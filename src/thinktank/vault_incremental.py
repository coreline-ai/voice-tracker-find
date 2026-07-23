# @TASK - 증분 정규화 (새 주제만 기존 클러스터에 배정)
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_vault_incremental.py
"""새로 생긴 주제 노트만 기존 클러스터에 배정하는 증분 정규화.

전체 재클러스터(:mod:`thinktank.vault_index`)는 매번 배정이 바뀌어 볼트가 출렁이고,
수동으로 나눈 하위 클러스터(예: 설비-* 분할)가 원복된다. 이 모듈은 **인덱스에 이미
있는 배정은 그대로 두고**, 아직 없거나 미분류인 주제만 기존 클러스터명을 재사용해
배정한다. 야간에 얹어 하루치 새 노트를 구조에 편입시키는 용도다.
"""

from __future__ import annotations

import logging
import sys
import time
from datetime import datetime
from pathlib import Path

from thinktank.cluster import LlmFn, TopicClaim, assign_clusters
from thinktank.vault_apply import apply_clusters, parse_index_clusters
from thinktank.vault_index import (
    INDEX_SUBDIR,
    VAULT_INDEX_FILENAME,
    _write_lf,
    render_vault_index,
    scan_topic_notes,
)
from thinktank.vault_wiki import generate_wiki_hubs

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

logger = logging.getLogger(__name__)

CLI_TIMEOUT = 600.0


def normalize_incremental(
    vault_path: str | Path, llm: LlmFn, *, now: str | None = None
) -> dict[str, int]:
    """인덱스에 없거나 미분류인 주제만 기존 클러스터에 (재)배정한다.

    Args:
        vault_path: Obsidian 볼트 루트.
        llm: 프롬프트 -> 응답 텍스트.
        now: 인덱스 타임스탬프. None 이면 현재 시각.

    Returns:
        {"new": 재배정 대상 주제 수, "hubs": 갱신한 허브 수}.
    """
    vault = Path(vault_path).expanduser()
    index_path = vault / INDEX_SUBDIR / VAULT_INDEX_FILENAME
    records = scan_topic_notes(vault)
    current = (
        parse_index_clusters(index_path.read_text(encoding="utf-8"))
        if index_path.is_file()
        else {}
    )

    # 인덱스에 없거나 미분류인 주제만 (재)배정한다. 성공적으로 배정된 것은 안 건드려
    # 기존 구조(수동 분할 포함)를 보존한다. 미분류는 매번 다시 시도해 자가 치유한다.
    todo = [record for record in records if current.get(record.id) in (None, "미분류")]
    if not todo:
        return {"new": 0, "hubs": 0}

    seed = sorted({cluster for cluster in current.values() if cluster != "미분류"})
    assigned = assign_clusters(
        [TopicClaim(topic=record.id, claim=record.claim) for record in todo],
        llm,
        seed_clusters=seed,
    )

    merged = dict(current)
    merged.update(assigned)
    stamp = now or datetime.now().strftime("%Y-%m-%d %H:%M")
    _write_lf(index_path, render_vault_index(records, merged, stamp))
    apply_clusters(vault)

    # 새 멤버가 붙은 클러스터의 허브만 다시 만든다(전체 재생성 안 함).
    affected = sorted({cluster for cluster in assigned.values() if cluster != "미분류"})
    hubs = sum(
        generate_wiki_hubs(vault, llm, only=cluster)["hubs"] for cluster in affected
    )
    return {"new": len(todo), "hubs": hubs}


def _robust_cli_llm(prompt: str, tries: int = 3) -> str:
    """claude_cli 를 재시도로 감싼다. 총 실패 시 '{}' 로 흘려 볼트를 망가뜨리지 않는다.

    (그 배치의 새 주제는 미분류로 남고, 미분류는 다음 실행에서 다시 시도된다.)
    """
    from thinktank.claude_cli import run_claude_cli

    last: object = None
    for attempt in range(1, tries + 1):
        try:
            out = run_claude_cli(prompt, timeout=CLI_TIMEOUT)
            if out and out.strip():
                return out
            last = "빈 응답"
        except Exception as exc:  # noqa: BLE001 - 재시도로 흡수
            last = exc
        logger.warning("증분 정규화 CLI 재시도 %d/%d: %s", attempt, tries, last)
        time.sleep(5 * attempt)
    return "{}"


def main() -> None:
    """CLI 진입점: 실 설정 + claude_cli 로 기본 볼트에 증분 정규화를 적용한다."""
    import logging as _logging

    from thinktank.config import load_settings

    _logging.basicConfig(level=_logging.INFO)
    settings = load_settings()
    stats = normalize_incremental(settings.obsidian_vault, _robust_cli_llm)
    print(f"✅ 증분 정규화: 새 주제 {stats['new']}개, 허브 {stats['hubs']}개 갱신")


if __name__ == "__main__":
    main()
