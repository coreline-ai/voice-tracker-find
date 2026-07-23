# @TASK P4-R2-T1 - 7일 유예 삭제 테스트 (TDD RED)
# @SPEC docs/planning/06-tasks.md#P4-R2-T1
# @TEST tests/test_cleanup.py
from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from thinktank.cleanup import run_cleanup
from thinktank.db import (
    Recording,
    Status,
    get_recordings,
    init_db,
    insert_recording,
)
from thinktank.notes.renderer import archive_filename


@pytest.fixture
def db_path(tmp_path: Path) -> Path:
    """초기화된 임시 DB 파일 경로."""
    path = tmp_path / "pipeline.db"
    init_db(path)
    return path


@pytest.fixture
def ingest_dir(tmp_path: Path) -> Path:
    """녹음 원본이 있는 수신 폴더."""
    path = tmp_path / "ingest"
    path.mkdir()
    return path


@pytest.fixture
def temp_dir(tmp_path: Path) -> Path:
    """전사 임시 파일/VAD 산출물이 있는 임시 폴더."""
    path = tmp_path / "temp"
    path.mkdir()
    return path


@pytest.fixture
def vault_path(tmp_path: Path) -> Path:
    """Obsidian 볼트 루트."""
    path = tmp_path / "vault"
    path.mkdir()
    return path


def _make_organized_recording(
    db_path: Path,
    ingest_dir: Path,
    temp_dir: Path,
    vault_path: Path,
    filename: str = "a.m4a",
    days_ago: int = 10,
    recorded_at: str | None = "2025-01-01 09:00:00",
    create_files: bool = True,
) -> Recording:
    """organized 상태이며 폐기 대상 파일 3종이 존재하는 레코딩을 만든다."""
    recording = insert_recording(
        db_path,
        source_path=str(ingest_dir / filename),
        filename=filename,
        recorded_at=recorded_at,
    )

    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE recordings SET status = ?, organized_at = datetime('now', ?) "
        "WHERE id = ?",
        (Status.ORGANIZED, f"-{days_ago} days", recording.id),
    )
    conn.commit()
    conn.close()

    if create_files:
        (ingest_dir / filename).write_bytes(b"audio-bytes")
        stem = Path(filename).stem
        (temp_dir / f"{stem}.txt").write_text("transcript text", encoding="utf-8")
        (temp_dir / filename).write_bytes(b"vad-audio-bytes")
        if recorded_at:
            archive_dir = vault_path / "90-archive"
            archive_dir.mkdir(parents=True, exist_ok=True)
            note_name = archive_filename(filename, recorded_at[:10])
            (archive_dir / note_name).write_text("archive note", encoding="utf-8")

    return next(
        r for r in get_recordings(db_path, Status.ORGANIZED) if r.id == recording.id
    )


# ---------------------------------------------------------------------------
# run_cleanup: 정상 삭제 + 상태 전이
# ---------------------------------------------------------------------------


def test_run_cleanup_deletes_all_three_target_files(
    db_path, ingest_dir, temp_dir, vault_path
):
    recording = _make_organized_recording(db_path, ingest_dir, temp_dir, vault_path)

    run_cleanup(db_path, ingest_dir, temp_dir, vault_path)

    assert not (ingest_dir / recording.filename).exists()
    assert not (temp_dir / "a.txt").exists()
    assert not (temp_dir / recording.filename).exists()
    assert not (vault_path / "90-archive" / "a_2025-01-01.md").exists()


def test_run_cleanup_transitions_recording_to_deleted(
    db_path, ingest_dir, temp_dir, vault_path
):
    recording = _make_organized_recording(db_path, ingest_dir, temp_dir, vault_path)

    result = run_cleanup(db_path, ingest_dir, temp_dir, vault_path)

    assert [r.id for r in result] == [recording.id]
    [updated] = get_recordings(db_path, Status.DELETED)
    assert updated.id == recording.id
    assert updated.deleted_at is not None
    assert get_recordings(db_path, Status.ORGANIZED) == []


def test_run_cleanup_uses_default_retention_of_7_days(
    db_path, ingest_dir, temp_dir, vault_path
):
    recording = _make_organized_recording(
        db_path, ingest_dir, temp_dir, vault_path, days_ago=8
    )

    result = run_cleanup(db_path, ingest_dir, temp_dir, vault_path)

    assert [r.id for r in result] == [recording.id]


# ---------------------------------------------------------------------------
# 유예 기간 이내 레코딩: 대상에서 제외
# ---------------------------------------------------------------------------


def test_run_cleanup_skips_recording_within_retention_period(
    db_path, ingest_dir, temp_dir, vault_path
):
    recording = _make_organized_recording(
        db_path, ingest_dir, temp_dir, vault_path, days_ago=1
    )

    result = run_cleanup(db_path, ingest_dir, temp_dir, vault_path)

    assert result == []
    assert (ingest_dir / recording.filename).exists()
    assert (temp_dir / "a.txt").exists()
    [remaining] = get_recordings(db_path, Status.ORGANIZED)
    assert remaining.id == recording.id


def test_run_cleanup_no_targets_returns_empty_list(
    db_path, ingest_dir, temp_dir, vault_path
):
    assert run_cleanup(db_path, ingest_dir, temp_dir, vault_path) == []


# ---------------------------------------------------------------------------
# 멱등성: 파일이 이미 없거나, 이미 삭제된 레코딩을 재실행해도 에러 없음
# ---------------------------------------------------------------------------


def test_run_cleanup_is_idempotent_when_files_already_missing(
    db_path, ingest_dir, temp_dir, vault_path
):
    recording = _make_organized_recording(
        db_path, ingest_dir, temp_dir, vault_path, create_files=False
    )

    result = run_cleanup(db_path, ingest_dir, temp_dir, vault_path)

    assert [r.id for r in result] == [recording.id]
    [updated] = get_recordings(db_path, Status.DELETED)
    assert updated.id == recording.id


def test_run_cleanup_is_idempotent_on_rerun(db_path, ingest_dir, temp_dir, vault_path):
    _make_organized_recording(db_path, ingest_dir, temp_dir, vault_path)

    first = run_cleanup(db_path, ingest_dir, temp_dir, vault_path)
    second = run_cleanup(db_path, ingest_dir, temp_dir, vault_path)

    assert len(first) == 1
    assert second == []


# ---------------------------------------------------------------------------
# 개별 실패 격리: 한 레코딩의 파일 삭제 실패가 배치 전체를 막지 않는다
# ---------------------------------------------------------------------------


def test_run_cleanup_skips_failed_recording_and_continues_batch(
    db_path, ingest_dir, temp_dir, vault_path, monkeypatch
):
    ok = _make_organized_recording(db_path, ingest_dir, temp_dir, vault_path, "ok.m4a")
    broken = _make_organized_recording(
        db_path, ingest_dir, temp_dir, vault_path, "broken.m4a"
    )

    original_unlink = Path.unlink

    def fake_unlink(self: Path, *args, **kwargs):
        if self.name == "broken.m4a":
            raise PermissionError("access denied")
        return original_unlink(self, *args, **kwargs)

    monkeypatch.setattr(Path, "unlink", fake_unlink)

    result = run_cleanup(db_path, ingest_dir, temp_dir, vault_path)

    assert [r.id for r in result] == [ok.id]
    [remaining] = get_recordings(db_path, Status.ORGANIZED)
    assert remaining.id == broken.id
    [deleted] = get_recordings(db_path, Status.DELETED)
    assert deleted.id == ok.id


# ---------------------------------------------------------------------------
# 무음(no_speech) 레코딩: 전사/노트는 없고 원본만 유예 기간 후 폐기 대상
# ---------------------------------------------------------------------------


def _make_no_speech_recording(
    db_path: Path,
    ingest_dir: Path,
    filename: str = "silent.m4a",
    days_ago: int = 10,
) -> Recording:
    """no_speech(종료) 상태이며 원본 파일만 존재하는 레코딩을 만든다."""
    recording = insert_recording(
        db_path, source_path=str(ingest_dir / filename), filename=filename
    )
    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE recordings SET status = ?, vad_at = datetime('now', ?) WHERE id = ?",
        (Status.NO_SPEECH, f"-{days_ago} days", recording.id),
    )
    conn.commit()
    conn.close()

    (ingest_dir / filename).write_bytes(b"silent-audio")
    return next(
        r for r in get_recordings(db_path, Status.NO_SPEECH) if r.id == recording.id
    )


def test_run_cleanup_deletes_aged_no_speech_original(
    db_path, ingest_dir, temp_dir, vault_path
):
    recording = _make_no_speech_recording(db_path, ingest_dir, days_ago=10)

    result = run_cleanup(db_path, ingest_dir, temp_dir, vault_path)

    assert [r.id for r in result] == [recording.id]
    assert not (ingest_dir / recording.filename).exists()
    [deleted] = get_recordings(db_path, Status.DELETED)
    assert deleted.id == recording.id


def test_run_cleanup_skips_no_speech_within_retention(
    db_path, ingest_dir, temp_dir, vault_path
):
    recording = _make_no_speech_recording(db_path, ingest_dir, days_ago=1)

    result = run_cleanup(db_path, ingest_dir, temp_dir, vault_path)

    assert result == []
    assert (ingest_dir / recording.filename).exists()
    [remaining] = get_recordings(db_path, Status.NO_SPEECH)
    assert remaining.id == recording.id
