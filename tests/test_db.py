# @TASK P1-R1-T1 - SQLite 저장소 (recordings 테이블 + 상태 전이 + 멱등 조회)
# @SPEC docs/planning/06-tasks.md#P1-R1-T1
# @TEST tests/test_db.py
from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from airvoice.db import (
    InvalidTransitionError,
    Recording,
    Status,
    get_cleanup_targets,
    get_daily_stats,
    get_recordings,
    get_recordings_organized_on,
    get_retry_targets,
    init_db,
    insert_recording,
    is_valid_transition,
    update_extracted_json,
    update_recording_status,
)


@pytest.fixture
def db_path(tmp_path: Path) -> Path:
    """초기화된 임시 DB 파일 경로."""
    path = tmp_path / "pipeline.db"
    init_db(path)
    return path


def test_init_db_creates_recordings_table(db_path: Path):
    """init_db 는 recordings 테이블을 생성한다."""
    conn = sqlite3.connect(db_path)
    row = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='recordings'"
    ).fetchone()
    conn.close()
    assert row is not None


def test_init_db_enables_wal_mode(db_path: Path):
    """init_db 는 WAL 저널 모드를 활성화한다."""
    conn = sqlite3.connect(db_path)
    mode = conn.execute("PRAGMA journal_mode;").fetchone()[0]
    conn.close()
    assert mode.lower() == "wal"


def test_init_db_creates_parent_directory(tmp_path: Path):
    """DB 파일의 상위 디렉토리가 없으면 생성한다."""
    nested = tmp_path / "nested" / "dir" / "pipeline.db"
    init_db(nested)
    assert nested.exists()


def test_init_db_is_idempotent(db_path: Path):
    """이미 초기화된 DB에 다시 호출해도 에러가 없다."""
    init_db(db_path)


def test_insert_recording_defaults_to_pending(db_path: Path):
    """새 레코딩은 pending 상태로 등록된다."""
    rec = insert_recording(db_path, source_path="/a/b.m4a", filename="b.m4a")
    assert isinstance(rec, Recording)
    assert rec.status == Status.PENDING
    assert rec.source_path == "/a/b.m4a"
    assert rec.id > 0


def test_insert_recording_duplicate_source_path_raises(db_path: Path):
    """source_path 중복 등록은 IntegrityError (멱등성 보장)."""
    insert_recording(db_path, source_path="/a/b.m4a", filename="b.m4a")
    with pytest.raises(sqlite3.IntegrityError):
        insert_recording(db_path, source_path="/a/b.m4a", filename="b.m4a")


def test_get_recordings_filters_by_status(db_path: Path):
    """status 로 필터링해 레코딩을 조회한다."""
    insert_recording(db_path, source_path="/a/1.m4a", filename="1.m4a")
    rec2 = insert_recording(db_path, source_path="/a/2.m4a", filename="2.m4a")
    update_recording_status(db_path, rec2.id, Status.SYNCED, "2025-01-01T00:00:00")

    pending = get_recordings(db_path, Status.PENDING)
    synced = get_recordings(db_path, Status.SYNCED)

    assert len(pending) == 1
    assert pending[0].source_path == "/a/1.m4a"
    assert len(synced) == 1
    assert synced[0].source_path == "/a/2.m4a"


def test_get_recordings_orders_oldest_first(db_path: Path):
    """조회 결과는 생성 순(오래된 순)으로 정렬된다."""
    first = insert_recording(db_path, source_path="/a/1.m4a", filename="1.m4a")
    second = insert_recording(db_path, source_path="/a/2.m4a", filename="2.m4a")

    results = get_recordings(db_path, Status.PENDING)
    assert [r.id for r in results] == [first.id, second.id]


@pytest.mark.parametrize(
    ("from_status", "to_status", "expected"),
    [
        (Status.PENDING, Status.SYNCED, True),
        (Status.SYNCED, Status.VAD_DONE, True),
        (Status.SYNCED, Status.NO_SPEECH, True),
        (Status.NO_SPEECH, Status.DELETED, True),
        (Status.NO_SPEECH, Status.VAD_DONE, False),
        (Status.VAD_DONE, Status.TRANSCRIBED, True),
        (Status.VAD_DONE, Status.TRANSCRIBED_FAILED, True),
        (Status.TRANSCRIBED_FAILED, Status.TRANSCRIBED, True),
        (Status.TRANSCRIBED, Status.EXTRACTED, True),
        (Status.TRANSCRIBED_FAILED, Status.EXTRACTED, True),
        (Status.EXTRACTED_FAILED, Status.ORGANIZED, True),
        (Status.ORGANIZED, Status.DELETED, True),
        (Status.PENDING, Status.TRANSCRIBED, False),
        (Status.DELETED, Status.PENDING, False),
        (Status.ORGANIZED, Status.PENDING, False),
    ],
)
def test_is_valid_transition(from_status: str, to_status: str, expected: bool):
    """상태 전이도(04-database-design.md §1) 기준 유효성 검사."""
    assert is_valid_transition(from_status, to_status) is expected


def test_update_recording_status_valid_transition_updates_timestamp(db_path: Path):
    """유효한 전이 시 status 와 해당 타임스탬프 컬럼이 갱신된다."""
    rec = insert_recording(db_path, source_path="/a/1.m4a", filename="1.m4a")
    update_recording_status(db_path, rec.id, Status.SYNCED, "2025-01-15T09:00:00")

    [updated] = get_recordings(db_path, Status.SYNCED)
    assert updated.status == Status.SYNCED
    assert updated.synced_at == "2025-01-15T09:00:00"


def test_update_recording_status_invalid_transition_raises(db_path: Path):
    """상태 전이도에 없는 전이는 InvalidTransitionError."""
    rec = insert_recording(db_path, source_path="/a/1.m4a", filename="1.m4a")
    with pytest.raises(InvalidTransitionError):
        update_recording_status(
            db_path, rec.id, Status.TRANSCRIBED, "2025-01-15T09:00:00"
        )


def test_update_recording_status_missing_id_raises(db_path: Path):
    """존재하지 않는 id 갱신 시 ValueError."""
    with pytest.raises(ValueError, match="찾을 수 없습니다"):
        update_recording_status(db_path, 9999, Status.SYNCED, "2025-01-15T09:00:00")


def test_update_recording_status_records_error_msg(db_path: Path):
    """실패 상태 전이 시 error_msg 를 기록한다."""
    rec = insert_recording(db_path, source_path="/a/1.m4a", filename="1.m4a")
    update_recording_status(db_path, rec.id, Status.SYNCED, "2025-01-15T09:00:00")
    update_recording_status(db_path, rec.id, Status.VAD_DONE, "2025-01-15T09:05:00")
    update_recording_status(
        db_path,
        rec.id,
        Status.TRANSCRIBED_FAILED,
        "2025-01-15T09:10:00",
        error_msg="whisper timeout",
    )

    [failed] = get_recordings(db_path, Status.TRANSCRIBED_FAILED)
    assert failed.error_msg == "whisper timeout"
    assert failed.transcribed_at == "2025-01-15T09:10:00"


def test_update_recording_status_preserves_error_msg_when_not_given(db_path: Path):
    """error_msg 를 지정하지 않으면 기존 값을 유지한다."""
    rec = insert_recording(db_path, source_path="/a/1.m4a", filename="1.m4a")
    update_recording_status(db_path, rec.id, Status.SYNCED, "2025-01-15T09:00:00")
    update_recording_status(db_path, rec.id, Status.VAD_DONE, "2025-01-15T09:05:00")
    update_recording_status(
        db_path,
        rec.id,
        Status.TRANSCRIBED_FAILED,
        "2025-01-15T09:10:00",
        error_msg="timeout",
    )
    # 재시도 성공 (error_msg 미지정 -> 기존 값 유지)
    update_recording_status(db_path, rec.id, Status.TRANSCRIBED, "2025-01-15T09:20:00")

    [done] = get_recordings(db_path, Status.TRANSCRIBED)
    assert done.error_msg == "timeout"


def test_update_extracted_json_stores_value(db_path: Path):
    """extracted_json 컬럼을 상태 전이와 무관하게 독립적으로 갱신한다."""
    rec = insert_recording(db_path, source_path="/a/1.m4a", filename="1.m4a")
    update_extracted_json(db_path, rec.id, '[{"category": "idea"}]')

    conn = sqlite3.connect(db_path)
    row = conn.execute(
        "SELECT extracted_json FROM recordings WHERE id = ?", (rec.id,)
    ).fetchone()
    conn.close()
    assert row[0] == '[{"category": "idea"}]'


def test_update_extracted_json_missing_id_raises(db_path: Path):
    """존재하지 않는 id 갱신 시 ValueError."""
    with pytest.raises(ValueError, match="찾을 수 없습니다"):
        update_extracted_json(db_path, 9999, "[]")


def test_get_cleanup_targets_returns_organized_past_retention(db_path: Path):
    """organized 상태로 N일 이상 경과한 레코딩을 폐기 대상으로 조회한다."""
    old = insert_recording(db_path, source_path="/a/old.m4a", filename="old.m4a")
    recent = insert_recording(
        db_path, source_path="/a/recent.m4a", filename="recent.m4a"
    )

    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE recordings SET status = ?, organized_at = datetime('now', '-10 days') "
        "WHERE id = ?",
        (Status.ORGANIZED, old.id),
    )
    conn.execute(
        "UPDATE recordings SET status = ?, organized_at = datetime('now', '-1 days') "
        "WHERE id = ?",
        (Status.ORGANIZED, recent.id),
    )
    conn.commit()
    conn.close()

    targets = get_cleanup_targets(db_path, days=7)
    assert [t.id for t in targets] == [old.id]


def test_get_cleanup_targets_excludes_already_deleted(db_path: Path):
    """이미 deleted_at 이 있는 레코딩은 폐기 대상에서 제외한다."""
    rec = insert_recording(db_path, source_path="/a/old.m4a", filename="old.m4a")
    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE recordings SET status = ?, organized_at = datetime('now', '-10 days'), "
        "deleted_at = datetime('now') WHERE id = ?",
        (Status.ORGANIZED, rec.id),
    )
    conn.commit()
    conn.close()

    assert get_cleanup_targets(db_path, days=7) == []


def test_get_retry_targets_returns_failed_statuses(db_path: Path):
    """전사/추출 실패 상태의 레코딩을 재시도 대상으로 조회한다."""
    rec1 = insert_recording(db_path, source_path="/a/1.m4a", filename="1.m4a")
    rec2 = insert_recording(db_path, source_path="/a/2.m4a", filename="2.m4a")
    insert_recording(db_path, source_path="/a/3.m4a", filename="3.m4a")  # pending, 제외

    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE recordings SET status = ? WHERE id = ?",
        (Status.TRANSCRIBED_FAILED, rec1.id),
    )
    conn.execute(
        "UPDATE recordings SET status = ? WHERE id = ?",
        (Status.EXTRACTED_FAILED, rec2.id),
    )
    conn.commit()
    conn.close()

    targets = get_retry_targets(db_path)
    assert {t.id for t in targets} == {rec1.id, rec2.id}


def test_get_retry_targets_respects_limit(db_path: Path):
    """limit 파라미터로 반환 개수를 제한한다."""
    for i in range(5):
        rec = insert_recording(db_path, source_path=f"/a/{i}.m4a", filename=f"{i}.m4a")
        conn = sqlite3.connect(db_path)
        conn.execute(
            "UPDATE recordings SET status = ? WHERE id = ?",
            (Status.TRANSCRIBED_FAILED, rec.id),
        )
        conn.commit()
        conn.close()

    assert len(get_retry_targets(db_path, limit=3)) == 3


def test_get_recordings_organized_on_returns_only_that_date_ordered(db_path: Path):
    """지정한 날짜에 organized 로 전이된 레코딩만 organized_at 오름차순으로 반환한다."""
    same_day_later = insert_recording(
        db_path, source_path="/a/later.m4a", filename="later.m4a"
    )
    same_day_earlier = insert_recording(
        db_path, source_path="/a/earlier.m4a", filename="earlier.m4a"
    )
    other_day = insert_recording(
        db_path, source_path="/a/other.m4a", filename="other.m4a"
    )
    not_organized = insert_recording(
        db_path, source_path="/a/pending.m4a", filename="pending.m4a"
    )

    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE recordings SET status = ?, organized_at = '2025-01-14 11:00:00' "
        "WHERE id = ?",
        (Status.ORGANIZED, same_day_later.id),
    )
    conn.execute(
        "UPDATE recordings SET status = ?, organized_at = '2025-01-14 09:00:00' "
        "WHERE id = ?",
        (Status.ORGANIZED, same_day_earlier.id),
    )
    conn.execute(
        "UPDATE recordings SET status = ?, organized_at = '2025-01-15 09:00:00' "
        "WHERE id = ?",
        (Status.ORGANIZED, other_day.id),
    )
    conn.commit()
    conn.close()

    results = get_recordings_organized_on(db_path, "2025-01-14")

    assert [r.id for r in results] == [same_day_earlier.id, same_day_later.id]
    assert not_organized.id not in [r.id for r in results]


def test_get_daily_stats_counts_success_and_failure(db_path: Path):
    """지정한 날짜의 성공/실패 처리 건수를 집계한다.

    수정(Defect A): EXTRACTED_FAILED 전이는 실제로 organized_at 이 아닌
    extracted_at 컬럼을 갱신하므로(_STATUS_TIMESTAMP_COLUMN 참조), 이 테스트도
    실제 파이프라인 동작에 맞춰 extracted_at 을 기준으로 검증하도록 수정했다.
    """
    ok = insert_recording(db_path, source_path="/a/ok.m4a", filename="ok.m4a")
    fail = insert_recording(db_path, source_path="/a/fail.m4a", filename="fail.m4a")

    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE recordings SET status = ?, organized_at = '2025-01-14 10:00:00' "
        "WHERE id = ?",
        (Status.ORGANIZED, ok.id),
    )
    conn.execute(
        "UPDATE recordings SET status = ?, extracted_at = '2025-01-14 11:00:00' "
        "WHERE id = ?",
        (Status.EXTRACTED_FAILED, fail.id),
    )
    conn.commit()
    conn.close()

    stats = get_daily_stats(db_path, date="2025-01-14")
    assert stats == {"success_count": 1, "fail_count": 1}


def test_get_daily_stats_counts_transcribed_failed_by_transcribed_at(db_path: Path):
    """추가(Defect A): transcribed_failed 는 transcribed_at 기준으로 집계된다."""
    fail = insert_recording(db_path, source_path="/a/fail2.m4a", filename="fail2.m4a")

    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE recordings SET status = ?, transcribed_at = '2025-01-14 09:00:00' "
        "WHERE id = ?",
        (Status.TRANSCRIBED_FAILED, fail.id),
    )
    conn.commit()
    conn.close()

    stats = get_daily_stats(db_path, date="2025-01-14")
    assert stats == {"success_count": 0, "fail_count": 1}


def test_get_daily_stats_defaults_to_yesterday(db_path: Path):
    """date 를 지정하지 않으면 어제 날짜 기준으로 집계한다."""
    rec = insert_recording(db_path, source_path="/a/y.m4a", filename="y.m4a")
    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE recordings SET status = ?, organized_at = datetime('now', '-1 day') "
        "WHERE id = ?",
        (Status.ORGANIZED, rec.id),
    )
    conn.commit()
    conn.close()

    stats = get_daily_stats(db_path)
    assert stats == {"success_count": 1, "fail_count": 0}
