# @TASK P2-R1-T2 - STT 전사 (faster-whisper large-v3, CUDA)
# @SPEC docs/planning/06-tasks.md#P2-R1-T2
# @TEST tests/test_transcribe.py
from __future__ import annotations

from pathlib import Path

import pytest

from thinktank.db import (
    Recording,
    Status,
    get_recordings,
    get_retry_targets,
    init_db,
    insert_recording,
    update_recording_status,
)
from thinktank.notes.archive import Segment, Transcript
from thinktank.transcribe import (
    RawSegment,
    format_timestamp,
    render_transcript_text,
    run_transcribe_batch,
    save_transcript_text,
    transcribe_audio,
)


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


def _make_vad_done_recording(
    db_path: Path,
    filename: str = "a.m4a",
    recorded_at: str = "2025-01-15 09:00:00",
) -> Recording:
    """vad_done 상태의 레코딩을 만든다 (오디오는 fake로 대체하므로 실제 파일 불필요)."""
    recording = insert_recording(
        db_path,
        source_path=str(Path("/audio") / filename),
        filename=filename,
        recorded_at=recorded_at,
        file_size=1234567,
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


# ---------------------------------------------------------------------------
# format_timestamp: 초(float) -> "MM:SS" 문자열 변환 (순수 함수)
# ---------------------------------------------------------------------------


def test_format_timestamp_zero():
    assert format_timestamp(0.0) == "00:00"


def test_format_timestamp_seconds_only():
    assert format_timestamp(15.0) == "00:15"


def test_format_timestamp_minutes_and_seconds():
    assert format_timestamp(75.0) == "01:15"


def test_format_timestamp_truncates_subseconds():
    assert format_timestamp(15.9) == "00:15"


def test_format_timestamp_over_an_hour_keeps_mm_ss_no_hour_wrap():
    assert format_timestamp(3661.0) == "61:01"


# ---------------------------------------------------------------------------
# render_transcript_text / save_transcript_text: temp_dir에 저장할 전사 텍스트
# ---------------------------------------------------------------------------


def _sample_transcript(**overrides) -> Transcript:
    defaults: dict = dict(
        source_file="a.m4a",
        date="2025-01-15",
        recorded_at="2025-01-15 09:00:00",
        file_size=1234567,
        segments=[
            Segment(start="00:00", end="00:15", text="안녕하세요"),
            Segment(start="00:15", end="00:32", text="다음 안건입니다"),
        ],
    )
    defaults.update(overrides)
    return Transcript(**defaults)


def test_render_transcript_text_includes_timestamp_and_quoted_text():
    text = render_transcript_text(_sample_transcript())
    assert '[00:00-00:15] "안녕하세요"' in text
    assert '[00:15-00:32] "다음 안건입니다"' in text


def test_render_transcript_text_empty_segments_is_empty_string():
    assert render_transcript_text(_sample_transcript(segments=[])) == ""


def test_save_transcript_text_writes_file_to_temp_dir(temp_dir):
    transcript = _sample_transcript()
    path = save_transcript_text(transcript, temp_dir)

    assert path == temp_dir / "a.txt"
    assert path.read_text(encoding="utf-8") == render_transcript_text(transcript)


def test_save_transcript_text_creates_temp_dir_if_missing(tmp_path):
    missing_dir = tmp_path / "not_yet_created"
    save_transcript_text(_sample_transcript(), missing_dir)

    assert missing_dir.is_dir()


# ---------------------------------------------------------------------------
# transcribe_audio: 단일 레코딩 처리 (fake transcribe_fn 주입, faster-whisper 불필요)
# ---------------------------------------------------------------------------


def test_transcribe_audio_returns_transcript_and_transitions_to_transcribed(
    db_path, temp_dir
):
    recording = _make_vad_done_recording(db_path)
    audio_path = temp_dir / "a.m4a"

    def fake_transcribe(path: Path) -> list[RawSegment]:
        return [
            RawSegment(start=0.0, end=15.0, text="안녕하세요"),
            RawSegment(start=15.0, end=32.5, text="다음 안건입니다"),
        ]

    transcript = transcribe_audio(
        recording, audio_path, db_path, temp_dir, fake_transcribe
    )

    assert transcript.source_file == "a.m4a"
    assert transcript.date == "2025-01-15"
    assert transcript.recorded_at == "2025-01-15 09:00:00"
    assert transcript.file_size == 1234567
    assert transcript.segments == [
        Segment(start="00:00", end="00:15", text="안녕하세요"),
        Segment(start="00:15", end="00:32", text="다음 안건입니다"),
    ]

    [updated] = get_recordings(db_path, Status.TRANSCRIBED)
    assert updated.id == recording.id
    assert updated.transcribed_at is not None

    assert (temp_dir / "a.txt").exists()


def test_transcribe_audio_marks_transcribed_failed_on_error(db_path, temp_dir):
    recording = _make_vad_done_recording(db_path)
    audio_path = temp_dir / "a.m4a"

    def fake_transcribe_fails(path: Path) -> list[RawSegment]:
        raise RuntimeError("CUDA out of memory")

    with pytest.raises(RuntimeError):
        transcribe_audio(
            recording, audio_path, db_path, temp_dir, fake_transcribe_fails
        )

    [updated] = get_recordings(db_path, Status.TRANSCRIBED_FAILED)
    assert updated.id == recording.id
    assert updated.error_msg == "CUDA out of memory"


def test_transcribe_audio_retry_succeeds_from_transcribed_failed(db_path, temp_dir):
    recording = _make_vad_done_recording(db_path)
    audio_path = temp_dir / "a.m4a"

    def fake_transcribe_fails(path: Path) -> list[RawSegment]:
        raise RuntimeError("timeout")

    with pytest.raises(RuntimeError):
        transcribe_audio(
            recording, audio_path, db_path, temp_dir, fake_transcribe_fails
        )

    [failed] = get_retry_targets(db_path)
    assert failed.status == Status.TRANSCRIBED_FAILED

    def fake_transcribe_ok(path: Path) -> list[RawSegment]:
        return [RawSegment(start=0.0, end=1.0, text="ok")]

    transcript = transcribe_audio(
        failed, audio_path, db_path, temp_dir, fake_transcribe_ok
    )

    assert transcript.segments == [Segment(start="00:00", end="00:01", text="ok")]
    [updated] = get_recordings(db_path, Status.TRANSCRIBED)
    assert updated.id == recording.id


# ---------------------------------------------------------------------------
# run_transcribe_batch: vad_done + transcribed_failed 전체를 처리하는 배치
# ---------------------------------------------------------------------------


def test_run_transcribe_batch_transitions_all_vad_done_to_transcribed(
    db_path, temp_dir
):
    r1 = _make_vad_done_recording(db_path, "a.m4a")
    r2 = _make_vad_done_recording(db_path, "b.m4a")

    def fake_transcribe(path: Path) -> list[RawSegment]:
        return [RawSegment(start=0.0, end=1.0, text="text")]

    processed = run_transcribe_batch(db_path, temp_dir, fake_transcribe)

    assert {r.id for r in processed} == {r1.id, r2.id}
    assert all(r.status == Status.TRANSCRIBED for r in processed)
    assert get_recordings(db_path, Status.VAD_DONE) == []


def test_run_transcribe_batch_keeps_failed_recording_for_retry(db_path, temp_dir):
    ok = _make_vad_done_recording(db_path, "ok.m4a")
    broken = _make_vad_done_recording(db_path, "broken.m4a")

    def fake_transcribe(path: Path) -> list[RawSegment]:
        if path.name == "broken.m4a":
            raise RuntimeError("decode error")
        return [RawSegment(start=0.0, end=1.0, text="text")]

    processed = run_transcribe_batch(db_path, temp_dir, fake_transcribe)

    assert {r.id for r in processed} == {ok.id}
    assert [r.id for r in get_recordings(db_path, Status.TRANSCRIBED_FAILED)] == [
        broken.id
    ]
    assert [r.id for r in get_recordings(db_path, Status.TRANSCRIBED)] == [ok.id]


def test_run_transcribe_batch_retries_previously_failed_recordings(db_path, temp_dir):
    recording = _make_vad_done_recording(db_path, "flaky.m4a")
    call_count = {"n": 0}

    def fake_transcribe_first_fails(path: Path) -> list[RawSegment]:
        call_count["n"] += 1
        if call_count["n"] == 1:
            raise RuntimeError("temporary failure")
        return [RawSegment(start=0.0, end=1.0, text="ok")]

    first = run_transcribe_batch(db_path, temp_dir, fake_transcribe_first_fails)
    assert first == []
    assert [r.id for r in get_recordings(db_path, Status.TRANSCRIBED_FAILED)] == [
        recording.id
    ]

    second = run_transcribe_batch(db_path, temp_dir, fake_transcribe_first_fails)
    assert [r.id for r in second] == [recording.id]
    assert get_recordings(db_path, Status.TRANSCRIBED_FAILED) == []


def test_run_transcribe_batch_no_candidates_returns_empty(db_path, temp_dir):
    def fake_transcribe(path: Path) -> list[RawSegment]:
        return [RawSegment(start=0.0, end=1.0, text="text")]

    assert run_transcribe_batch(db_path, temp_dir, fake_transcribe) == []


def test_run_transcribe_batch_is_idempotent_on_rerun(db_path, temp_dir):
    """한 번 전사된 레코딩은 다음 배치 재실행에서 다시 처리되지 않는다."""
    _make_vad_done_recording(db_path, "a.m4a")

    def fake_transcribe(path: Path) -> list[RawSegment]:
        return [RawSegment(start=0.0, end=1.0, text="text")]

    first = run_transcribe_batch(db_path, temp_dir, fake_transcribe)
    second = run_transcribe_batch(db_path, temp_dir, fake_transcribe)

    assert len(first) == 1
    assert second == []


# ---------------------------------------------------------------------------
# 무거운 의존성(faster-whisper/CUDA) 통합 테스트: 라이브러리 부재 시 skip.
# 실 모델 검증은 P5 E2E 테스트에서 수행한다.
# ---------------------------------------------------------------------------

try:
    import faster_whisper  # noqa: F401

    _WHISPER_AVAILABLE = True
except ImportError:
    _WHISPER_AVAILABLE = False


@pytest.mark.skipif(
    not _WHISPER_AVAILABLE,
    reason="faster-whisper 미설치 - 실 모델 검증은 P5 E2E에서 수행",
)
def test_load_transcriber_returns_callable_with_real_model():
    from thinktank.transcribe import load_transcriber

    transcriber = load_transcriber()

    assert callable(transcriber)
