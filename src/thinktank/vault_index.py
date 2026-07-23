# @TASK PB-T5 - VAULT_INDEX 생성 (주제 클러스터링 → _index/VAULT_INDEX.md)
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_vault_index.py
"""20-notes 주제 노트를 클러스터링해 `_index/VAULT_INDEX.md` 를 생성한다.

핸드오프 §4-M 의 볼트 적용 1단계. cluster.assign_clusters 로 각 주제에 클러스터를
배정하고, 클러스터별 표(`| ID | Claim | Tags | Links |`)로 렌더한다. 포맷은
zettel-connect 플러그인 파서(index-reader.ts)와 호환되며 `/wiki`·`/lint` 가 이
파일만 읽어 동작한다.

**노트를 수정하지 않는다 — 인덱스만 만든다.** frontmatter 에 cluster 주입, sources
이동, wiki 허브 생성은 각각 별도 단계다(§4-M 다음 2·3).

식별자 규약: 주제 노트 파일명 stem 이 slug 이고, 노트끼리의 연결(related)도 이
slug 를 참조한다. 따라서 표의 ID 열은 stem(slug), Links 열은 related slug 목록이라
서로 참조가 맞물린다. Claim 열은 사람이 읽는 대표 문장(가장 최근 날짜 섹션의 첫
항목, 없으면 제목)이다.
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from thinktank.cluster import LlmFn, TopicClaim, assign_clusters
from thinktank.topics import TOPICS_SUBDIR, _parse_topic_note

# 한글/이모지 출력이 Windows 콘솔 기본 인코딩(cp949)에서 죽지 않게.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

INDEX_SUBDIR = "_index"
VAULT_INDEX_FILENAME = "VAULT_INDEX.md"
FALLBACK_CLUSTER = "미분류"

# 클러스터링 프롬프트(주제 60개 + 누적 클러스터 목록)는 길어서 claude_cli 기본
# 120초로는 부족하다(실측 최대 119초로 타임아웃 다발). emerge 처럼 넉넉히 잡는다.
CLUSTER_CLI_TIMEOUT = 600.0

_TABLE_HEADER = "| ID | Claim | Tags | Links |"
_SEPARATOR = "|----|-------|------|-------|"


@dataclass(frozen=True)
class TopicRecord:
    """VAULT_INDEX 한 줄이 될 주제 노트 1건."""

    id: str  # 파일명 stem(slug) — related 링크가 참조하는 식별자
    claim: str  # 대표 문장
    tags: list[str]
    links: list[str]  # related slug 목록


def _cell(text: str) -> str:
    """표 셀 안전화: 줄바꿈·연속 공백을 한 칸으로, 파이프는 표를 깨므로 '/' 로."""
    return re.sub(r"\s+", " ", text).replace("|", "/").strip()


def _claim_of(title: str, entries: list, stem: str) -> str:
    """대표 문장 = 가장 최근 날짜 섹션의 첫 항목, 없으면 제목, 그것도 없으면 stem."""
    if entries and entries[0].items:
        return entries[0].items[0]
    return title or stem


def scan_topic_notes(vault_path: str | Path) -> list[TopicRecord]:
    """20-notes/*.md 를 파싱해 TopicRecord 목록으로 반환한다(파일명 순).

    노트를 읽기만 한다. frontmatter 가 없거나 파싱 불가한 파일은 건너뛴다.
    """
    topics_dir = Path(vault_path).expanduser() / TOPICS_SUBDIR
    if not topics_dir.is_dir():
        return []

    records: list[TopicRecord] = []
    for path in sorted(topics_dir.glob("*.md")):
        title, entries, related, tags, _sources = _parse_topic_note(
            path.read_text(encoding="utf-8")
        )
        records.append(
            TopicRecord(
                id=path.stem,
                claim=_claim_of(title, entries, path.stem),
                tags=tags,
                links=related,
            )
        )
    return records


def _order_clusters(
    records: list[TopicRecord], cluster_of: dict[str, str]
) -> list[tuple[str, list[TopicRecord]]]:
    """클러스터를 (큰 것 먼저, 동률이면 이름순)으로, 내부는 ID순으로 정렬한다."""
    by_cluster: dict[str, list[TopicRecord]] = {}
    for record in records:
        cluster = cluster_of.get(record.id, FALLBACK_CLUSTER)
        by_cluster.setdefault(cluster, []).append(record)
    for members in by_cluster.values():
        members.sort(key=lambda r: r.id)
    return sorted(by_cluster.items(), key=lambda kv: (-len(kv[1]), kv[0]))


def render_vault_index(
    records: list[TopicRecord], cluster_of: dict[str, str], now: str
) -> str:
    """주제 레코드와 클러스터 매핑으로 VAULT_INDEX.md 전체 문자열을 만든다."""
    clusters = _order_clusters(records, cluster_of)
    lines = [
        "# Vault Index",
        "> 자동 생성됨. 수동 편집 금지.",
        f"> 최종 갱신: {now}",
        f"> 총 {len(records)}개 주제, {len(clusters)}개 클러스터",
    ]
    for name, members in clusters:
        lines.append("")
        lines.append(f"## 클러스터: {name} ({len(members)}개)")
        lines.append(_TABLE_HEADER)
        lines.append(_SEPARATOR)
        for r in members:
            tags = _cell(", ".join(r.tags))
            links = _cell(", ".join(r.links))
            lines.append(f"| {_cell(r.id)} | {_cell(r.claim)} | {tags} | {links} |")
    return "\n".join(lines) + "\n"


def _write_lf(path: Path, content: str) -> None:
    """LF 개행 고정으로 UTF-8 쓰기.

    Windows 기본 CRLF 변환을 피할 뿐 아니라, 내용에 섞여 들어온 CR(예: LLM 종합
    텍스트의 `\\r\\n`)도 LF 로 정규화해 개행을 일관되게 유지한다.
    """
    normalized = content.replace("\r\n", "\n").replace("\r", "\n")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(normalized.encode("utf-8"))


def generate_vault_index(
    vault_path: str | Path,
    llm: LlmFn,
    *,
    batch_size: int = 60,
    now: str | None = None,
) -> dict[str, int]:
    """주제 노트를 클러스터링해 `_index/VAULT_INDEX.md` 를 생성한다(노트 미변경).

    Args:
        vault_path: Obsidian 볼트 루트.
        llm: 프롬프트 -> 응답 텍스트 (claude_cli.run_claude_cli 또는 테스트용 가짜).
        batch_size: 클러스터링 한 배치당 주제 수.
        now: 헤더 타임스탬프. None 이면 현재 시각.

    Returns:
        {"notes": 주제 수, "clusters": 클러스터 수}.
    """
    records = scan_topic_notes(vault_path)
    topic_claims = [TopicClaim(topic=r.id, claim=r.claim) for r in records]
    cluster_of = assign_clusters(topic_claims, llm, batch_size=batch_size)

    stamp = now or datetime.now().strftime("%Y-%m-%d %H:%M")
    content = render_vault_index(records, cluster_of, stamp)

    index_path = Path(vault_path).expanduser() / INDEX_SUBDIR / VAULT_INDEX_FILENAME
    _write_lf(index_path, content)

    return {"notes": len(records), "clusters": len(set(cluster_of.values()))}


def _robust_cli_llm(prompt: str) -> str:
    """claude_cli 호출을 넉넉한 타임아웃 + 1회 재시도로 감싼다.

    22배치 × 몇 분짜리 작업이라 한 번의 타임아웃/오류로 그 배치 60개가 통째로
    미분류가 되지 않게 재시도하고, 그래도 실패하면 빈 객체로 흘려보내(그 배치만
    미분류) 전체 작업은 완주시킨다.
    """
    import logging

    from thinktank.claude_cli import run_claude_cli

    for attempt in (1, 2):
        try:
            return run_claude_cli(prompt, timeout=CLUSTER_CLI_TIMEOUT)
        except Exception as exc:  # noqa: BLE001 - 한 배치 실패가 전체를 막으면 안 됨
            logging.getLogger(__name__).warning(
                "클러스터 배치 CLI 실패(시도 %d): %s", attempt, exc
            )
    return "{}"


def main() -> None:
    """CLI 진입점: 실 설정 + claude_cli 로 VAULT_INDEX 를 생성한다."""
    import logging

    from thinktank.config import load_settings

    logging.basicConfig(level=logging.INFO)
    settings = load_settings()
    stats = generate_vault_index(settings.obsidian_vault, _robust_cli_llm)
    n, c = stats["notes"], stats["clusters"]
    print(f"✅ VAULT_INDEX 생성: 주제 {n}개 / 클러스터 {c}개")


if __name__ == "__main__":
    main()
