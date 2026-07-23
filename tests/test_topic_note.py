# @TASK P3-S2-T1 - 주제 노트 렌더링 테스트 (TDD RED)
# @SPEC docs/planning/06-tasks.md#P3-S2-T1
# @TEST tests/test_topic_note.py
from __future__ import annotations

from thinktank.notes.topic import TopicEntry, render_topic_note


def _entries(*pairs: tuple[str, list[str]]) -> list[TopicEntry]:
    return [TopicEntry(date=date, items=items) for date, items in pairs]


class TestRenderTopicNoteFrontmatter:
    def test_type_and_date_from_latest_entry(self):
        content = render_topic_note(
            "design", _entries(("2025-01-15", ["아이디어"])), []
        )
        assert "type: topic" in content
        assert "date: 2025-01-15" in content

    def test_date_uses_first_entry_when_multiple(self):
        content = render_topic_note(
            "design",
            _entries(("2025-01-16", ["둘째"]), ("2025-01-15", ["첫째"])),
            [],
        )
        assert "date: 2025-01-16" in content

    def test_tags_rendered_in_frontmatter(self):
        content = render_topic_note(
            "design", _entries(("2025-01-15", ["아이디어"])), [], tags=["#ui", "#ux"]
        )
        assert "tags: [#ui, #ux]" in content

    def test_tags_omitted_when_none(self):
        content = render_topic_note(
            "design", _entries(("2025-01-15", ["아이디어"])), []
        )
        assert "tags:" not in content

    def test_tags_omitted_when_empty_list(self):
        content = render_topic_note(
            "design", _entries(("2025-01-15", ["아이디어"])), [], tags=[]
        )
        assert "tags:" not in content

    def test_sources_rendered_in_frontmatter(self):
        content = render_topic_note(
            "design",
            _entries(("2025-01-15", ["아이디어"])),
            [],
            sources=["file_1.m4a (2025-01-15)", "file_2.m4a (2025-01-16)"],
        )
        assert "sources: [file_1.m4a (2025-01-15), file_2.m4a (2025-01-16)]" in content

    def test_sources_omitted_when_none(self):
        content = render_topic_note(
            "design", _entries(("2025-01-15", ["아이디어"])), []
        )
        assert "sources:" not in content

    def test_related_rendered_in_frontmatter(self):
        content = render_topic_note(
            "design", _entries(("2025-01-15", ["아이디어"])), ["feature", "research"]
        )
        assert "related: [[feature]], [[research]]" in content

    def test_related_omitted_from_frontmatter_when_empty(self):
        content = render_topic_note(
            "design", _entries(("2025-01-15", ["아이디어"])), []
        )
        _, _, frontmatter_body = content.partition("---\n")
        frontmatter_block = frontmatter_body.split("---", 1)[0]
        assert "related:" not in frontmatter_block


class TestRenderTopicNoteBody:
    def test_title_heading_uses_original_name(self):
        content = render_topic_note(
            "Design Pattern", _entries(("2025-01-15", ["아이디어"])), []
        )
        assert "# Design Pattern" in content

    def test_date_section_and_items(self):
        content = render_topic_note(
            "design", _entries(("2025-01-15", ["첫 아이디어", "둘째 아이디어"])), []
        )
        assert "## 2025-01-15" in content
        assert "- 첫 아이디어" in content
        assert "- 둘째 아이디어" in content

    def test_multiple_date_sections_preserve_order(self):
        content = render_topic_note(
            "design",
            _entries(("2025-01-16", ["둘째날"]), ("2025-01-15", ["첫째날"])),
            [],
        )
        assert content.index("## 2025-01-16") < content.index("## 2025-01-15")

    def test_related_topics_heading_always_present(self):
        content = render_topic_note(
            "design", _entries(("2025-01-15", ["아이디어"])), []
        )
        assert "## 관련 주제" in content

    def test_related_topics_rendered_as_wikilinks(self):
        content = render_topic_note(
            "design", _entries(("2025-01-15", ["아이디어"])), ["feature", "research"]
        )
        assert "- [[feature]]" in content
        assert "- [[research]]" in content


class TestTopicEntry:
    def test_is_frozen_dataclass_with_date_and_items(self):
        entry = TopicEntry(date="2025-01-15", items=["아이디어"])
        assert entry.date == "2025-01-15"
        assert entry.items == ["아이디어"]
