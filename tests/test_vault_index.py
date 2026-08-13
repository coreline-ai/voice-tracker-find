# @TASK PB-T5 - VAULT_INDEX 생성
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_vault_index.py
from __future__ import annotations

import json
from pathlib import Path

from airvoice.vault_index import (
    TopicRecord,
    generate_vault_index,
    render_vault_index,
    scan_topic_notes,
)


def _write_topic(
    vault: Path,
    stem: str,
    *,
    title: str,
    date: str = "2026-07-19",
    items: list[str] | None = None,
    tags: list[str] | None = None,
    related: list[str] | None = None,
) -> None:
    """실제 20-notes 포맷의 주제 노트 fixture 를 만든다."""
    topics_dir = vault / "20-notes"
    topics_dir.mkdir(parents=True, exist_ok=True)
    tags = tags or []
    related = related or []
    parts = [
        "---",
        "type: topic",
        f"date: {date}",
        f"tags: [{', '.join(tags)}]",
        "---",
        f"# {title}",
        "",
    ]
    if items:
        parts.append(f"## {date}")
        parts.extend(f"- {it}" for it in items)
        parts.append("")
    parts.append("## 관련 주제")
    parts.extend(f"- [[{r}]]" for r in related)
    (topics_dir / f"{stem}.md").write_text("\n".join(parts) + "\n", encoding="utf-8")


# ─── scan ────────────────────────────────────────────────────────────


def test_주제노트에서_id_claim_tags_links를_뽑는다(tmp_path: Path) -> None:
    _write_topic(
        tmp_path,
        "55호기",
        title="55호기",
        items=["55호기 HMI 체크 시작", "전기 인입 완료"],
        tags=["#HMI", "#PLC"],
        related=["아이오-체크", "테스트-일정"],
    )

    records = scan_topic_notes(tmp_path)

    assert len(records) == 1
    r = records[0]
    assert r.id == "55호기"
    assert r.claim == "55호기 HMI 체크 시작"  # 최근 날짜 섹션 첫 항목
    assert r.tags == ["#HMI", "#PLC"]
    assert r.links == ["아이오-체크", "테스트-일정"]


def test_항목이_없으면_claim은_제목으로_폴백한다(tmp_path: Path) -> None:
    _write_topic(tmp_path, "빈주제", title="빈 주제 제목", items=None)

    records = scan_topic_notes(tmp_path)

    assert records[0].claim == "빈 주제 제목"


def test_주제폴더가_없으면_빈목록(tmp_path: Path) -> None:
    assert scan_topic_notes(tmp_path) == []


# ─── render ──────────────────────────────────────────────────────────


def _rec(id: str, claim: str = "문장", tags=None, links=None) -> TopicRecord:
    return TopicRecord(id=id, claim=claim, tags=tags or [], links=links or [])


def test_클러스터별_표로_렌더한다(tmp_path: Path) -> None:
    records = [_rec("투자", "손절로 평단 낮춤", ["#주식"], ["투자-전략"])]
    text = render_vault_index(records, {"투자": "재테크"}, "2026-07-21 12:00")

    assert "# Vault Index" in text
    assert "> 총 1개 주제, 1개 클러스터" in text
    assert "## 클러스터: 재테크 (1개)" in text
    assert "| ID | Claim | Tags | Links |" in text
    assert "| 투자 | 손절로 평단 낮춤 | #주식 | 투자-전략 |" in text


def test_클러스터는_큰것부터_내부는_id순(tmp_path: Path) -> None:
    records = [_rec("b"), _rec("a"), _rec("c")]
    cluster_of = {"a": "큰클러스터", "b": "큰클러스터", "c": "작은클러스터"}

    text = render_vault_index(records, cluster_of, "2026-07-21 12:00")

    big = text.index("## 클러스터: 큰클러스터")
    small = text.index("## 클러스터: 작은클러스터")
    assert big < small  # 큰 클러스터 먼저
    assert text.index("| a |") < text.index("| b |")  # 내부 id 순


def test_매핑에_없는_주제는_미분류로(tmp_path: Path) -> None:
    text = render_vault_index([_rec("떠돌이")], {}, "2026-07-21 12:00")
    assert "## 클러스터: 미분류 (1개)" in text


def test_파이프와_줄바꿈은_표를_깨지_않게_정리한다(tmp_path: Path) -> None:
    records = [_rec("t", "앞 | 뒤\n둘째줄", ["#a"], [])]
    text = render_vault_index(records, {"t": "c"}, "2026-07-21 12:00")

    row = next(ln for ln in text.splitlines() if ln.startswith("| t |"))
    assert row.count("|") == 5  # ID/Claim/Tags/Links 4열 = 파이프 5개
    assert "앞 / 뒤 둘째줄" in row


# ─── generate (end-to-end) ───────────────────────────────────────────


def test_전체흐름_인덱스생성_노트는_안건드림(tmp_path: Path) -> None:
    _write_topic(tmp_path, "투자", title="투자", items=["손절"])
    _write_topic(tmp_path, "민준이-태권도", title="민준이 태권도", items=["승급 심사"])
    note_path = tmp_path / "20-notes" / "투자.md"
    before = note_path.read_bytes()

    def fake_llm(_prompt: str) -> str:
        return json.dumps(
            {"투자": "재테크", "민준이-태권도": "육아"}, ensure_ascii=False
        )

    stats = generate_vault_index(tmp_path, fake_llm, now="2026-07-21 12:00")

    index = (tmp_path / "_index" / "VAULT_INDEX.md").read_text(encoding="utf-8")
    assert "## 클러스터: 재테크 (1개)" in index
    assert "## 클러스터: 육아 (1개)" in index
    assert stats == {"notes": 2, "clusters": 2}
    assert note_path.read_bytes() == before  # 원본 노트 불변


def test_생성물은_LF개행(tmp_path: Path) -> None:
    _write_topic(tmp_path, "t", title="t", items=["x"])

    generate_vault_index(
        tmp_path, lambda _p: json.dumps({"t": "c"}), now="2026-07-21 12:00"
    )

    raw = (tmp_path / "_index" / "VAULT_INDEX.md").read_bytes()
    assert b"\r\n" not in raw
