# @TASK P3-R2-T1 - 주제 노트 병합 (TDD RED)
# @SPEC docs/planning/06-tasks.md#P3-R2-T1
# @TEST tests/test_topics.py
from __future__ import annotations

from pathlib import Path

from thinktank.extract import ExtractedItem
from thinktank.topics import Topic, TopicEntry, merge_topics


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


class TestMergeTopicsCreatesNewNote:
    def test_creates_note_file_for_new_topic(self, tmp_path: Path):
        items = [_item("새로운 UI는 대시보드식", ["design"])]
        merge_topics(items, tmp_path, "2025-01-15")
        assert (tmp_path / "20-notes" / "design.md").exists()

    def test_uses_slug_for_filename(self, tmp_path: Path):
        items = [_item("아이디어", ["AI Features"])]
        merge_topics(items, tmp_path, "2025-01-15")
        assert (tmp_path / "20-notes" / "ai-features.md").exists()

    def test_frontmatter_type_and_date(self, tmp_path: Path):
        items = [_item("아이디어", ["design"])]
        merge_topics(items, tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "type: topic" in content
        assert "date: 2025-01-15" in content

    def test_includes_title_heading_with_original_name(self, tmp_path: Path):
        items = [_item("아이디어", ["Design"])]
        merge_topics(items, tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "# Design" in content

    def test_includes_date_section_and_item(self, tmp_path: Path):
        items = [_item("새로운 아이디어", ["design"])]
        merge_topics(items, tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "## 2025-01-15" in content
        assert "- 새로운 아이디어" in content

    def test_includes_related_topics_heading_even_if_empty(self, tmp_path: Path):
        items = [_item("아이디어", ["design"])]
        merge_topics(items, tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "## 관련 주제" in content

    def test_creates_20_notes_folder_if_missing(self, tmp_path: Path):
        items = [_item("아이디어", ["design"])]
        merge_topics(items, tmp_path, "2025-01-15")
        assert (tmp_path / "20-notes").is_dir()


class TestMergeTopicsGrouping:
    def test_item_with_multiple_topics_creates_multiple_notes(self, tmp_path: Path):
        items = [_item("아이디어", ["design", "feature"])]
        merge_topics(items, tmp_path, "2025-01-15")
        assert (tmp_path / "20-notes" / "design.md").exists()
        assert (tmp_path / "20-notes" / "feature.md").exists()

    def test_item_without_topics_is_skipped(self, tmp_path: Path):
        items = [_item("주제 없음", [])]
        result = merge_topics(items, tmp_path, "2025-01-15")
        assert result == []
        assert not (tmp_path / "20-notes").exists()


class TestMergeTopicsReoccurrence:
    def test_new_date_section_inserted_above_existing(self, tmp_path: Path):
        merge_topics([_item("첫째날 아이디어", ["design"])], tmp_path, "2025-01-14")
        merge_topics([_item("둘째날 아이디어", ["design"])], tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert content.index("## 2025-01-15") < content.index("## 2025-01-14")
        assert "- 첫째날 아이디어" in content
        assert "- 둘째날 아이디어" in content

    def test_frontmatter_date_updates_to_latest(self, tmp_path: Path):
        merge_topics([_item("아이디어1", ["design"])], tmp_path, "2025-01-14")
        merge_topics([_item("아이디어2", ["design"])], tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "date: 2025-01-15" in content

    def test_merges_into_existing_file_without_duplicating(self, tmp_path: Path):
        merge_topics([_item("아이디어1", ["design"])], tmp_path, "2025-01-14")
        merge_topics([_item("아이디어2", ["design"])], tmp_path, "2025-01-15")
        matches = list((tmp_path / "20-notes").glob("design*.md"))
        assert len(matches) == 1


class TestMergeTopicsIdempotency:
    def test_same_date_rerun_does_not_duplicate_items(self, tmp_path: Path):
        items = [_item("반복 아이디어", ["design"])]
        merge_topics(items, tmp_path, "2025-01-15")
        merge_topics(items, tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert content.count("- 반복 아이디어") == 1

    def test_same_date_new_distinct_item_is_appended(self, tmp_path: Path):
        merge_topics([_item("아이디어A", ["design"])], tmp_path, "2025-01-15")
        merge_topics([_item("아이디어B", ["design"])], tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert content.count("## 2025-01-15") == 1
        assert "- 아이디어A" in content
        assert "- 아이디어B" in content


class TestMergeTopicsRelated:
    def test_co_topics_added_as_related(self, tmp_path: Path):
        items = [_item("아이디어", ["design", "feature", "research"])]
        merge_topics(items, tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "[[feature]]" in content
        assert "[[research]]" in content

    def test_related_is_bidirectional(self, tmp_path: Path):
        items = [_item("아이디어", ["design", "feature"])]
        merge_topics(items, tmp_path, "2025-01-15")
        feature_content = (tmp_path / "20-notes" / "feature.md").read_text(
            encoding="utf-8"
        )
        assert "[[design]]" in feature_content

    def test_related_dedup_and_keeps_existing(self, tmp_path: Path):
        item1 = _item("아이디어1", ["design", "feature"])
        item2 = _item("아이디어2", ["design", "feature"])
        merge_topics([item1], tmp_path, "2025-01-14")
        merge_topics([item2], tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        # frontmatter의 related 한 줄과 본문 "## 관련 주제" 목록에 각각 한 번씩
        # 등장하되, 본문 목록에서는 중복 없이 한 번만 등장해야 한다.
        body = content.split("---", 2)[-1]
        assert body.count("[[feature]]") == 1

    def test_related_accumulates_new_topics_over_time(self, tmp_path: Path):
        item1 = _item("아이디어1", ["design", "feature"])
        item2 = _item("아이디어2", ["design", "research"])
        merge_topics([item1], tmp_path, "2025-01-14")
        merge_topics([item2], tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "[[feature]]" in content
        assert "[[research]]" in content


class TestMergeTopicsReturnValue:
    def test_returns_topic_objects(self, tmp_path: Path):
        items = [_item("아이디어", ["design"])]
        result = merge_topics(items, tmp_path, "2025-01-15")
        assert len(result) == 1
        assert isinstance(result[0], Topic)
        assert result[0].name == "design"
        assert result[0].date == "2025-01-15"

    def test_returned_entries_include_items(self, tmp_path: Path):
        items = [_item("아이디어", ["design"])]
        result = merge_topics(items, tmp_path, "2025-01-15")
        assert result[0].entries[0] == TopicEntry(date="2025-01-15", items=["아이디어"])

    def test_returned_related_lists_co_topics(self, tmp_path: Path):
        items = [_item("아이디어", ["design", "feature"])]
        result = merge_topics(items, tmp_path, "2025-01-15")
        design_topic = next(t for t in result if t.name == "design")
        assert design_topic.related == ["feature"]

    def test_does_not_touch_other_vault_folders(self, tmp_path: Path):
        merge_topics([_item("아이디어", ["design"])], tmp_path, "2025-01-15")
        assert list(tmp_path.iterdir()) == [tmp_path / "20-notes"]


class TestMergeTopicsTags:
    def test_aggregates_item_tags_into_frontmatter(self, tmp_path: Path):
        items = [_item("아이디어", ["design"], tags=["#ui", "#ux"])]
        merge_topics(items, tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "tags: [#ui, #ux]" in content

    def test_tags_dedup_across_items(self, tmp_path: Path):
        items = [
            _item("아이디어1", ["design"], tags=["#ui"]),
            _item("아이디어2", ["design"], tags=["#ui", "#ux"]),
        ]
        merge_topics(items, tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "tags: [#ui, #ux]" in content

    def test_tags_merge_and_dedup_across_runs(self, tmp_path: Path):
        merge_topics(
            [_item("아이디어1", ["design"], tags=["#ui"])], tmp_path, "2025-01-14"
        )
        merge_topics(
            [_item("아이디어2", ["design"], tags=["#ui", "#ux"])],
            tmp_path,
            "2025-01-15",
        )
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "tags: [#ui, #ux]" in content

    def test_tags_persist_when_new_batch_has_no_tags(self, tmp_path: Path):
        merge_topics(
            [_item("아이디어1", ["design"], tags=["#ui"])], tmp_path, "2025-01-14"
        )
        merge_topics([_item("아이디어2", ["design"], tags=[])], tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "tags: [#ui]" in content

    def test_returned_topic_includes_tags(self, tmp_path: Path):
        items = [_item("아이디어", ["design"], tags=["#ui"])]
        result = merge_topics(items, tmp_path, "2025-01-15")
        assert result[0].tags == ["#ui"]

    def test_tags_default_empty_when_no_tags(self, tmp_path: Path):
        items = [_item("아이디어", ["design"])]
        result = merge_topics(items, tmp_path, "2025-01-15")
        assert result[0].tags == []
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "tags:" not in content


class TestMergeTopicsSources:
    def test_sources_param_reflected_in_frontmatter(self, tmp_path: Path):
        items = [_item("아이디어", ["design"])]
        merge_topics(items, tmp_path, "2025-01-15", sources=["file_1.m4a (2025-01-15)"])
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "sources: [file_1.m4a (2025-01-15)]" in content

    def test_sources_merge_and_dedup_across_runs(self, tmp_path: Path):
        merge_topics(
            [_item("아이디어1", ["design"])],
            tmp_path,
            "2025-01-14",
            sources=["file_1.m4a (2025-01-14)"],
        )
        merge_topics(
            [_item("아이디어2", ["design"])],
            tmp_path,
            "2025-01-15",
            sources=["file_1.m4a (2025-01-14)", "file_2.m4a (2025-01-15)"],
        )
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert content.count("file_1.m4a (2025-01-14)") == 1
        assert "file_2.m4a (2025-01-15)" in content

    def test_sources_persist_when_not_passed_again(self, tmp_path: Path):
        merge_topics(
            [_item("아이디어1", ["design"])],
            tmp_path,
            "2025-01-14",
            sources=["file_1.m4a (2025-01-14)"],
        )
        merge_topics([_item("아이디어2", ["design"])], tmp_path, "2025-01-15")
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "file_1.m4a (2025-01-14)" in content

    def test_returned_topic_includes_sources(self, tmp_path: Path):
        items = [_item("아이디어", ["design"])]
        result = merge_topics(
            items, tmp_path, "2025-01-15", sources=["file_1.m4a (2025-01-15)"]
        )
        assert result[0].sources == ["file_1.m4a (2025-01-15)"]

    def test_sources_default_empty_when_not_provided(self, tmp_path: Path):
        items = [_item("아이디어", ["design"])]
        result = merge_topics(items, tmp_path, "2025-01-15")
        assert result[0].sources == []
        content = (tmp_path / "20-notes" / "design.md").read_text(encoding="utf-8")
        assert "sources:" not in content
