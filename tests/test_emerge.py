# @TASK P4-R3-T1 - 창발 아이디어 엔진 (TDD RED)
# @SPEC docs/planning/06-tasks.md#P4-R3-T1
# @TEST tests/test_emerge.py
from __future__ import annotations

import dataclasses
import json
from pathlib import Path

import pytest

from thinktank.emerge import (
    EmergedIdea,
    Evidence,
    count_topic_items,
    filter_new_entries,
    last_emerge_date,
    load_emerger,
    parse_emerge_response,
    run_emerge,
    scan_new_topic_entries,
    scan_prior_ideas,
    scan_topic_notes,
)

try:
    import anthropic  # noqa: F401

    _ANTHROPIC_AVAILABLE = True
except ImportError:
    _ANTHROPIC_AVAILABLE = False


_TOPIC_NOTE_TEMPLATE = """---
type: topic
date: {date}
tags: [{tags}]
sources: [{sources}]
---
# {title}

## {date}
{items}

## 관련 주제
{related}
"""


def _write_topic_note(
    vault_path: Path,
    slug: str,
    title: str = "제목",
    date: str = "2025-01-15",
    items: list[str] | None = None,
    related: list[str] | None = None,
    tags: list[str] | None = None,
    sources: list[str] | None = None,
) -> Path:
    notes_dir = vault_path / "20-notes"
    notes_dir.mkdir(parents=True, exist_ok=True)
    items_text = "\n".join(f"- {item}" for item in (items or ["기본 항목"]))
    related_text = "\n".join(f"- [[{name}]]" for name in (related or []))
    content = _TOPIC_NOTE_TEMPLATE.format(
        title=title,
        date=date,
        items=items_text,
        related=related_text,
        tags=", ".join(tags or []),
        sources=", ".join(sources or ["recording_1.m4a (2025-01-15)"]),
    )
    path = notes_dir / f"{slug}.md"
    path.write_text(content, encoding="utf-8")
    return path


# ---------------------------------------------------------------------------
# Evidence / EmergedIdea: 창발 노트 생성(P4-S3-T1)이 소비하는 계약
# ---------------------------------------------------------------------------


def test_evidence_is_frozen_dataclass():
    evidence = Evidence(topic="design", mention_count=5)
    assert evidence.topic == "design"
    assert evidence.mention_count == 5
    with pytest.raises(dataclasses.FrozenInstanceError):
        evidence.mention_count = 6  # type: ignore[misc]


def test_emerged_idea_is_frozen_dataclass():
    idea = EmergedIdea(
        title="제목",
        date="2025-01-15",
        sequence=1,
        tags=["#design"],
        insight="통찰",
        examples=["사례1"],
        next_steps=["액션1"],
        evidence=[Evidence(topic="design", mention_count=3)],
    )
    assert idea.title == "제목"
    assert idea.date == "2025-01-15"
    assert idea.sequence == 1
    assert idea.tags == ["#design"]
    assert idea.insight == "통찰"
    assert idea.examples == ["사례1"]
    assert idea.next_steps == ["액션1"]
    assert idea.evidence == [Evidence(topic="design", mention_count=3)]
    with pytest.raises(dataclasses.FrozenInstanceError):
        idea.title = "변경"  # type: ignore[misc]


# ---------------------------------------------------------------------------
# scan_topic_notes: 20-notes/*.md 수집 (frontmatter 제거, sources 미포함)
# ---------------------------------------------------------------------------


def test_scan_topic_notes_returns_empty_dict_when_dir_missing(tmp_path: Path):
    assert scan_topic_notes(tmp_path) == {}


def test_scan_topic_notes_collects_slug_to_body(tmp_path: Path):
    _write_topic_note(tmp_path, "design", title="Design", items=["아이디어1"])
    _write_topic_note(tmp_path, "ai-features", title="AI Features", items=["아이디어2"])

    result = scan_topic_notes(tmp_path)

    assert set(result) == {"design", "ai-features"}
    assert "# Design" in result["design"]
    assert "- 아이디어1" in result["design"]


def test_scan_topic_notes_strips_frontmatter_and_sources(tmp_path: Path):
    _write_topic_note(
        tmp_path, "design", sources=["private_recording_1.m4a (2025-01-15)"]
    )

    result = scan_topic_notes(tmp_path)

    assert "private_recording_1.m4a" not in result["design"]
    assert "type: topic" not in result["design"]


# ---------------------------------------------------------------------------
# count_topic_items: 날짜 섹션 불릿 개수 (관련 주제 섹션 제외)
# ---------------------------------------------------------------------------


def test_count_topic_items_counts_bullets_under_date_sections():
    body = "# 제목\n\n## 2025-01-15\n- 항목1\n- 항목2\n\n## 관련 주제\n- [[feature]]\n"
    assert count_topic_items(body) == 2


def test_count_topic_items_excludes_related_section_bullets():
    body = "# 제목\n\n## 관련 주제\n- [[feature]]\n- [[design]]\n"
    assert count_topic_items(body) == 0


def test_count_topic_items_sums_across_multiple_date_sections():
    body = (
        "# 제목\n\n## 2025-01-15\n- 항목1\n\n## 2025-01-14\n- 항목2\n- 항목3\n\n"
        "## 관련 주제\n"
    )
    assert count_topic_items(body) == 3


# ---------------------------------------------------------------------------
# parse_emerge_response: Claude 응답 방어적 파싱
# ---------------------------------------------------------------------------


def _raw_idea(**overrides) -> dict:
    defaults = dict(
        title="아이디어",
        insight="통찰",
        examples=["사례1"],
        next_steps=["액션1"],
        evidence=[{"topic": "design", "mention_count": 5}],
    )
    defaults.update(overrides)
    return defaults


def test_parse_emerge_response_plain_json_array():
    raw = json.dumps([_raw_idea()])
    ideas = parse_emerge_response(raw)
    assert ideas == [_raw_idea()]


def test_parse_emerge_response_strips_json_code_fence():
    raw = f"```json\n{json.dumps([_raw_idea()])}\n```"
    ideas = parse_emerge_response(raw)
    assert ideas[0]["title"] == "아이디어"


def test_parse_emerge_response_strips_plain_code_fence():
    raw = f"```\n{json.dumps([_raw_idea()])}\n```"
    ideas = parse_emerge_response(raw)
    assert ideas[0]["title"] == "아이디어"


def test_parse_emerge_response_finds_json_array_within_surrounding_text():
    raw = f"Here is the result:\n{json.dumps([_raw_idea()])}\nDone."
    ideas = parse_emerge_response(raw)
    assert ideas[0]["title"] == "아이디어"


def test_parse_emerge_response_malformed_json_raises_value_error():
    with pytest.raises(ValueError):
        parse_emerge_response("not json at all {{{")


def test_parse_emerge_response_non_list_raises_value_error():
    with pytest.raises(ValueError):
        parse_emerge_response(json.dumps({"title": "아이디어"}))


# ---------------------------------------------------------------------------
# run_emerge: 주제 노트 스캔 + fake emerge_fn 주입 (API 호출 없음)
# ---------------------------------------------------------------------------


def _seed_topics(tmp_path: Path, slugs: list[str]) -> None:
    for slug in slugs:
        _write_topic_note(tmp_path, slug, title=slug)


def test_run_emerge_returns_empty_when_topics_below_min(tmp_path: Path):
    _seed_topics(tmp_path, ["design", "feature"])  # 2 topics < default min 3

    def fake_emerge_should_not_be_called(
        prior_ideas: str, topics: dict[str, str]
    ) -> list[dict]:
        raise AssertionError("주제 수 부족 시 emerge_fn이 호출되면 안 됨")

    result = run_emerge(tmp_path, fake_emerge_should_not_be_called, date="2025-01-15")

    assert result == []


def test_run_emerge_calls_emerge_fn_when_topics_meet_min(tmp_path: Path):
    _seed_topics(tmp_path, ["design", "feature", "ai-features"])
    received: dict[str, str] = {}

    def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
        received.update(topics)
        return [_raw_idea()]

    result = run_emerge(tmp_path, fake_emerge, date="2025-01-15")

    assert set(received) == {"design", "feature", "ai-features"}
    assert len(result) == 1


def test_run_emerge_builds_emerged_idea_with_date_and_sequence(tmp_path: Path):
    _seed_topics(tmp_path, ["design", "feature", "ai-features"])

    def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
        return [_raw_idea(title="첫번째"), _raw_idea(title="두번째")]

    [first, second] = run_emerge(tmp_path, fake_emerge, date="2025-01-20")

    assert first.title == "첫번째"
    assert first.date == "2025-01-20"
    assert first.sequence == 1
    assert second.title == "두번째"
    assert second.date == "2025-01-20"
    assert second.sequence == 2


def test_run_emerge_maps_evidence_and_derives_tags(tmp_path: Path):
    _seed_topics(tmp_path, ["design", "feature", "ai-features"])

    def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
        return [
            _raw_idea(
                evidence=[
                    {"topic": "Design", "mention_count": 5},
                    {"topic": "AI Features", "mention_count": 3},
                ]
            )
        ]

    [idea] = run_emerge(tmp_path, fake_emerge, date="2025-01-15")

    assert idea.evidence == [
        Evidence(topic="design", mention_count=5),
        Evidence(topic="ai-features", mention_count=3),
    ]
    assert idea.tags == ["#design", "#ai-features"]


def test_run_emerge_defaults_missing_optional_fields(tmp_path: Path):
    _seed_topics(tmp_path, ["design", "feature", "ai-features"])

    def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
        return [{"title": "제목만 있음"}]

    [idea] = run_emerge(tmp_path, fake_emerge, date="2025-01-15")

    assert idea.title == "제목만 있음"
    assert idea.insight == ""
    assert idea.examples == []
    assert idea.next_steps == []
    assert idea.evidence == []
    assert idea.tags == []


def test_run_emerge_no_ideas_returns_empty_list(tmp_path: Path):
    _seed_topics(tmp_path, ["design", "feature", "ai-features"])

    def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
        return []

    assert run_emerge(tmp_path, fake_emerge, date="2025-01-15") == []


def test_run_emerge_respects_custom_min_topics(tmp_path: Path):
    _seed_topics(tmp_path, ["design", "feature"])

    def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
        return [_raw_idea()]

    result = run_emerge(tmp_path, fake_emerge, date="2025-01-15", min_topics=2)

    assert len(result) == 1


# ---------------------------------------------------------------------------
# load_emerger: anthropic lazy import (모듈 임포트 자체는 anthropic 불필요)
# ---------------------------------------------------------------------------


def test_emerge_module_importable_without_anthropic_installed():
    """anthropic 미설치 환경에서도 모듈 최상단 임포트는 성공해야 한다."""
    import thinktank.emerge as emerge_module

    assert hasattr(emerge_module, "load_emerger")


@pytest.mark.skipif(
    _ANTHROPIC_AVAILABLE,
    reason="anthropic 이 설치된 환경에서는 이 미설치 케이스를 검증할 수 없음",
)
def test_load_emerger_raises_when_anthropic_not_installed():
    with pytest.raises(ModuleNotFoundError):
        load_emerger(api_key="test-key")


@pytest.mark.skipif(
    not _ANTHROPIC_AVAILABLE,
    reason="anthropic 미설치 - 실 API 검증은 P5 E2E에서 수행",
)
def test_load_emerger_returns_callable_with_real_client():
    emerger = load_emerger(api_key="test-key")

    assert callable(emerger)


# ---------------------------------------------------------------------------
# load_cli_emerger: 로컬 claude CLI(구독 OAuth) 백엔드 (API 키 불필요)
# ---------------------------------------------------------------------------


def test_load_cli_emerger_produces_emerged_ideas_via_run_emerge(
    tmp_path: Path, monkeypatch
):
    """load_cli_emerger 로 만든 함수를 run_emerge 에 주입하면 EmergedIdea 를 만든다."""
    from thinktank.emerge import load_cli_emerger

    _seed_topics(tmp_path, ["design", "feature", "ai-features"])

    raw_response = json.dumps([_raw_idea()])

    def _fake_run_claude_cli(prompt: str, model=None, timeout=120.0) -> str:
        assert "design" in prompt
        return raw_response

    monkeypatch.setattr("thinktank.claude_cli.run_claude_cli", _fake_run_claude_cli)

    emerge_fn = load_cli_emerger()
    ideas = run_emerge(tmp_path, emerge_fn, date="2025-01-15")

    assert len(ideas) == 1
    idea = ideas[0]
    assert idea.title == "아이디어"
    assert idea.date == "2025-01-15"
    assert idea.sequence == 1
    assert idea.evidence == [Evidence(topic="design", mention_count=5)]


# ---------------------------------------------------------------------------
# 증분 창발: 마지막 창발 이후 신규 주제만 + 기존 아이디어 압축본
# ---------------------------------------------------------------------------


def _write_idea_note(
    vault_path: Path, date: str, seq: int, title: str, insight: str
) -> Path:
    ideas_dir = vault_path / "30-ideas"
    ideas_dir.mkdir(parents=True, exist_ok=True)
    content = (
        f"---\ntype: emerged_idea\ndate: {date}\n---\n"
        f"# {title}\n\n## 통찰\n{insight}\n\n[[some-topic]]\n\n"
        "## 구체적 사례\n- 사례\n"
    )
    path = ideas_dir / f"{date}_idea_{seq}.md"
    path.write_text(content, encoding="utf-8")
    return path


def test_last_emerge_date_returns_none_when_no_ideas(tmp_path: Path):
    assert last_emerge_date(tmp_path) is None


def test_last_emerge_date_returns_latest(tmp_path: Path):
    _write_idea_note(tmp_path, "2025-01-10", 1, "A", "통찰A")
    _write_idea_note(tmp_path, "2025-01-20", 1, "B", "통찰B")

    assert last_emerge_date(tmp_path) == "2025-01-20"


def test_filter_new_entries_returns_full_body_when_since_none():
    body = "# 제목\n\n## 2025-01-15\n- 항목1\n"
    assert filter_new_entries(body, None) == body


def test_filter_new_entries_keeps_only_sections_after_since():
    body = (
        "# 제목\n\n"
        "## 2025-01-14\n- 옛날 항목\n\n"
        "## 2025-01-16\n- 새 항목\n\n"
        "## 관련 주제\n- [[feature]]\n"
    )
    filtered = filter_new_entries(body, "2025-01-15")

    assert "새 항목" in filtered
    assert "옛날 항목" not in filtered
    assert "[[feature]]" not in filtered  # 관련 주제 섹션 제외
    assert "# 제목" in filtered


def test_scan_new_topic_entries_excludes_topics_without_new_entries(tmp_path: Path):
    _write_topic_note(tmp_path, "design", date="2025-01-16", items=["새 항목"])
    _write_topic_note(tmp_path, "stale", date="2025-01-10", items=["옛날 항목"])

    result = scan_new_topic_entries(tmp_path, "2025-01-15")

    assert set(result) == {"design"}
    assert "새 항목" in result["design"]


def test_scan_new_topic_entries_returns_all_when_since_none(tmp_path: Path):
    _write_topic_note(tmp_path, "a", date="2025-01-10")
    _write_topic_note(tmp_path, "b", date="2025-01-20")

    assert set(scan_new_topic_entries(tmp_path, None)) == {"a", "b"}


def test_scan_prior_ideas_extracts_title_and_insight(tmp_path: Path):
    _write_idea_note(tmp_path, "2025-01-10", 1, "아이디어 A", "A의 통찰")
    _write_idea_note(tmp_path, "2025-01-10", 2, "아이디어 B", "B의 통찰")

    text = scan_prior_ideas(tmp_path)

    assert "아이디어 A: A의 통찰" in text
    assert "아이디어 B: B의 통찰" in text


def test_scan_prior_ideas_empty_when_no_ideas(tmp_path: Path):
    assert scan_prior_ideas(tmp_path) == ""


def test_run_emerge_incremental_feeds_prior_ideas_and_only_new_topics(tmp_path: Path):
    # 마지막 창발 = 2025-01-15 (아이디어 노트 존재)
    _write_idea_note(tmp_path, "2025-01-15", 1, "기존 아이디어", "이전 통찰")
    # 신규(01-16) 3개 + 옛날(01-10)만 있는 stale 1개
    _write_topic_note(tmp_path, "design", date="2025-01-16", items=["새 항목"])
    _write_topic_note(tmp_path, "trading", date="2025-01-16", items=["매매 항목"])
    _write_topic_note(tmp_path, "sports", date="2025-01-16", items=["운동 항목"])
    _write_topic_note(tmp_path, "stale", date="2025-01-10", items=["옛날 항목"])

    captured: dict[str, object] = {}

    def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
        captured["prior"] = prior_ideas
        captured["topics"] = set(topics)
        return [_raw_idea()]

    result = run_emerge(tmp_path, fake_emerge, date="2025-01-16")

    # stale 은 신규 항목이 없어 제외, 신규 3개만 전달됨
    assert captured["topics"] == {"design", "trading", "sports"}
    # 기존 아이디어가 압축본으로 함께 전달됨
    assert "기존 아이디어: 이전 통찰" in captured["prior"]
    assert len(result) == 1
