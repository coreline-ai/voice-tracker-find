# @TASK PB-T7 - 클러스터 wiki 허브 생성 (1 wiki/{클러스터}.md)
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_vault_wiki.py
"""클러스터별 개념 허브 페이지를 `1 wiki/` 에 생성한다(카파시 LLM Wiki 패턴).

핸드오프 §4-M 볼트 적용 3단계. 결정: 파편난 원자 노트를 억지로 합치지 않고,
클러스터마다 허브 한 장이 그 주제들의 **claim 만 읽어** 종합 + Gap Analysis 를
제공해 진입점만 통합한다(투자 12개 → 재테크 허브).

LLM(Claude)에는 종합 프롬프트로 claim 목록만 보내 JSON(질문/흐름/빈구멍/교차연결)을
받고, 허브 마크다운은 이 모듈이 결정적으로 조립한다(핵심 주장 표는 인덱스에서 그대로,
링크는 `[[주제]]` 로 클릭 가능). 개별 노트 본문은 읽지 않는다(토큰 절약).
"""

from __future__ import annotations

import logging
import re
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from thinktank.cluster import LlmFn, _parse_object
from thinktank.vault_index import (
    INDEX_SUBDIR,
    VAULT_INDEX_FILENAME,
    _write_lf,
)

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

logger = logging.getLogger(__name__)

WIKI_SUBDIR = "1 wiki"
MIN_HUB_NOTES = 5
# 큰 클러스터(설비 382개)의 claim 을 한 프롬프트로 종합하므로 넉넉히.
WIKI_CLI_TIMEOUT = 600.0

_CLUSTER_HEADER_RE = re.compile(r"^## 클러스터: (.+?) \(\d+개\)$")


@dataclass(frozen=True)
class Member:
    """허브가 가리키는 주제 1건."""

    id: str
    claim: str


def clusters_with_claims(index_text: str) -> dict[str, list[Member]]:
    """VAULT_INDEX 에서 {클러스터: [Member(id, claim), ...]} 를 복원한다."""
    result: dict[str, list[Member]] = {}
    current: str | None = None
    for line in index_text.splitlines():
        header = _CLUSTER_HEADER_RE.match(line)
        if header:
            current = header.group(1)
            result.setdefault(current, [])
            continue
        if current and line.startswith("| ") and not line.startswith("| ID"):
            cells = [c.strip() for c in line.split("|")[1:-1]]
            if len(cells) >= 2 and cells[0]:
                result[current].append(Member(id=cells[0], claim=cells[1]))
    return result


def build_wiki_prompt(cluster: str, members: list[Member]) -> str:
    """클러스터의 claim 목록을 종합시키는 프롬프트(JSON 응답 요구)를 만든다."""
    lines = [
        f'"{cluster}" 클러스터에 묶인 주제들의 핵심 주장 목록이다.',
        "이 주장들을 종합해 개념 허브 페이지의 재료를 JSON 으로 만들어라.",
        "개별 노트 본문은 없다. 아래 claim 만으로 판단한다.",
        "",
        "주제 목록 (주제ID :: 핵심 주장):",
    ]
    lines += [f"- {m.id} :: {m.claim}" for m in members]
    lines += [
        "",
        "JSON 객체 하나만 출력한다. 형식:",
        "{",
        '  "question": "이 클러스터가 답하려는 핵심 질문 한 문장",',
        '  "flow": "주요 흐름 3~5문단(마크다운). 관련 주제는 [[주제ID]] 로 인용",',
        '  "gaps": ["아직 다루지 않은 빈 구멍 2~4개"],',
        '  "cross": ["다른 클러스터와 이어질 지점 0~3개"]',
        "}",
        "설명 없이 JSON 만 출력한다.",
    ]
    return "\n".join(lines)


def render_wiki_hub(
    cluster: str, members: list[Member], data: dict, generated: str
) -> str:
    """LLM 종합 데이터(data)와 멤버 목록으로 허브 마크다운을 조립한다."""
    question = str(data.get("question", "")).strip()
    flow = str(data.get("flow", "")).strip()
    gaps = [str(g).strip() for g in data.get("gaps", []) if str(g).strip()]
    cross = [str(c).strip() for c in data.get("cross", []) if str(c).strip()]

    lines = [
        "---",
        "type: wiki",
        f"topic: {cluster}",
        f"note_count: {len(members)}",
        f'generated: "{generated}"',
        "---",
        "",
        f"# {cluster}",
        "",
        f"> {question}",
        "",
        "## 핵심 주장들",
        "",
        f"이 클러스터의 {len(members)}개 주제:",
        "",
        "| 주제 | 핵심 주장 |",
        "|------|-----------|",
    ]
    lines += [f"| [[{m.id}]] | {m.claim} |" for m in members]
    lines += ["", "## 주요 흐름", "", flow, "", "## 빈 구멍 (Gap Analysis)", ""]
    lines += [f"- {g}" for g in gaps]
    if cross:
        lines += ["", "## 교차 클러스터 연결", ""]
        lines += [f"- {c}" for c in cross]
    lines += [
        "",
        "---",
        "> [!info] 자동 생성 — 원자 노트가 원본, 이 허브는 claim 종합. "
        "`python -m thinktank.vault_wiki`.",
    ]
    return "\n".join(lines) + "\n"


def generate_wiki_hubs(
    vault_path: str | Path,
    llm: LlmFn,
    *,
    min_notes: int = MIN_HUB_NOTES,
    only: str | None = None,
    now: str | None = None,
) -> dict[str, int]:
    """클러스터별 허브를 `1 wiki/` 에 생성한다.

    Args:
        vault_path: 볼트 루트.
        llm: 프롬프트 -> 응답 텍스트.
        min_notes: 이 개수 이상인 클러스터만 허브 생성.
        only: 지정하면 그 클러스터 하나만 생성(proof/재생성용).
        now: generated 타임스탬프. None 이면 현재 시각.

    Returns:
        {"hubs": 생성한 허브 수, "skipped": 건너뛴 클러스터 수}.
    """
    vault = Path(vault_path).expanduser()
    index_text = (vault / INDEX_SUBDIR / VAULT_INDEX_FILENAME).read_text(
        encoding="utf-8"
    )
    by_cluster = clusters_with_claims(index_text)

    wiki_dir = vault / WIKI_SUBDIR
    stamp = now or datetime.now().strftime("%Y-%m-%d %H:%M")

    hubs = 0
    skipped = 0
    for cluster, members in by_cluster.items():
        if only is not None and cluster != only:
            continue
        if cluster == "미분류" or len(members) < min_notes:
            skipped += 1
            continue
        try:
            data = _parse_object(llm(build_wiki_prompt(cluster, members)))
        except Exception as exc:  # noqa: BLE001 - 한 허브 실패가 전체를 막으면 안 됨
            logger.warning("허브 생성 실패(%s): %s", cluster, exc)
            skipped += 1
            continue
        content = render_wiki_hub(cluster, members, data, stamp)
        _write_lf(wiki_dir / f"{cluster}.md", content)
        hubs += 1
        logger.info("허브 생성: %s (%d개 주제)", cluster, len(members))

    return {"hubs": hubs, "skipped": skipped}


def _robust_cli_llm(prompt: str) -> str:
    """claude_cli 를 넉넉한 타임아웃 + 1회 재시도로 감싼다."""
    from thinktank.claude_cli import run_claude_cli

    for attempt in (1, 2):
        try:
            return run_claude_cli(prompt, timeout=WIKI_CLI_TIMEOUT)
        except Exception as exc:  # noqa: BLE001
            logger.warning("허브 CLI 실패(시도 %d): %s", attempt, exc)
    return "{}"


def main() -> None:
    """CLI 진입점: 실 설정 + claude_cli 로 허브를 생성한다."""
    import argparse

    from thinktank.config import load_settings

    logging.basicConfig(level=logging.INFO)
    parser = argparse.ArgumentParser(description="클러스터 wiki 허브 생성")
    parser.add_argument("--only", default=None, help="이 클러스터 하나만 생성")
    args = parser.parse_args()

    settings = load_settings()
    stats = generate_wiki_hubs(
        settings.obsidian_vault, _robust_cli_llm, only=args.only
    )
    print(f"✅ 허브 {stats['hubs']}개 생성, {stats['skipped']}개 건너뜀")


if __name__ == "__main__":
    main()
