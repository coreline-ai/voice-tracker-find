# @TASK P1-S0-T1 - 공통 노트 렌더러 테스트 (TDD RED)
# @SPEC docs/planning/06-tasks.md#P1-S0-T1
# @TEST tests/test_renderer.py
import pytest

from thinktank.notes.renderer import (
    archive_filename,
    daily_filename,
    emerged_idea_filename,
    normalize_filename,
    render_frontmatter,
    render_wikilink,
)


class TestNormalizeFilename:
    def test_lowercases_and_hyphenates_spaces(self):
        assert normalize_filename("AI Features") == "ai-features"

    def test_removes_special_characters(self):
        assert normalize_filename("My Topic Name!") == "my-topic-name"

    def test_converts_underscores_to_hyphens(self):
        assert normalize_filename("hello_world") == "hello-world"

    def test_collapses_repeated_separators(self):
        assert normalize_filename("a   b--c") == "a-b-c"

    def test_strips_leading_trailing_hyphens(self):
        assert normalize_filename("  -Design-  ") == "design"

    def test_preserves_korean_characters(self):
        assert normalize_filename("디자인 이야기") == "디자인-이야기"


class TestRenderWikilink:
    def test_wraps_topic_in_double_brackets(self):
        assert render_wikilink("design") == "[[design]]"

    def test_preserves_original_case(self):
        assert render_wikilink("Design") == "[[Design]]"


class TestRenderFrontmatter:
    def test_minimal_required_fields(self):
        result = render_frontmatter({"type": "daily", "date": "2025-01-15"})
        assert result == "---\ntype: daily\ndate: 2025-01-15\n---\n"

    def test_all_optional_fields(self):
        note_data = {
            "type": "topic",
            "date": "2025-01-15",
            "tags": ["#design", "#ui"],
            "sources": ["file_1.m4a (2025-01-15)", "file_3.m4a (2025-01-14)"],
            "related": ["feature", "product"],
        }
        result = render_frontmatter(note_data)
        expected = (
            "---\n"
            "type: topic\n"
            "date: 2025-01-15\n"
            "tags: [#design, #ui]\n"
            "sources: [file_1.m4a (2025-01-15), file_3.m4a (2025-01-14)]\n"
            "related: [[feature]], [[product]]\n"
            "---\n"
        )
        assert result == expected

    def test_missing_type_raises(self):
        with pytest.raises(ValueError):
            render_frontmatter({"date": "2025-01-15"})

    def test_missing_date_raises(self):
        with pytest.raises(ValueError):
            render_frontmatter({"type": "daily"})

    def test_invalid_type_raises(self):
        with pytest.raises(ValueError):
            render_frontmatter({"type": "invalid", "date": "2025-01-15"})

    def test_invalid_date_format_raises(self):
        with pytest.raises(ValueError):
            render_frontmatter({"type": "daily", "date": "2025/01/15"})

    @pytest.mark.parametrize(
        "note_type", ["daily", "topic", "emerged_idea", "archive", "log"]
    )
    def test_accepts_all_note_types(self, note_type):
        result = render_frontmatter({"type": note_type, "date": "2025-01-15"})
        assert f"type: {note_type}" in result


class TestFilenameGenerators:
    def test_daily_filename(self):
        assert daily_filename("2025-01-15") == "2025-01-15.md"

    def test_emerged_idea_filename(self):
        assert emerged_idea_filename("2025-01-15", 1) == "2025-01-15_idea_1.md"

    def test_archive_filename_strips_extension(self):
        assert archive_filename("file_1.m4a", "2025-01-15") == "file_1_2025-01-15.md"

    def test_archive_filename_without_extension(self):
        assert archive_filename("design", "2025-01-15") == "design_2025-01-15.md"

    def test_daily_filename_invalid_date_raises(self):
        with pytest.raises(ValueError):
            daily_filename("15-01-2025")
