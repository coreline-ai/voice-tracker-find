# @TASK P1-R1-T2 - 수집 스캐너 (Syncthing 수신 폴더 스캔 + 등록 + synced 전이)
# @SPEC docs/planning/06-tasks.md#P1-R1-T2
# @TEST tests/test_ingest.py
from __future__ import annotations

import os
from pathlib import Path

import pytest

from thinktank.db import Recording, Status, get_recordings, init_db
from thinktank.ingest import (
    register_recording,
    scan_ingest_folder,
    sync_ingest_folder,
)


@pytest.fixture
def db_path(tmp_path: Path) -> Path:
    """초기화된 임시 DB 파일 경로."""
    path = tmp_path / "pipeline.db"
    init_db(path)
    return path


@pytest.fixture
def ingest_dir(tmp_path: Path) -> Path:
    """임시 수집 폴더."""
    path = tmp_path / "inbox"
    path.mkdir()
    return path


def _make_file(directory: Path, name: str, content: bytes = b"data") -> Path:
    file_path = directory / name
    file_path.write_bytes(content)
    return file_path


def test_scan_ingest_folder_finds_supported_audio_files(ingest_dir: Path):
    """지원 형식(.m4a/.wav/.mp3/.ogg) 파일만 찾는다."""
    _make_file(ingest_dir, "a.m4a")
    _make_file(ingest_dir, "b.wav")
    _make_file(ingest_dir, "c.mp3")
    _make_file(ingest_dir, "d.ogg")
    _make_file(ingest_dir, "e.txt")

    found = scan_ingest_folder(ingest_dir)

    assert sorted(p.name for p in found) == ["a.m4a", "b.wav", "c.mp3", "d.ogg"]


def test_scan_ingest_folder_excludes_syncthing_temp_files(ingest_dir: Path):
    """Syncthing 임시 파일(.syncthing.*.tmp, ~syncthing~*)은 제외한다."""
    _make_file(ingest_dir, "real.m4a")
    _make_file(ingest_dir, ".syncthing.real2.m4a.tmp")
    _make_file(ingest_dir, "~syncthing~real3.m4a")

    found = scan_ingest_folder(ingest_dir)

    assert [p.name for p in found] == ["real.m4a"]


def test_scan_ingest_folder_missing_dir_returns_empty(tmp_path: Path):
    """존재하지 않는 폴더는 빈 목록을 반환한다."""
    missing = tmp_path / "does-not-exist"

    assert scan_ingest_folder(missing) == []


def test_register_recording_creates_pending_with_metadata(
    db_path: Path, ingest_dir: Path
):
    """신규 파일은 pending 상태로 등록되고 file_size 가 채워진다."""
    file_path = _make_file(ingest_dir, "note.m4a", content=b"1234567890")

    recording = register_recording(db_path, file_path)

    assert isinstance(recording, Recording)
    assert recording.status == Status.PENDING
    assert recording.source_path == str(file_path)
    assert recording.filename == "note.m4a"
    assert recording.file_size == 10
    assert recording.recorded_at is not None


def test_register_recording_duplicate_returns_none(db_path: Path, ingest_dir: Path):
    """이미 등록된 source_path 는 다시 등록하지 않고 None 을 반환한다 (멱등)."""
    file_path = _make_file(ingest_dir, "note.m4a")

    first = register_recording(db_path, file_path)
    second = register_recording(db_path, file_path)

    assert first is not None
    assert second is None
    assert len(get_recordings(db_path, Status.PENDING)) == 1


def test_register_recording_parses_recorded_at_from_filename(
    db_path: Path, ingest_dir: Path
):
    """파일명에 타임스탬프 패턴이 있으면 이를 recorded_at 으로 사용한다."""
    file_path = _make_file(ingest_dir, "REC_20250705_143022.m4a")

    recording = register_recording(db_path, file_path)

    assert recording is not None
    assert recording.recorded_at == "2025-07-05 14:30:22"


def test_register_recording_falls_back_to_mtime(db_path: Path, ingest_dir: Path):
    """파일명에서 타임스탬프를 못 찾으면 mtime 을 사용한다."""
    file_path = _make_file(ingest_dir, "recording.m4a")
    mtime = 1_700_000_000
    os.utime(file_path, (mtime, mtime))

    recording = register_recording(db_path, file_path)

    assert recording is not None
    assert recording.recorded_at == "2023-11-14 22:13:20"


def test_sync_ingest_folder_registers_and_syncs_new_files(
    db_path: Path, ingest_dir: Path
):
    """새 파일을 등록하고 즉시 synced 상태로 전이한다."""
    _make_file(ingest_dir, "a.m4a")
    _make_file(ingest_dir, "b.wav")

    synced = sync_ingest_folder(ingest_dir, db_path)

    assert {r.filename for r in synced} == {"a.m4a", "b.wav"}
    assert all(r.status == Status.SYNCED for r in synced)
    assert len(get_recordings(db_path, Status.PENDING)) == 0
    assert len(get_recordings(db_path, Status.SYNCED)) == 2


def test_sync_ingest_folder_is_idempotent(db_path: Path, ingest_dir: Path):
    """같은 폴더를 다시 스캔해도 중복 등록/재전이 없이 안전하다."""
    _make_file(ingest_dir, "a.m4a")

    first = sync_ingest_folder(ingest_dir, db_path)
    second = sync_ingest_folder(ingest_dir, db_path)

    assert len(first) == 1
    assert second == []
    assert len(get_recordings(db_path, Status.SYNCED)) == 1


def test_sync_ingest_folder_ignores_syncthing_temp_files(
    db_path: Path, ingest_dir: Path
):
    """Syncthing 임시 파일은 등록/동기화 대상에서 제외된다."""
    _make_file(ingest_dir, "a.m4a")
    _make_file(ingest_dir, ".syncthing.b.m4a.tmp")

    synced = sync_ingest_folder(ingest_dir, db_path)

    assert len(synced) == 1
    assert synced[0].filename == "a.m4a"


# --- 2자리 연도 파일명 (삼성 녹음기 / 통화녹음) --------------------------


@pytest.mark.parametrize(
    ("filename", "expected"),
    [
        # 앱 녹음 — 4자리 연도 (기존 동작 유지)
        ("rec_20260719_101500.m4a", "2026-07-19 10:15:00"),
        # 삼성 음성 녹음기 — 2자리 연도
        ("음성 260719_101500.m4a", "2026-07-19 10:15:00"),
        # 삼성 통화 녹음 — 상대 이름이 섞여 있다
        ("통화 홍길동_260704_115255.m4a", "2026-07-04 11:52:55"),
    ],
)
def test_파일명에서_녹음_시각을_읽는다(
    db_path: Path, ingest_dir: Path, filename: str, expected: str
) -> None:
    file_path = _make_file(ingest_dir, filename)

    recording = register_recording(db_path, file_path)

    assert recording is not None
    assert recording.recorded_at == expected


def test_날짜가_아닌_숫자열은_시각으로_오인하지_않는다(
    db_path: Path, ingest_dir: Path
) -> None:
    # 구분자가 없는 숫자열은 날짜로 보지 않는다 -> mtime 폴백.
    file_path = _make_file(ingest_dir, "123456789012.m4a")

    recording = register_recording(db_path, file_path)

    assert recording is not None
    assert recording.recorded_at != "2012-34-56 78:90:12"


def test_범위를_벗어난_날짜는_폴백한다(db_path: Path, ingest_dir: Path) -> None:
    # 99월 99일 — 구분자는 맞지만 실재하지 않는 날짜.
    file_path = _make_file(ingest_dir, "음성 269999_101500.m4a")

    recording = register_recording(db_path, file_path)

    assert recording is not None
    assert not recording.recorded_at.startswith("2026-99")
