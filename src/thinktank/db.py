# @TASK P1-R1-T1 - SQLite 저장소 (recordings 테이블 + 상태 전이 + 멱등 조회)
# @SPEC docs/planning/06-tasks.md#P1-R1-T1
# @TEST tests/test_db.py
"""녹음 파일 처리 상태를 추적하는 SQLite 저장소.

recordings 테이블 하나로 파이프라인 각 단계(수집 -> VAD -> 전사 -> 추출 ->
정리 -> 폐기)의 진행 상태를 관리한다. 스키마와 상태 전이도는
docs/planning/04-database-design.md §1 을 따른다.
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from pathlib import Path

_SCHEMA = """
CREATE TABLE IF NOT EXISTS recordings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_path TEXT NOT NULL UNIQUE,
    filename TEXT NOT NULL,
    recorded_at TIMESTAMP,
    file_size INTEGER,
    synced_at TIMESTAMP,
    vad_at TIMESTAMP,
    transcribed_at TIMESTAMP,
    extracted_at TIMESTAMP,
    organized_at TIMESTAMP,
    status TEXT NOT NULL,
    extracted_json TEXT,
    error_msg TEXT,
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT (datetime('now'))
);
"""

_INDEXES = (
    "CREATE INDEX IF NOT EXISTS idx_recordings_status ON recordings(status);",
    "CREATE INDEX IF NOT EXISTS idx_recordings_organized_at "
    "ON recordings(organized_at);",
)


class Status(StrEnum):
    """recordings.status 컬럼에 허용되는 상태 값 (10종)."""

    PENDING = "pending"
    SYNCED = "synced"
    VAD_DONE = "vad_done"
    NO_SPEECH = "no_speech"
    TRANSCRIBED = "transcribed"
    TRANSCRIBED_FAILED = "transcribed_failed"
    EXTRACTED = "extracted"
    EXTRACTED_FAILED = "extracted_failed"
    ORGANIZED = "organized"
    DELETED = "deleted"


# 상태 전이도 (04-database-design.md §1 "상태 전이도" 참조)
_VALID_TRANSITIONS: dict[str, frozenset[str]] = {
    Status.PENDING: frozenset({Status.SYNCED}),
    Status.SYNCED: frozenset({Status.VAD_DONE, Status.NO_SPEECH}),
    Status.NO_SPEECH: frozenset({Status.DELETED}),
    Status.VAD_DONE: frozenset({Status.TRANSCRIBED, Status.TRANSCRIBED_FAILED}),
    Status.TRANSCRIBED_FAILED: frozenset(
        {
            Status.TRANSCRIBED,
            Status.TRANSCRIBED_FAILED,
            Status.EXTRACTED,
            Status.EXTRACTED_FAILED,
        }
    ),
    Status.TRANSCRIBED: frozenset({Status.EXTRACTED, Status.EXTRACTED_FAILED}),
    Status.EXTRACTED_FAILED: frozenset(
        {Status.EXTRACTED, Status.EXTRACTED_FAILED, Status.ORGANIZED}
    ),
    Status.EXTRACTED: frozenset({Status.ORGANIZED}),
    Status.ORGANIZED: frozenset({Status.DELETED}),
    Status.DELETED: frozenset(),
}

# 상태별로 갱신할 타임스탬프 컬럼 (pending 은 created_at 이 이미 담당하므로 없음)
_STATUS_TIMESTAMP_COLUMN: dict[str, str] = {
    Status.SYNCED: "synced_at",
    Status.VAD_DONE: "vad_at",
    Status.NO_SPEECH: "vad_at",
    Status.TRANSCRIBED: "transcribed_at",
    Status.TRANSCRIBED_FAILED: "transcribed_at",
    Status.EXTRACTED: "extracted_at",
    Status.EXTRACTED_FAILED: "extracted_at",
    Status.ORGANIZED: "organized_at",
    Status.DELETED: "deleted_at",
}


class InvalidTransitionError(Exception):
    """상태 전이도에 없는 전이를 시도했을 때 발생."""


@dataclass(frozen=True)
class Recording:
    """recordings 테이블의 한 행 (15컬럼)."""

    id: int
    source_path: str
    filename: str
    recorded_at: str | None
    file_size: int | None
    synced_at: str | None
    vad_at: str | None
    transcribed_at: str | None
    extracted_at: str | None
    organized_at: str | None
    status: str
    extracted_json: str | None
    error_msg: str | None
    deleted_at: str | None
    created_at: str


def _connect(db_path: str | Path) -> sqlite3.Connection:
    """DB 파일에 연결하고 WAL 모드 + Row 팩토리를 설정한다."""
    conn = sqlite3.connect(str(Path(db_path).expanduser()))
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL;")
    return conn


def _row_to_recording(row: sqlite3.Row) -> Recording:
    return Recording(**dict(row))


def _get_recording_by_id(db_path: str | Path, recording_id: int) -> Recording:
    conn = _connect(db_path)
    try:
        row = conn.execute(
            "SELECT * FROM recordings WHERE id = ?", (recording_id,)
        ).fetchone()
    finally:
        conn.close()
    if row is None:
        raise ValueError(f"recording id={recording_id} 를 찾을 수 없습니다.")
    return _row_to_recording(row)


def init_db(db_path: str | Path) -> None:
    """recordings 테이블과 인덱스를 생성하고 WAL 모드를 활성화한다.

    Args:
        db_path: SQLite DB 파일 경로. 상위 디렉토리가 없으면 생성한다.
    """
    path = Path(db_path).expanduser()
    path.parent.mkdir(parents=True, exist_ok=True)

    conn = _connect(path)
    try:
        conn.execute(_SCHEMA)
        for statement in _INDEXES:
            conn.execute(statement)
        conn.commit()
    finally:
        conn.close()


def is_valid_transition(from_status: str, to_status: str) -> bool:
    """상태 전이도(04-database-design.md §1) 기준으로 전이 허용 여부를 반환한다."""
    return to_status in _VALID_TRANSITIONS.get(from_status, frozenset())


def insert_recording(
    db_path: str | Path,
    source_path: str,
    filename: str,
    recorded_at: str | None = None,
    file_size: int | None = None,
) -> Recording:
    """새 녹음 파일을 pending 상태로 등록한다.

    source_path 는 UNIQUE 제약이므로, 이미 등록된 파일을 다시 등록하려 하면
    sqlite3.IntegrityError 가 발생한다 (중복 등록 방지를 통한 멱등성 보장).

    Args:
        db_path: SQLite DB 파일 경로.
        source_path: 원본 파일 절대 경로 (UNIQUE).
        filename: 파일명.
        recorded_at: 녹음 시작 시간 (메타데이터).
        file_size: 파일 크기 (바이트).

    Returns:
        생성된 Recording.
    """
    conn = _connect(db_path)
    try:
        cursor = conn.execute(
            """
            INSERT INTO recordings
                (source_path, filename, recorded_at, file_size, status)
            VALUES (?, ?, ?, ?, ?)
            """,
            (source_path, filename, recorded_at, file_size, Status.PENDING),
        )
        conn.commit()
        row_id = int(cursor.lastrowid)
    finally:
        conn.close()

    return _get_recording_by_id(db_path, row_id)


def get_recordings(db_path: str | Path, status: str) -> list[Recording]:
    """주어진 상태의 레코딩을 오래된 순(created_at ASC)으로 조회한다."""
    conn = _connect(db_path)
    try:
        rows = conn.execute(
            "SELECT * FROM recordings WHERE status = ? ORDER BY created_at ASC",
            (status,),
        ).fetchall()
    finally:
        conn.close()
    return [_row_to_recording(row) for row in rows]


def update_recording_status(
    db_path: str | Path,
    recording_id: int,
    status: str,
    timestamp: str,
    error_msg: str | None = None,
) -> None:
    """레코딩 상태를 갱신하고 해당 단계의 타임스탬프 컬럼을 기록한다.

    Args:
        db_path: SQLite DB 파일 경로.
        recording_id: 갱신할 레코딩 id.
        status: 새 상태 (Status 값 중 하나).
        timestamp: 새 상태에 해당하는 타임스탬프 컬럼에 기록할 값.
        error_msg: 실패 사유. 지정하지 않으면 기존 값을 유지한다.

    Raises:
        InvalidTransitionError: 현재 상태에서 허용되지 않는 전이일 때.
        ValueError: recording_id 가 존재하지 않을 때.
    """
    current = _get_recording_by_id(db_path, recording_id)
    if not is_valid_transition(current.status, status):
        raise InvalidTransitionError(
            f"{current.status!r} -> {status!r} 전이는 허용되지 않습니다."
        )

    timestamp_column = _STATUS_TIMESTAMP_COLUMN.get(status)
    conn = _connect(db_path)
    try:
        if timestamp_column is None:
            conn.execute(
                "UPDATE recordings SET status = ?, error_msg = COALESCE(?, error_msg) "
                "WHERE id = ?",
                (status, error_msg, recording_id),
            )
        else:
            # timestamp_column 은 _STATUS_TIMESTAMP_COLUMN 의 고정된 내부 값만
            # 사용하므로 f-string 삽입이 안전하다 (외부 입력이 아님).
            conn.execute(
                f"UPDATE recordings SET status = ?, {timestamp_column} = ?, "
                "error_msg = COALESCE(?, error_msg) WHERE id = ?",
                (status, timestamp, error_msg, recording_id),
            )
        conn.commit()
    finally:
        conn.close()


def update_extracted_json(
    db_path: str | Path, recording_id: int, extracted_json: str
) -> None:
    """recordings.extracted_json 컬럼을 갱신한다 (상태 전이와 무관한 결과 저장용).

    Args:
        db_path: SQLite DB 파일 경로.
        recording_id: 갱신할 레코딩 id.
        extracted_json: 저장할 JSON 문자열 (extract.items_to_json 결과).

    Raises:
        ValueError: recording_id 가 존재하지 않을 때.
    """
    _get_recording_by_id(db_path, recording_id)  # 존재 검증 (없으면 ValueError)
    conn = _connect(db_path)
    try:
        conn.execute(
            "UPDATE recordings SET extracted_json = ? WHERE id = ?",
            (extracted_json, recording_id),
        )
        conn.commit()
    finally:
        conn.close()


def get_cleanup_targets(db_path: str | Path, days: int) -> list[Recording]:
    """폐기 대상 레코딩을 조회한다: days일 이상 경과하고 아직 삭제되지 않은 것.

    두 종류를 포함한다:
    - organized 로 정리 완료된 레코딩 (organized_at 기준)
    - no_speech 로 종료된 무음 레코딩 (vad_at 기준) — 노트는 없고 원본만 폐기 대상.
    """
    conn = _connect(db_path)
    try:
        rows = conn.execute(
            """
            SELECT * FROM recordings
            WHERE deleted_at IS NULL
              AND (
                    (status = ? AND organized_at < datetime('now', ?))
                 OR (status = ? AND vad_at < datetime('now', ?))
              )
            ORDER BY COALESCE(organized_at, vad_at) ASC
            """,
            (Status.ORGANIZED, f"-{days} days", Status.NO_SPEECH, f"-{days} days"),
        ).fetchall()
    finally:
        conn.close()
    return [_row_to_recording(row) for row in rows]


def get_retry_targets(db_path: str | Path, limit: int = 10) -> list[Recording]:
    """전사/추출 실패 상태의 레코딩을 재시도 대상으로 조회한다."""
    conn = _connect(db_path)
    try:
        rows = conn.execute(
            """
            SELECT * FROM recordings
            WHERE status IN (?, ?)
            ORDER BY transcribed_at ASC
            LIMIT ?
            """,
            (Status.TRANSCRIBED_FAILED, Status.EXTRACTED_FAILED, limit),
        ).fetchall()
    finally:
        conn.close()
    return [_row_to_recording(row) for row in rows]


def get_recordings_organized_on(db_path: str | Path, date: str) -> list[Recording]:
    """지정한 날짜에 organized 로 전이된 모든 레코딩을 organized_at 오름차순으로 조회.

    데일리 노트는 그 날 organized된 전체 레코딩의 합산 스냅샷이어야 하므로
    (배치 1회분만이 아니라), main._organize 가 write_daily_note 호출 전에
    이 함수로 그 날의 전체 items/sources 를 재구성하는 데 사용한다.

    Args:
        db_path: SQLite DB 파일 경로.
        date: 'YYYY-MM-DD' 형식 날짜 (organized_at 기준).

    Returns:
        해당 날짜에 organized 상태인 레코딩 목록 (organized_at ASC).
    """
    conn = _connect(db_path)
    try:
        rows = conn.execute(
            """
            SELECT * FROM recordings
            WHERE status = ? AND DATE(organized_at) = ?
            ORDER BY organized_at ASC
            """,
            (Status.ORGANIZED, date),
        ).fetchall()
    finally:
        conn.close()
    return [_row_to_recording(row) for row in rows]


def get_recordings_recorded_on(db_path: str | Path, date: str) -> list[Recording]:
    """지정한 날짜에 **녹음된** organized 레코딩을 recorded_at 오름차순으로 조회.

    데일리 노트를 "그날 처리한 것"이 아니라 "그날 말한 것"으로 묶기 위한 조회다.
    한 주치가 한꺼번에 들어와도(통화녹음 밀린 것 등) 각 녹음일 노트로 흩어진다.

    :func:`get_recordings_organized_on` 과 짝을 이루며, 노트를 다시 쓸 때 그 날짜
    전체를 복원하는 용도라 이미 organized 된 것도 모두 포함한다.

    Args:
        db_path: SQLite DB 파일 경로.
        date: 'YYYY-MM-DD' 형식 날짜 (recorded_at 기준).

    Returns:
        해당 날짜에 녹음된 organized 레코딩 목록 (recorded_at ASC).
    """
    conn = _connect(db_path)
    try:
        rows = conn.execute(
            """
            SELECT * FROM recordings
            WHERE status = ? AND DATE(recorded_at) = ?
            ORDER BY recorded_at ASC
            """,
            (Status.ORGANIZED, date),
        ).fetchall()
    finally:
        conn.close()
    return [_row_to_recording(row) for row in rows]


def get_recorded_dates(db_path: str | Path) -> list[str]:
    """organized 레코딩이 있는 모든 녹음 날짜를 오름차순으로 조회한다.

    노트 전체 재생성에 쓴다.
    """
    conn = _connect(db_path)
    try:
        rows = conn.execute(
            """
            SELECT DISTINCT DATE(recorded_at) AS d FROM recordings
            WHERE status = ? AND recorded_at IS NOT NULL
            ORDER BY d ASC
            """,
            (Status.ORGANIZED,),
        ).fetchall()
    finally:
        conn.close()
    return [row["d"] for row in rows if row["d"]]


def get_stats_recorded_on(db_path: str | Path, date: str) -> dict[str, int]:
    """지정한 **녹음일**의 성공/실패 건수를 집계한다.

    :func:`get_daily_stats` 는 처리일(organized_at) 기준이라 "그날 처리한 양"을
    센다. 이쪽은 녹음일 기준이라 "그날 녹음한 것 중 몇 건이 정리됐나"를 센다.

    Returns:
        {"success_count": int, "fail_count": int}
    """
    conn = _connect(db_path)
    try:
        row = conn.execute(
            """
            SELECT
                COUNT(CASE WHEN status = ? THEN 1 END) AS success_count,
                COUNT(CASE WHEN status IN (?, ?) THEN 1 END) AS fail_count
            FROM recordings
            WHERE DATE(recorded_at) = ?
            """,
            (
                Status.ORGANIZED,
                Status.EXTRACTED_FAILED,
                Status.TRANSCRIBED_FAILED,
                date,
            ),
        ).fetchone()
    finally:
        conn.close()
    return {
        "success_count": row["success_count"] or 0,
        "fail_count": row["fail_count"] or 0,
    }


def get_daily_stats(db_path: str | Path, date: str | None = None) -> dict[str, int]:
    """지정한 날짜(기본: 어제)의 처리 성공/실패 건수를 집계한다.

    성공(success_count)은 organized 전이가 완료된 organized_at 날짜 기준으로
    집계한다. 실패(fail_count)는 각 실패 상태가 실제로 갱신하는 타임스탬프
    컬럼(_STATUS_TIMESTAMP_COLUMN 참조) 기준으로 집계한다: extracted_failed는
    extracted_at, transcribed_failed는 transcribed_at.

    Args:
        db_path: SQLite DB 파일 경로.
        date: 'YYYY-MM-DD' 형식 날짜. None 이면 어제 날짜(UTC)를 사용한다.

    Returns:
        {"success_count": int, "fail_count": int}
    """
    if date is None:
        date = (datetime.now(UTC) - timedelta(days=1)).strftime("%Y-%m-%d")

    conn = _connect(db_path)
    try:
        row = conn.execute(
            """
            SELECT
                COUNT(CASE WHEN status = ? AND DATE(organized_at) = ?
                    THEN 1 END) AS success_count,
                COUNT(CASE WHEN status = ? AND DATE(extracted_at) = ?
                    THEN 1 END)
                + COUNT(CASE WHEN status = ? AND DATE(transcribed_at) = ?
                    THEN 1 END) AS fail_count
            FROM recordings
            """,
            (
                Status.ORGANIZED,
                date,
                Status.EXTRACTED_FAILED,
                date,
                Status.TRANSCRIBED_FAILED,
                date,
            ),
        ).fetchone()
    finally:
        conn.close()
    return {"success_count": row["success_count"], "fail_count": row["fail_count"]}
