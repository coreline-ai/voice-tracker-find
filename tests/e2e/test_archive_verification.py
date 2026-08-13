# @TASK P2-S4-V - 아카이브 노트 연결점 검증 (통합 관점)
# @SPEC docs/planning/06-tasks.md#P2-S4-V
# @TEST tests/e2e/test_archive_verification.py
"""전사 파이프라인 -> 아카이브 노트 -> 7일 폐기 대상 조회로 이어지는 연결점을 검증한다.

이미 구현된 각 모듈(transcribe.py/notes/archive.py/db.py)의 단위 동작은 각자의
단위 테스트(tests/test_transcribe.py, tests/test_archive_note.py, tests/test_db.py)
에서 검증됐다. 이 파일은 모듈 간 계약이 어댑터 없이 그대로 맞물리는지(통합 관점)를
검증한다. 실제 오디오/faster-whisper 모델은 사용하지 않고 fake transcribe_fn 과
tmp_path 로 대체한다. 근거: specs/screens/archive.yaml.
"""

from __future__ import annotations

import re
import sqlite3
from pathlib import Path

import pytest

from airvoice.db import (
    Recording,
    Status,
    get_cleanup_targets,
    get_recordings,
    init_db,
    insert_recording,
    update_recording_status,
)
from airvoice.notes.archive import (
    Segment,
    Transcript,
    render_archive_note,
    write_archive_note,
)
from airvoice.transcribe import RawSegment, transcribe_audio


@pytest.fixture
def db_path(tmp_path: Path) -> Path:
    """초기화된 임시 DB 파일 경로."""
    path = tmp_path / "pipeline.db"
    init_db(path)
    return path


@pytest.fixture
def temp_dir(tmp_path: Path) -> Path:
    """VAD 출력 오디오 및 전사 텍스트를 저장할 임시 폴더."""
    path = tmp_path / "temp"
    path.mkdir()
    return path


@pytest.fixture
def vault_path(tmp_path: Path) -> Path:
    """Obsidian 볼트 루트로 사용할 임시 폴더."""
    path = tmp_path / "vault"
    path.mkdir()
    return path


def _make_vad_done_recording(
    db_path: Path,
    filename: str = "file_1.m4a",
    recorded_at: str = "2025-01-15 09:00:00",
    file_size: int = 1234567,
) -> Recording:
    """vad_done 상태의 레코딩을 만든다 (오디오는 fake로 대체하므로 실제 파일 불필요)."""
    recording = insert_recording(
        db_path,
        source_path=str(Path("/audio") / filename),
        filename=filename,
        recorded_at=recorded_at,
        file_size=file_size,
    )
    update_recording_status(
        db_path, recording.id, Status.SYNCED, "2025-01-15 09:00:01"
    )
    update_recording_status(
        db_path, recording.id, Status.VAD_DONE, "2025-01-15 09:05:00"
    )
    return next(
        r for r in get_recordings(db_path, Status.VAD_DONE) if r.id == recording.id
    )


def _organize_recording(db_path: Path, recording_id: int) -> None:
    """transcribed -> extracted -> organized 전이를 통합 시뮬레이션한다.

    추출(extract) 단계는 Phase 3 구현 대상이므로, 이 검증에서는 상태 전이도가
    허용하는 경로만 통해 organized 까지 진행시킨다.
    """
    update_recording_status(
        db_path, recording_id, Status.TRANSCRIBED, "2025-01-15 09:10:00"
    )
    update_recording_status(
        db_path, recording_id, Status.EXTRACTED, "2025-01-15 09:20:00"
    )
    update_recording_status(
        db_path, recording_id, Status.ORGANIZED, "2025-01-15 09:30:00"
    )


def _set_organized_at(db_path: Path, recording_id: int, sqlite_modifier: str) -> None:
    """organized_at 값을 테스트 목적으로 임의 시점으로 되돌린다."""
    conn = sqlite3.connect(db_path)
    try:
        conn.execute(
            "UPDATE recordings SET organized_at = datetime('now', ?) WHERE id = ?",
            (sqlite_modifier, recording_id),
        )
        conn.commit()
    finally:
        conn.close()


def _fake_transcribe(path: Path) -> list[RawSegment]:
    return [
        RawSegment(start=0.0, end=15.0, text="안녕하세요 회의를 시작합니다."),
        RawSegment(start=15.0, end=32.0, text="다음 안건은 디자인 리뷰입니다."),
    ]


# ---------------------------------------------------------------------------
# 검증 항목 1: Field Coverage - transcripts 5필드가 생성된 노트에 모두 반영되는가.
# ---------------------------------------------------------------------------


class TestFieldCoverage:
    def test_all_five_fields_present_in_rendered_note(self, db_path, temp_dir):
        recording = _make_vad_done_recording(db_path)
        audio_path = temp_dir / "file_1.m4a"

        transcript = transcribe_audio(
            recording, audio_path, db_path, temp_dir, _fake_transcribe
        )
        note_text = render_archive_note(transcript)

        # frontmatter: source_file, date, recorded_at, file_size
        assert f"source_file: {transcript.source_file}" in note_text
        assert f"date: {transcript.date}" in note_text
        assert f"recorded_at: {transcript.recorded_at}" in note_text
        assert f"file_size: {transcript.file_size}" in note_text
        # 본문: segments
        assert transcript.segments, "fake transcribe_fn 은 세그먼트를 반환해야 한다."
        for segment in transcript.segments:
            assert f"[{segment.start}-{segment.end}]" in note_text
            assert segment.text in note_text


# ---------------------------------------------------------------------------
# 검증 항목 2: 계약 일치 - transcribe_audio 출력이 어댑터 없이
# write_archive_note 입력으로 바로 연결되는가 (파이프라인 통합 흐름).
# ---------------------------------------------------------------------------


class TestPipelineContract:
    def test_transcribe_audio_output_feeds_directly_into_write_archive_note(
        self, db_path, temp_dir, vault_path
    ):
        recording = _make_vad_done_recording(db_path)
        audio_path = temp_dir / "file_1.m4a"

        transcript = transcribe_audio(
            recording, audio_path, db_path, temp_dir, _fake_transcribe
        )

        # 변환/어댑터 없이 transcribe_audio 반환값을 그대로 전달한다.
        note_path = write_archive_note(transcript, vault_path)

        assert isinstance(transcript, Transcript)
        assert all(isinstance(segment, Segment) for segment in transcript.segments)
        assert note_path.exists()
        assert note_path.read_text(encoding="utf-8") == render_archive_note(transcript)


# ---------------------------------------------------------------------------
# 검증 항목 3: 생성 조건 - 전사 완료 직후 노트 생성 가능,
# 파일명 {filename}_{YYYY-MM-DD}.md 규칙.
# ---------------------------------------------------------------------------


class TestCreationCondition:
    def test_note_created_immediately_after_transcription(
        self, db_path, temp_dir, vault_path
    ):
        recording = _make_vad_done_recording(db_path, filename="file_1.m4a")
        audio_path = temp_dir / "file_1.m4a"

        transcript = transcribe_audio(
            recording, audio_path, db_path, temp_dir, _fake_transcribe
        )
        [updated] = get_recordings(db_path, Status.TRANSCRIBED)
        assert updated.transcribed_at is not None

        note_path = write_archive_note(transcript, vault_path)
        assert note_path.parent.name == "90-archive"
        assert note_path.name == "file_1_2025-01-15.md"

    def test_filename_matches_filename_underscore_date_pattern(
        self, db_path, temp_dir, vault_path
    ):
        recording = _make_vad_done_recording(
            db_path, filename="recording_morning.m4a"
        )
        audio_path = temp_dir / "recording_morning.m4a"

        transcript = transcribe_audio(
            recording, audio_path, db_path, temp_dir, _fake_transcribe
        )
        note_path = write_archive_note(transcript, vault_path)

        assert re.fullmatch(r".+_\d{4}-\d{2}-\d{2}\.md", note_path.name)
        assert note_path.name == "recording_morning_2025-01-15.md"


# ---------------------------------------------------------------------------
# 검증 항목 4: 7일 삭제 연동 - organized 상태 + organized_at 7일 경과 레코드가
# get_cleanup_targets 로 회수되는가.
# ---------------------------------------------------------------------------


class TestCleanupIntegration:
    def test_organized_recording_past_7_days_is_cleanup_target(self, db_path):
        recording = _make_vad_done_recording(db_path)
        _organize_recording(db_path, recording.id)
        _set_organized_at(db_path, recording.id, "-8 days")

        targets = get_cleanup_targets(db_path, days=7)
        assert [t.id for t in targets] == [recording.id]

    def test_organized_recording_within_7_days_is_not_cleanup_target(self, db_path):
        recording = _make_vad_done_recording(db_path)
        _organize_recording(db_path, recording.id)
        _set_organized_at(db_path, recording.id, "-1 days")

        targets = get_cleanup_targets(db_path, days=7)
        assert targets == []


# ---------------------------------------------------------------------------
# 검증 항목 5: specs/screens/archive.yaml 의 tests 시나리오 3개 반영.
# ---------------------------------------------------------------------------


class TestArchiveYamlScenarios:
    def test_scenario_transcribe_then_archive_note_created(
        self, db_path, temp_dir, vault_path
    ):
        """시나리오 1: 전사 완료 후 노트 생성."""
        recording = _make_vad_done_recording(db_path)
        audio_path = temp_dir / "file_1.m4a"

        transcript = transcribe_audio(
            recording, audio_path, db_path, temp_dir, _fake_transcribe
        )
        note_path = write_archive_note(transcript, vault_path)

        assert note_path == vault_path / "90-archive" / "file_1_2025-01-15.md"
        assert note_path.exists()

        content = note_path.read_text(encoding="utf-8")
        assert "source_file: file_1.m4a" in content
        assert "date: 2025-01-15" in content
        assert "recorded_at: 2025-01-15 09:00:00" in content
        assert "file_size: 1234567" in content
        assert re.search(r"\[\d{2}:\d{2}-\d{2}:\d{2}\]", content)

    def test_scenario_error_verification_via_timestamp_lookup(
        self, db_path, temp_dir, vault_path
    ):
        """시나리오 2: 데일리 노트의 이상 항목 발견 후 90-archive 원문에서 해당
        시간대를 조회해 LLM 분류 오류를 검증할 수 있다."""
        recording = _make_vad_done_recording(db_path)
        audio_path = temp_dir / "file_1.m4a"

        transcript = transcribe_audio(
            recording, audio_path, db_path, temp_dir, _fake_transcribe
        )
        note_path = write_archive_note(transcript, vault_path)

        content = note_path.read_text(encoding="utf-8")
        assert "[00:15-00:32]\n다음 안건은 디자인 리뷰입니다." in content

    def test_scenario_seven_day_auto_delete_target_after_organized(self, db_path):
        """시나리오 3: cleanup.py 가 organized_at + 7일 경과 파일을 폐기 대상으로
        찾아낸다 (실제 파일 삭제는 cleanup.py 담당, 여기서는 대상 조회만 검증)."""
        recording = _make_vad_done_recording(db_path)
        _organize_recording(db_path, recording.id)
        _set_organized_at(db_path, recording.id, "-8 days")

        targets = get_cleanup_targets(db_path, days=7)
        assert [t.id for t in targets] == [recording.id]
