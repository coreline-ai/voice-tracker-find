# @TASK PB-T4 - 주제 클러스터 배정 (VAULT_INDEX 기반)
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_cluster.py
"""주제 노트를 의미 있는 클러스터로 묶는다.

하이브리드 스코어러의 구조 축(같은 클러스터 +0.4)이 실제로 작동하려면 각 주제에
클러스터가 있어야 한다. thinktank 주제 노트에는 클러스터 개념이 없어서(실측),
구조 축이 0 이 되고 하이브리드가 임베딩 단독으로 퇴화했다. 이 모듈이 그 구멍을
메운다.

Claude 로 주제들을 배치 분류한다. LLM 호출은 DI 로 주입해 테스트에서 가짜로
대체한다. 노트를 수정하지 않고, 매핑(주제 -> 클러스터)만 만든다 — 그 매핑으로
VAULT_INDEX 를 쓰고, frontmatter 갱신은 별도 단계에서 한다.
"""

from __future__ import annotations

import json
import logging
from collections.abc import Callable
from dataclasses import dataclass

logger = logging.getLogger(__name__)

# 한 번의 LLM 호출에 넣을 주제 수. 너무 크면 프롬프트가 길어지고 분류가 흐려지며,
# 너무 작으면 호출 수가 늘고 클러스터가 배치마다 파편화된다.
DEFAULT_BATCH_SIZE = 60

# 주제(문자열) -> Claude 에 보낼 짧은 claim. 주제만으로는 문맥이 부족하므로
# 대표 문장을 함께 준다.
ClaimFn = Callable[[str], str]
# 프롬프트 -> 응답 텍스트 (claude_cli.run_claude_cli 또는 테스트용 가짜).
LlmFn = Callable[[str], str]


@dataclass(frozen=True)
class TopicClaim:
    """분류 대상 주제 하나와 그 대표 문장."""

    topic: str
    claim: str


def _strip_code_fence(text: str) -> str:
    """마크다운 코드펜스를 제거한다(extract._strip_code_fence 와 동일 규약)."""
    stripped = text.strip()
    if not stripped.startswith("```"):
        return stripped
    lines = stripped.splitlines()[1:]
    if lines and lines[-1].strip() == "```":
        lines = lines[:-1]
    return "\n".join(lines).strip()


def _parse_object(raw_text: str) -> dict:
    """LLM 응답에서 JSON 객체를 방어적으로 뽑는다."""
    cleaned = _strip_code_fence(raw_text)
    try:
        obj = json.loads(cleaned)
    except json.JSONDecodeError:
        start, end = cleaned.find("{"), cleaned.rfind("}")
        if start == -1 or end == -1 or end < start:
            raise ValueError(
                f"클러스터 응답에서 JSON 을 찾을 수 없습니다: {raw_text!r}"
            ) from None
        obj = json.loads(cleaned[start : end + 1])
    if not isinstance(obj, dict):
        raise ValueError("클러스터 응답이 JSON 객체가 아닙니다.")
    return obj


def build_prompt(batch: list[TopicClaim], existing_clusters: list[str]) -> str:
    """주제 배치를 클러스터로 분류시키는 프롬프트를 만든다.

    이전 배치가 만든 클러스터 목록을 함께 줘서, 배치가 갈려도 같은 개념이 같은
    클러스터명으로 모이게 한다(파편화 방지).
    """
    lines = [
        "다음 주제들을 의미가 통하는 클러스터로 묶어라.",
        "각 주제를 정확히 하나의 클러스터에 배정한다.",
        "클러스터명은 넓은 상위 개념으로(예: '재테크', '육아', '설비-엔지니어링').",
        "너무 잘게 나누지 말 것 — 20~40개 주제가 한 클러스터에 들어가도 좋다.",
        "",
    ]
    if existing_clusters:
        lines.append("이미 있는 클러스터(가능하면 재사용):")
        lines.append(", ".join(existing_clusters))
        lines.append("")
    lines.append("주제 목록 (주제 :: 대표 문장):")
    for item in batch:
        lines.append(f"- {item.topic} :: {item.claim}")
    lines.append("")
    lines.append('출력은 JSON 객체만. 형식: {"주제명": "클러스터명", ...}')
    lines.append("설명 없이 JSON 만 출력한다.")
    return "\n".join(lines)


def assign_clusters(
    topics: list[TopicClaim],
    llm: LlmFn,
    *,
    batch_size: int = DEFAULT_BATCH_SIZE,
    seed_clusters: list[str] | None = None,
) -> dict[str, str]:
    """주제들을 클러스터로 배정한다.

    배치로 나눠 LLM 을 부르되, 앞 배치가 만든 클러스터 목록을 다음 배치에
    전달해 같은 개념이 같은 이름으로 모이게 한다.

    Args:
        topics: 분류할 (주제, claim) 목록.
        llm: 프롬프트 -> 응답 텍스트 함수.
        batch_size: 한 호출당 주제 수.
        seed_clusters: 이미 있는 클러스터명(증분 정규화용). 첫 배치부터 이 이름들을
            "재사용 가능" 목록에 넣어, 새 주제를 기존 클러스터에 붙게 유도한다.

    Returns:
        주제 -> 클러스터명 매핑. 응답에서 빠진 주제는 "미분류".
    """
    mapping: dict[str, str] = {}
    clusters: list[str] = list(seed_clusters) if seed_clusters else []

    for start in range(0, len(topics), batch_size):
        batch = topics[start : start + batch_size]
        prompt = build_prompt(batch, clusters)
        try:
            result = _parse_object(llm(prompt))
        except (ValueError, json.JSONDecodeError) as exc:
            logger.warning("클러스터 배치 실패(%d~), 미분류 처리: %s", start, exc)
            for item in batch:
                mapping[item.topic] = "미분류"
            continue

        for item in batch:
            cluster = str(result.get(item.topic, "미분류")).strip() or "미분류"
            mapping[item.topic] = cluster
            if cluster != "미분류" and cluster not in clusters:
                clusters.append(cluster)

    return mapping
