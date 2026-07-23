# @TASK P2-S4-T1 - 전사 아카이브 노트 생성 테스트 (TDD RED)
# @SPEC docs/planning/06-tasks.md#P2-S4-T1
# @TEST tests/test_archive_note.py
from pathlib import Path

from thinktank.notes.archive import (
    Segment,
    Transcript,
    render_archive_note,
    write_archive_note,
)


def _sample_transcript(**overrides) -> Transcript:
    defaults: dict = dict(
        source_file="file_1.m4a",
        date="2025-01-15",
        recorded_at="2025-01-15 09:00:00",
        file_size=1234567,
        segments=[
            Segment(start="00:00", end="00:15", text="안녕하세요 회의를 시작합니다."),
            Segment(start="00:15", end="00:32", text="다음 안건은 디자인 리뷰입니다."),
        ],
    )
    defaults.update(overrides)
    return Transcript(**defaults)


class TestRenderArchiveNote:
    def test_includes_frontmatter_required_fields(self):
        result = render_archive_note(_sample_transcript())
        assert "type: archive" in result
        assert "date: 2025-01-15" in result
        assert "source_file: file_1.m4a" in result
        assert "recorded_at: 2025-01-15 09:00:00" in result
        assert "file_size: 1234567" in result

    def test_frontmatter_wrapped_in_dashes(self):
        result = render_archive_note(_sample_transcript())
        assert result.count("---") == 2

    def test_includes_heading(self):
        result = render_archive_note(_sample_transcript())
        assert "# 전사 원본" in result

    def test_heading_appears_after_frontmatter(self):
        result = render_archive_note(_sample_transcript())
        second_dash_idx = result.index("---", result.index("---") + 1)
        assert second_dash_idx < result.index("# 전사 원본")

    def test_includes_segment_blocks(self):
        result = render_archive_note(_sample_transcript())
        assert "[00:00-00:15]\n안녕하세요 회의를 시작합니다." in result
        assert "[00:15-00:32]\n다음 안건은 디자인 리뷰입니다." in result

    def test_segment_order_preserved(self):
        result = render_archive_note(_sample_transcript())
        first_idx = result.index("[00:00-00:15]")
        second_idx = result.index("[00:15-00:32]")
        assert first_idx < second_idx

    def test_empty_segments_produces_no_segment_blocks(self):
        result = render_archive_note(_sample_transcript(segments=[]))
        assert "# 전사 원본" in result
        assert "[00:00" not in result


class TestWriteArchiveNote:
    def test_creates_file_in_archive_folder(self, tmp_path: Path):
        path = write_archive_note(_sample_transcript(), tmp_path)
        assert path == tmp_path / "90-archive" / "file_1_2025-01-15.md"
        assert path.exists()

    def test_creates_archive_folder_if_missing(self, tmp_path: Path):
        vault = tmp_path / "vault"
        write_archive_note(_sample_transcript(), vault)
        assert (vault / "90-archive").is_dir()

    def test_written_content_matches_render(self, tmp_path: Path):
        transcript = _sample_transcript()
        path = write_archive_note(transcript, tmp_path)
        assert path.read_text(encoding="utf-8") == render_archive_note(transcript)

    def test_filename_uses_stem_without_extension(self, tmp_path: Path):
        transcript = _sample_transcript(
            source_file="recording_morning.m4a", date="2025-02-01"
        )
        path = write_archive_note(transcript, tmp_path)
        assert path.name == "recording_morning_2025-02-01.md"

    def test_does_not_touch_other_vault_folders(self, tmp_path: Path):
        write_archive_note(_sample_transcript(), tmp_path)
        assert list(tmp_path.iterdir()) == [tmp_path / "90-archive"]
