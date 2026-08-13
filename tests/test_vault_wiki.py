# @TASK PB-T7 - 클러스터 wiki 허브 생성
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_vault_wiki.py
from __future__ import annotations

import json
from pathlib import Path

from airvoice.vault_wiki import (
    Member,
    build_wiki_prompt,
    clusters_with_claims,
    generate_wiki_hubs,
    render_wiki_hub,
)

_INDEX = """# Vault Index
> 총 3개 주제, 2개 클러스터

## 클러스터: 재테크 (2개)
| ID | Claim | Tags | Links |
|----|-------|------|-------|
| 투자 | 시드머니는 최소 500만원 | #주식 | 투자-전략 |
| 투자-전략 | 상승장엔 분할매수 | #매매 | 투자 |

## 클러스터: 잡동사니 (1개)
| ID | Claim | Tags | Links |
|----|-------|------|-------|
| 기타 | 뭔가 | #x | |
"""


def test_인덱스에서_클러스터별_멤버를_복원한다() -> None:
    by_cluster = clusters_with_claims(_INDEX)
    assert [m.id for m in by_cluster["재테크"]] == ["투자", "투자-전략"]
    assert by_cluster["재테크"][0].claim == "시드머니는 최소 500만원"
    assert [m.id for m in by_cluster["잡동사니"]] == ["기타"]


def test_프롬프트에_claim과_JSON형식이_들어간다() -> None:
    prompt = build_wiki_prompt("재테크", [Member("투자", "시드머니 500만원")])
    assert "재테크" in prompt
    assert "투자 :: 시드머니 500만원" in prompt
    assert "question" in prompt and "gaps" in prompt


def test_허브_마크다운을_조립한다() -> None:
    members = [Member("투자", "시드머니 500만원"), Member("투자-전략", "분할매수")]
    data = {
        "question": "어떻게 돈을 굴리나?",
        "flow": "[[투자]]에서 시작해 [[투자-전략]]으로 이어진다.",
        "gaps": ["세금 전략 부재", "리밸런싱 규칙 없음"],
        "cross": ["소비-경제 클러스터와 연결"],
    }
    out = render_wiki_hub("재테크", members, data, "2026-07-21 22:00")

    assert "type: wiki" in out
    assert "topic: 재테크" in out
    assert "note_count: 2" in out
    assert "# 재테크" in out
    assert "> 어떻게 돈을 굴리나?" in out
    assert "| [[투자]] | 시드머니 500만원 |" in out  # 클릭 가능한 링크
    assert "[[투자-전략]]으로 이어진다" in out
    assert "- 세금 전략 부재" in out
    assert "## 교차 클러스터 연결" in out


def test_교차연결_없으면_섹션_생략() -> None:
    out = render_wiki_hub(
        "c", [Member("a", "x")], {"question": "q", "flow": "f", "gaps": ["g"]}, "t"
    )
    assert "## 교차 클러스터 연결" not in out


def _write_index(vault: Path) -> None:
    (vault / "_index").mkdir(parents=True, exist_ok=True)
    (vault / "_index" / "VAULT_INDEX.md").write_text(_INDEX, encoding="utf-8")


def test_작은_클러스터와_미분류는_건너뛴다(tmp_path: Path) -> None:
    _write_index(tmp_path)

    def fake_llm(_p: str) -> str:
        return json.dumps({"question": "q", "flow": "f", "gaps": ["g"]})

    stats = generate_wiki_hubs(tmp_path, fake_llm, min_notes=2, now="2026-07-21 22:00")

    assert (tmp_path / "1 wiki" / "재테크.md").exists()  # 2개 → 생성
    assert not (tmp_path / "1 wiki" / "잡동사니.md").exists()  # 1개 → 건너뜀
    assert stats == {"hubs": 1, "skipped": 1}


def test_only_로_한_클러스터만_생성(tmp_path: Path) -> None:
    _write_index(tmp_path)
    generate_wiki_hubs(
        tmp_path,
        lambda _p: json.dumps({"question": "q", "flow": "f", "gaps": ["g"]}),
        min_notes=1,
        only="재테크",
        now="2026-07-21 22:00",
    )
    assert (tmp_path / "1 wiki" / "재테크.md").exists()
    assert not (tmp_path / "1 wiki" / "잡동사니.md").exists()


def test_LLM이_CRLF를_줘도_파일은_LF(tmp_path: Path) -> None:
    _write_index(tmp_path)
    # 종합 텍스트에 CR 이 섞여 들어오는 상황(실제로 발생).
    data = {"question": "q", "flow": "첫줄\r\n둘째줄", "gaps": ["g\r\n"]}
    generate_wiki_hubs(
        tmp_path, lambda _p: json.dumps(data), min_notes=2, now="2026-07-21 22:00"
    )
    raw = (tmp_path / "1 wiki" / "재테크.md").read_bytes()
    assert b"\r" not in raw
