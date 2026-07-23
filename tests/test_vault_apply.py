# @TASK PB-T6 - VAULT_INDEX 클러스터 노트 적용
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_vault_apply.py
from __future__ import annotations

from pathlib import Path

from thinktank.vault_apply import (
    apply_clusters,
    parse_index_clusters,
    transform_note,
)

_NOTE = """---
type: topic
date: 2026-07-19
tags: [#HMI, #PLC]
sources: [rec_a.m4a (2026-07-19), rec_b.m4a (2026-07-18)]
related: [[아이오-체크]]
---
# 55호기

## 2026-07-19
- HMI 체크 시작

## 관련 주제
- [[아이오-체크]]
"""


# ─── transform_note ──────────────────────────────────────────────────


def test_cluster를_date뒤에_주입한다() -> None:
    out = transform_note(_NOTE, "설비-엔지니어링")
    lines = out.splitlines()
    assert lines[2] == "date: 2026-07-19"
    assert lines[3] == "cluster: 설비-엔지니어링"


def test_sources를_본문_출처섹션으로_옮긴다() -> None:
    out = transform_note(_NOTE, "설비-엔지니어링")
    # frontmatter 에서 sources 사라짐
    frontmatter = out.split("---")[1]
    assert "sources:" not in frontmatter
    # 본문 맨 뒤 ## 출처 로 이동
    assert out.rstrip().endswith(
        "## 출처\n- rec_a.m4a (2026-07-19)\n- rec_b.m4a (2026-07-18)"
    )


def test_기존_본문은_보존된다() -> None:
    out = transform_note(_NOTE, "설비-엔지니어링")
    assert "# 55호기" in out
    assert "## 2026-07-19" in out
    assert "- HMI 체크 시작" in out
    assert "## 관련 주제" in out


def test_여러번_적용해도_결과가_같다() -> None:
    once = transform_note(_NOTE, "설비-엔지니어링")
    twice = transform_note(once, "설비-엔지니어링")
    assert once == twice


def test_재적용시_cluster만_바꾸고_출처는_보존() -> None:
    once = transform_note(_NOTE, "설비-엔지니어링")
    changed = transform_note(once, "다른클러스터")
    assert "cluster: 다른클러스터" in changed
    assert "cluster: 설비-엔지니어링" not in changed
    assert changed.count("## 출처") == 1  # 출처 중복 안 됨
    assert "- rec_a.m4a (2026-07-19)" in changed


def test_sources_없는_노트는_본문을_안건드림() -> None:
    note = (
        "---\ntype: topic\ndate: 2026-07-19\ntags: [#x]\n---\n"
        "# 제목\n\n## 관련 주제\n"
    )
    out = transform_note(note, "클러스터A")
    assert "cluster: 클러스터A" in out
    assert "## 출처" not in out


def test_frontmatter_없으면_원본_그대로() -> None:
    raw = "# 프론트매터 없음\n내용\n"
    assert transform_note(raw, "아무클러스터") == raw


# ─── parse_index_clusters ────────────────────────────────────────────


def test_인덱스에서_id_클러스터_매핑을_복원한다() -> None:
    index = (
        "# Vault Index\n> 총 2개 주제, 1개 클러스터\n\n"
        "## 클러스터: 재테크 (2개)\n"
        "| ID | Claim | Tags | Links |\n"
        "|----|-------|------|-------|\n"
        "| 투자 | c | #주식 | 투자-전략 |\n"
        "| 투자-전략 | c | #a | 투자 |\n"
    )
    mapping = parse_index_clusters(index)
    assert mapping == {"투자": "재테크", "투자-전략": "재테크"}


# ─── apply_clusters (end-to-end) ─────────────────────────────────────


def test_전체적용_노트에_cluster가_들어간다(tmp_path: Path) -> None:
    (tmp_path / "20-notes").mkdir()
    (tmp_path / "20-notes" / "투자.md").write_text(_NOTE, encoding="utf-8")
    (tmp_path / "_index").mkdir()
    (tmp_path / "_index" / "VAULT_INDEX.md").write_text(
        "## 클러스터: 재테크 (1개)\n"
        "| ID | Claim | Tags | Links |\n"
        "|----|-------|------|-------|\n"
        "| 투자 | c | #주식 | |\n",
        encoding="utf-8",
    )

    stats = apply_clusters(tmp_path)

    out = (tmp_path / "20-notes" / "투자.md").read_text(encoding="utf-8")
    assert "cluster: 재테크" in out
    assert "## 출처" in out
    assert stats == {"updated": 1, "missing": 0}
    assert b"\r\n" not in (tmp_path / "20-notes" / "투자.md").read_bytes()
