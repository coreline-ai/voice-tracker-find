# @TASK P3-S2-V - 주제 노트 연결점 검증 (통합 관점)
# @SPEC docs/planning/06-tasks.md#P3-S2-V
# @TEST tests/e2e/test_topic_verification.py
"""topics.merge_topics / notes.topic.render_topic_note 의 연결점을 검증한다.

각 모듈의 단위 동작은 이미 tests/test_topics.py, tests/test_topic_note.py 에서
검증됐다. 이 파일은 specs/screens/topic-note.yaml 의 data_requirements(6필드)와
tests 시나리오 5개, 멱등성, 파일명 slug 규칙, 그리고 데일리 노트(notes/daily.py)와의
위키링크 연결 정합성을 통합 관점에서 검증한다. LLM 호출 없이 ExtractedItem을
직접 구성하고 tmp_path 볼트를 사용한다.
"""

from __future__ import annotations

import re
from dataclasses import fields as dataclass_fields
from pathlib import Path

import pytest

from thinktank.extract import ExtractedItem
from thinktank.notes.daily import write_daily_note
from thinktank.notes.renderer import normalize_filename
from thinktank.notes.topic import TopicEntry
from thinktank.topics import Topic, merge_topics


def _item(text: str, topics: list[str], **overrides) -> ExtractedItem:
    defaults: dict = dict(
        category="idea",
        text=text,
        topics=topics,
        tags=[],
        event_at=None,
    )
    defaults.update(overrides)
    return ExtractedItem(**defaults)


# ---------------------------------------------------------------------------
# 검증 항목 1: Field Coverage - topics.[name, date, tags, sources, entries,
# related] 6개 필드가 모두 Topic 반환값과 렌더링된 노트(frontmatter+본문)에
# 반영되는가.
# ---------------------------------------------------------------------------


class TestFieldCoverage:
    def test_topic_dataclass_has_exactly_the_six_spec_fields(self):
        field_names = {f.name for f in dataclass_fields(Topic)}
        assert field_names == {
            "name",
            "date",
            "tags",
            "sources",
            "entries",
            "related",
        }

    def test_all_six_fields_reflected_in_returned_topic_and_rendered_note(
        self, tmp_path: Path
    ):
        items = [_item("새 아이디어", ["design", "feature"], tags=["#ui", "#ux"])]
        result = merge_topics(
            items, tmp_path, "2025-01-15", sources=["file_1.m4a (2025-01-15)"]
        )
        design = next(t for t in result if t.name == "design")

        # Topic 반환값
        assert design.name == "design"
        assert design.date == "2025-01-15"
        assert design.tags == ["#ui", "#ux"]
        assert design.sources == ["file_1.m4a (2025-01-15)"]
        assert design.entries == [TopicEntry(date="2025-01-15", items=["새 아이디어"])]
        assert design.related == ["feature"]

        # 렌더링된 노트 (frontmatter + 본문)
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "# design" in content  # name
        assert "date: 2025-01-15" in content  # date
        assert "tags: [#ui, #ux]" in content  # tags
        assert "sources: [file_1.m4a (2025-01-15)]" in content  # sources
        assert "## 2025-01-15" in content and "- 새 아이디어" in content  # entries
        assert "[[feature]]" in content  # related


# ---------------------------------------------------------------------------
# 검증 항목 2: specs/screens/topic-note.yaml tests 시나리오 5개.
# ---------------------------------------------------------------------------


class TestScreenSpecScenarios:
    def test_scenario_1_first_appearance_creates_note(self, tmp_path: Path):
        """시나리오: 주제 노트 생성 (첫 등장)."""
        items = [_item("새로운 아이디어", ["design"])]
        merge_topics(items, tmp_path, "2025-01-15")

        note_path = tmp_path / "20-notes" / "design.md"
        assert note_path.exists()
        content = note_path.read_text(encoding="utf-8")
        assert "# design" in content
        assert "## 2025-01-15" in content
        assert "- 새로운 아이디어" in content

    def test_scenario_2_reoccurrence_accumulates_new_date_on_top(
        self, tmp_path: Path
    ):
        """시나리오: 주제 노트 누적 (재등장, 최상단 삽입 + 기존 유지 + 역순)."""
        merge_topics([_item("첫날 아이디어", ["design"])], tmp_path, "2025-01-15")
        merge_topics([_item("둘째날 아이디어", ["design"])], tmp_path, "2025-01-16")

        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert content.index("## 2025-01-16") < content.index("## 2025-01-15")
        assert "- 첫날 아이디어" in content
        assert "- 둘째날 아이디어" in content

    def test_scenario_3_related_topics_auto_linked_and_deduped(
        self, tmp_path: Path
    ):
        """시나리오: 관련 주제 자동 링크 + 중복 제거."""
        items = [_item("아이디어", ["design", "feature", "research"])]
        merge_topics(items, tmp_path, "2025-01-15")
        # 같은 배치를 재실행해도 관련 주제 링크가 중복 추가되지 않아야 한다.
        merge_topics(items, tmp_path, "2025-01-15")

        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        body = content.split("---", 2)[-1]
        assert "[[feature]]" in body
        assert "[[research]]" in body
        assert body.count("[[feature]]") == 1
        assert body.count("[[research]]") == 1

    def test_scenario_4_backlink_navigation_is_bidirectional(self, tmp_path: Path):
        """시나리오: 사용자 주제 탐색 (백링크) - design <-> feature 상호 링크."""
        items = [_item("아이디어", ["design", "feature"])]
        merge_topics(items, tmp_path, "2025-01-15")

        design_content = (tmp_path / "20-notes" / "design.md").read_text(
            encoding="utf-8"
        )
        feature_content = (tmp_path / "20-notes" / "feature.md").read_text(
            encoding="utf-8"
        )
        assert "[[feature]]" in design_content
        assert "[[design]]" in feature_content

    def test_scenario_5_tags_and_sources_meta_displayed(self, tmp_path: Path):
        """시나리오: 태그 및 소스 메타 표시."""
        items = [_item("아이디어", ["design"], tags=["#ui", "#ux"])]
        merge_topics(
            items,
            tmp_path,
            "2025-01-15",
            sources=["file_1.m4a (2025-01-15)", "file_2.m4a (2025-01-16)"],
        )

        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "tags: [#ui, #ux]" in content
        assert (
            "sources: [file_1.m4a (2025-01-15), file_2.m4a (2025-01-16)]" in content
        )


# ---------------------------------------------------------------------------
# 검증 항목 3: 멱등성 - 같은 날짜로 재실행해도 중복 불릿 없음.
# ---------------------------------------------------------------------------


class TestIdempotency:
    def test_same_date_rerun_does_not_duplicate_bullets(self, tmp_path: Path):
        items = [_item("반복되는 아이디어", ["design"])]
        merge_topics(items, tmp_path, "2025-01-15")
        merge_topics(items, tmp_path, "2025-01-15")

        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert content.count("- 반복되는 아이디어") == 1
        assert content.count("## 2025-01-15") == 1

    def test_same_date_rerun_does_not_duplicate_tags_or_sources(
        self, tmp_path: Path
    ):
        items = [_item("아이디어", ["design", "feature"], tags=["#ui"])]
        merge_topics(items, tmp_path, "2025-01-15", sources=["file_1.m4a"])
        merge_topics(items, tmp_path, "2025-01-15", sources=["file_1.m4a"])

        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        body = content.split("---", 2)[-1]
        assert body.count("[[feature]]") == 1
        assert content.count("tags: [#ui]") == 1
        assert content.count("file_1.m4a") == 1


# ---------------------------------------------------------------------------
# 검증 항목 4: 파일명 규칙 - 소문자+하이픈 slug (specs/shared/types.yaml).
# ---------------------------------------------------------------------------


class TestFilenameSlugConvention:
    @pytest.mark.parametrize(
        "topic_name, expected_slug",
        [
            ("design", "design"),
            ("AI Features", "ai-features"),
            ("Design Pattern", "design-pattern"),
            ("User_Research", "user-research"),
        ],
    )
    def test_filename_uses_lowercase_hyphen_slug(
        self, tmp_path: Path, topic_name: str, expected_slug: str
    ):
        assert normalize_filename(topic_name) == expected_slug
        merge_topics([_item("아이디어", [topic_name])], tmp_path, "2025-01-15")
        assert (tmp_path / "20-notes" / f"{expected_slug}.md").exists()

    def test_slug_has_no_uppercase_or_underscore(self, tmp_path: Path):
        merge_topics(
            [_item("아이디어", ["User Research Topic"])], tmp_path, "2025-01-15"
        )
        [note_path] = list((tmp_path / "20-notes").iterdir())
        assert note_path.name == "user-research-topic.md"
        assert not any(c.isupper() for c in note_path.name)
        assert "_" not in note_path.name


# ---------------------------------------------------------------------------
# 검증 항목 5: 데일리 노트와의 링크 정합 - 데일리 노트가 렌더링하는
# [[주제]] 위키링크 텍스트가 topics.merge_topics 가 만드는 노트 파일명(slug)과
# Obsidian 링크 해석 관점에서 실제로 연결되는가.
#
# Obsidian은 [[링크텍스트]]를 대소문자만 무시하는 파일명 exact 매칭으로 해석한다
# (공백<->하이픈 자동 치환은 없다). merge_topics()는 파일명을
# normalize_filename()으로 슬러그화(공백/언더스코어->하이픈, 소문자화)하지만
# render_daily_note()의 _render_item_line()(src/thinktank/notes/daily.py:46-61)은
# item.topics 원본 문자열을 그대로 render_wikilink()에 넘긴다. 주제명에 공백이나
# 대문자가 섞이면 데일리 노트의 위키링크는 topics 노트 파일과 문자열이 달라져
# Obsidian에서 연결되지 않는다(broken link).
# ---------------------------------------------------------------------------


class TestDailyNoteLinkConsistency:
    def _extract_wikilink_text(self, daily_content: str) -> str:
        """색인에서 주제 링크를 하나 뽑는다.

        '## 구분'의 구분 노트 링크([[YYYY-MM-DD_아이디어]])는 주제 노트가
        아니므로 건너뛴다.
        """
        for target in re.findall(r"\[\[(.+?)\]\]", daily_content):
            if not re.match(r"^\d{4}-\d{2}-\d{2}_", target):
                return target
        raise AssertionError("데일리 노트에 주제 위키링크가 없습니다.")

    def test_daily_note_wikilink_matches_topic_note_filename_slug(
        self, tmp_path: Path
    ):
        """결함 재현: 영문 다중 단어 주제명."""
        topic_name = "Design Pattern"
        item = _item("새로운 아이디어", [topic_name])

        write_daily_note(
            "2025-01-15",
            [item],
            {"success_count": 1, "fail_count": 0},
            ["file_1.m4a"],
            tmp_path,
        )
        merge_topics([item], tmp_path, "2025-01-15", sources=["file_1.m4a"])

        daily_content = (tmp_path / "10-daily" / "2025-01-15.md").read_text(
            encoding="utf-8"
        )
        link_text = self._extract_wikilink_text(daily_content)

        slug = normalize_filename(topic_name)
        topic_note_path = tmp_path / "20-notes" / f"{slug}.md"
        assert topic_note_path.exists(), "merge_topics 가 주제 노트를 생성해야 한다."

        # Obsidian은 [[link_text]]를 대소문자만 무시하고 파일명과 exact 매칭한다.
        assert link_text.lower() == topic_note_path.stem.lower(), (
            f"결함: 데일리 노트의 위키링크 텍스트 '[[{link_text}]]' 가 topics 노트 "
            f"파일명 '{topic_note_path.name}' 과 일치하지 않아 Obsidian에서 "
            "연결되지 않는 깨진 링크(broken link)가 된다. "
            "원인: notes/daily.py의 _render_item_line()이 item.topics 원본 "
            "문자열을 slug화 없이 render_wikilink()에 그대로 전달함"
            "(topics.py의 merge_topics()는 normalize_filename()으로 슬러그화함)."
        )

    def test_daily_note_wikilink_with_korean_multiword_topic_also_mismatches(
        self, tmp_path: Path
    ):
        """결함 재현: 한글 다중 단어 주제명.

        대소문자 문제가 없어도 공백<->하이픈 불일치는 남는다.
        """
        topic_name = "사용자 리서치"
        item = _item("고객 인터뷰 정리", [topic_name])

        write_daily_note(
            "2025-01-15",
            [item],
            {"success_count": 1, "fail_count": 0},
            ["file_1.m4a"],
            tmp_path,
        )
        merge_topics([item], tmp_path, "2025-01-15", sources=["file_1.m4a"])

        daily_content = (tmp_path / "10-daily" / "2025-01-15.md").read_text(
            encoding="utf-8"
        )
        link_text = self._extract_wikilink_text(daily_content)
        topic_note_path = tmp_path / "20-notes" / f"{normalize_filename(topic_name)}.md"

        assert link_text == topic_note_path.stem, (
            f"결함: 위키링크 텍스트 '[[{link_text}]]'(공백 포함)가 실제 파일명 슬러그 "
            f"'{topic_note_path.stem}'(하이픈)와 문자 그대로 달라 Obsidian에서 "
            "링크가 끊어진다."
        )
