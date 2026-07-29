# @TASK P2-R1-T1 - VAD 처리 (Silero VAD 무음 제거)
# @SPEC docs/planning/06-tasks.md#P2-R1-T1
# @TEST tests/test_vad.py
from __future__ import annotations

from pathlib import Path

import pytest

from thinktank.db import (
    Recording,
    Status,
    get_recordings,
    init_db,
    insert_recording,
    update_recording_status,
)
from thinktank.vad import (
    AUDIBLE_FALLBACK_MIN_RMS,
    VAD_CHUNK_SECONDS,
    VadSegment,
    fallback_segments_for_audible_audio,
    iter_time_chunks,
    process_vad,
    run_vad_batch,
)


@pytest.fixture
def db_path(tmp_path: Path) -> Path:
    """초기화된 임시 DB 파일 경로."""
    path = tmp_path / "pipeline.db"
    init_db(path)
    return path


@pytest.fixture
def temp_dir(tmp_path: Path) -> Path:
    """정제된 오디오를 저장할 임시 폴더."""
    path = tmp_path / "temp"
    path.mkdir()
    return path


def _make_synced_recording(db_path: Path, filename: str = "a.m4a") -> Recording:
    """synced 상태의 레코딩을 만든다.

    오디오 처리는 fake 함수로 대체하므로 실제 파일은 필요 없다.
    """
    recording = insert_recording(
        db_path, source_path=str(Path("/audio") / filename), filename=filename
    )
    return _update_and_fetch(db_path, recording.id)


def _update_and_fetch(db_path: Path, recording_id: int) -> Recording:
    update_recording_status(db_path, recording_id, Status.SYNCED, "2025-07-05 00:00:00")
    return next(
        r for r in get_recordings(db_path, Status.SYNCED) if r.id == recording_id
    )


# ---------------------------------------------------------------------------
# iter_time_chunks: 긴 녹음(하루 3~8시간)을 청크 단위로 나누는 순수 함수
# ---------------------------------------------------------------------------


def test_iter_time_chunks_splits_into_fixed_size_chunks():
    chunks = iter_time_chunks(25.0, chunk_seconds=10.0)

    assert chunks == [(0.0, 10.0), (10.0, 20.0), (20.0, 25.0)]


def test_iter_time_chunks_single_chunk_when_shorter_than_chunk_size():
    chunks = iter_time_chunks(5.0, chunk_seconds=10.0)

    assert chunks == [(0.0, 5.0)]


def test_iter_time_chunks_exact_multiple_has_no_remainder_chunk():
    chunks = iter_time_chunks(20.0, chunk_seconds=10.0)

    assert chunks == [(0.0, 10.0), (10.0, 20.0)]


def test_iter_time_chunks_zero_or_negative_duration_returns_empty():
    assert iter_time_chunks(0.0, chunk_seconds=10.0) == []
    assert iter_time_chunks(-1.0, chunk_seconds=10.0) == []


def test_vad_chunk_seconds_default_is_positive():
    assert VAD_CHUNK_SECONDS > 0


def test_audible_fallback_preserves_full_recording_when_vad_misses_speech():
    assert fallback_segments_for_audible_audio(114.496, AUDIBLE_FALLBACK_MIN_RMS) == [
        VadSegment(start=0.0, end=114.496)
    ]


def test_audible_fallback_does_not_preserve_silent_or_empty_audio():
    assert fallback_segments_for_audible_audio(30.0, AUDIBLE_FALLBACK_MIN_RMS - 0.0001) == []
    assert fallback_segments_for_audible_audio(0.0, 1.0) == []


# ---------------------------------------------------------------------------
# process_vad: 단일 레코딩 처리 (fake detect_speech/cut_audio 주입, torch 불필요)
# ---------------------------------------------------------------------------


def test_process_vad_returns_segments_and_transitions_to_vad_done(db_path, temp_dir):
    recording = _make_synced_recording(db_path)
    segments = [VadSegment(start=1.0, end=3.5)]
    cut_calls: list[tuple[Path, Path, list[VadSegment]]] = []

    def fake_detect(audio_path: Path) -> list[VadSegment]:
        return segments

    def fake_cut(audio_path: Path, output_path: Path, segs: list[VadSegment]) -> None:
        cut_calls.append((audio_path, output_path, segs))

    result = process_vad(
        recording.id,
        recording.source_path,
        db_path,
        temp_dir,
        fake_detect,
        fake_cut,
    )

    expected_output = temp_dir / Path(recording.source_path).name
    assert result == segments
    assert cut_calls == [(Path(recording.source_path), expected_output, segments)]

    [updated] = get_recordings(db_path, Status.VAD_DONE)
    assert updated.id == recording.id
    assert updated.vad_at is not None


def test_process_vad_transitions_to_no_speech_when_no_speech_detected(
    db_path, temp_dir
):
    recording = _make_synced_recording(db_path)

    def fake_detect_empty(audio_path: Path) -> list[VadSegment]:
        return []

    def fake_cut_should_not_be_called(*args, **kwargs):
        raise AssertionError("발화 구간이 없으면 cut_audio 는 호출되면 안 된다")

    result = process_vad(
        recording.id,
        recording.source_path,
        db_path,
        temp_dir,
        fake_detect_empty,
        fake_cut_should_not_be_called,
    )

    # 무음은 오류가 아니라 no_speech(종료)로 전이 — 예외 없이 빈 목록을 반환한다.
    assert result == []
    assert get_recordings(db_path, Status.SYNCED) == []
    [updated] = get_recordings(db_path, Status.NO_SPEECH)
    assert updated.id == recording.id
    assert updated.vad_at is not None


def test_process_vad_propagates_cut_audio_failure_and_keeps_synced(db_path, temp_dir):
    recording = _make_synced_recording(db_path)

    def fake_detect(audio_path: Path) -> list[VadSegment]:
        return [VadSegment(start=0.0, end=1.0)]

    def fake_cut_fails(audio_path: Path, output_path: Path, segs) -> None:
        raise OSError("disk full")

    with pytest.raises(OSError):
        process_vad(
            recording.id,
            recording.source_path,
            db_path,
            temp_dir,
            fake_detect,
            fake_cut_fails,
        )

    assert [r.id for r in get_recordings(db_path, Status.SYNCED)] == [recording.id]


# ---------------------------------------------------------------------------
# run_vad_batch: synced 전체를 처리하는 배치 오케스트레이션
# ---------------------------------------------------------------------------


def test_run_vad_batch_transitions_all_synced_to_vad_done(db_path, temp_dir):
    r1 = _make_synced_recording(db_path, "a.m4a")
    r2 = _make_synced_recording(db_path, "b.m4a")

    def fake_detect(audio_path: Path) -> list[VadSegment]:
        return [VadSegment(start=0.0, end=1.0)]

    def fake_cut(audio_path: Path, output_path: Path, segs: list[VadSegment]) -> None:
        pass

    processed = run_vad_batch(db_path, temp_dir, fake_detect, fake_cut)

    assert {r.id for r in processed} == {r1.id, r2.id}
    assert all(r.status == Status.VAD_DONE for r in processed)
    assert get_recordings(db_path, Status.SYNCED) == []


def test_run_vad_batch_keeps_failed_recording_synced_for_retry(db_path, temp_dir):
    ok = _make_synced_recording(db_path, "ok.m4a")
    broken = _make_synced_recording(db_path, "broken.m4a")

    def fake_detect(audio_path: Path) -> list[VadSegment]:
        if audio_path.name == "broken.m4a":
            raise RuntimeError("corrupt audio")
        return [VadSegment(start=0.0, end=1.0)]

    def fake_cut(audio_path: Path, output_path: Path, segs: list[VadSegment]) -> None:
        pass

    processed = run_vad_batch(db_path, temp_dir, fake_detect, fake_cut)

    assert {r.id for r in processed} == {ok.id}
    assert [r.id for r in get_recordings(db_path, Status.SYNCED)] == [broken.id]
    assert [r.id for r in get_recordings(db_path, Status.VAD_DONE)] == [ok.id]


def test_run_vad_batch_transitions_no_speech_to_terminal_not_retried(db_path, temp_dir):
    _make_synced_recording(db_path, "silent.m4a")

    def fake_detect_empty(audio_path: Path) -> list[VadSegment]:
        return []

    def fake_cut(*args, **kwargs) -> None:
        pass

    first = run_vad_batch(db_path, temp_dir, fake_detect_empty, fake_cut)
    second = run_vad_batch(db_path, temp_dir, fake_detect_empty, fake_cut)

    # 무음은 vad_done 에 안 들어가고 no_speech(종료)라 재시도되지 않는다.
    assert first == []
    assert second == []
    assert get_recordings(db_path, Status.SYNCED) == []
    assert len(get_recordings(db_path, Status.NO_SPEECH)) == 1


def test_run_vad_batch_no_synced_recordings_returns_empty(db_path, temp_dir):
    def fake_detect(audio_path: Path) -> list[VadSegment]:
        return [VadSegment(start=0.0, end=1.0)]

    assert run_vad_batch(db_path, temp_dir, fake_detect) == []


def test_run_vad_batch_is_idempotent_on_rerun(db_path, temp_dir):
    """한 번 처리된 레코딩은 다음 배치 재실행에서 다시 처리되지 않는다."""
    _make_synced_recording(db_path, "a.m4a")

    def fake_detect(audio_path: Path) -> list[VadSegment]:
        return [VadSegment(start=0.0, end=1.0)]

    def fake_cut(audio_path: Path, output_path: Path, segs: list[VadSegment]) -> None:
        pass

    first = run_vad_batch(db_path, temp_dir, fake_detect, fake_cut)
    second = run_vad_batch(db_path, temp_dir, fake_detect, fake_cut)

    assert len(first) == 1
    assert second == []


# ---------------------------------------------------------------------------
# 무거운 의존성(torch/silero-vad/pydub) 통합 테스트: 라이브러리 부재 시 skip.
# 실 모델 검증은 P5 E2E 테스트에서 수행한다.
# ---------------------------------------------------------------------------

try:
    import pydub  # noqa: F401
    import silero_vad  # noqa: F401
    import torch  # noqa: F401

    _AUDIO_DEPS_AVAILABLE = True
except ImportError:
    _AUDIO_DEPS_AVAILABLE = False


@pytest.mark.skipif(
    not _AUDIO_DEPS_AVAILABLE,
    reason="torch/silero-vad/pydub 미설치 - 실 모델 검증은 P5 E2E에서 수행",
)
def test_load_speech_detector_returns_callable_with_real_model():
    from thinktank.vad import load_speech_detector

    detector = load_speech_detector()

    assert callable(detector)
