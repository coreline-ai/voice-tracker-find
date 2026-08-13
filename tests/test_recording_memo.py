from pathlib import Path

from airvoice.extract import ExtractedItem
from airvoice.notes.archive import Segment, Transcript
from airvoice.notes.recording_memo import render_recording_memo, write_recording_memo


def _transcript() -> Transcript:
    return Transcript(
        source_file="rec_20250115_090000_meeting.m4a",
        date="2025-01-15",
        recorded_at="2025-01-15 09:00:00",
        file_size=123,
        segments=[
            Segment(start="00:00", end="00:05", text="회의를 시작합니다."),
            Segment(start="00:05", end="00:10", text="다음 안건은 디자인입니다."),
        ],
    )


def test_empty_extraction_is_an_honest_recording_memo() -> None:
    content = render_recording_memo(_transcript(), [])

    assert "type: recording_memo" in content
    assert "# 음성 메모" in content
    assert "회의를 시작합니다." in content
    assert "자동 추출 항목 없음" in content
    assert "[[rec_20250115_090000_meeting_2025-01-15|원문 전사 보기]]" in content


def test_recording_memo_includes_extracted_items() -> None:
    content = render_recording_memo(
        _transcript(),
        [
            ExtractedItem(
                category="idea",
                text="디자인 리뷰를 준비한다.",
                topics=["디자인"],
                tags=[],
                event_at=None,
            )
        ],
    )

    assert "디자인 리뷰를 준비한다." in content
    assert "자동 추출 항목 없음" not in content


def test_recording_memo_reuses_a_deterministic_path(tmp_path: Path) -> None:
    first = write_recording_memo(_transcript(), [], tmp_path)
    first.write_text("사용자 수정 메모\n", encoding="utf-8")
    second = write_recording_memo(_transcript(), [], tmp_path)

    assert first == second
    assert (
        first
        == tmp_path / "30-ideas" / "rec_20250115_090000_meeting_2025-01-15_memo.md"
    )
    assert len(list((tmp_path / "30-ideas").glob("*.md"))) == 1
    assert second.read_text(encoding="utf-8") == "사용자 수정 메모\n"


def test_recording_memo_can_be_explicitly_refreshed(tmp_path: Path) -> None:
    path = write_recording_memo(_transcript(), [], tmp_path)
    path.write_text("이전 표시\n", encoding="utf-8")

    write_recording_memo(_transcript(), [], tmp_path, overwrite=True)

    assert "원문 전사 보기" in path.read_text(encoding="utf-8")
