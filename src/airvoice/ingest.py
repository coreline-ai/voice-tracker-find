# @TASK P1-R1-T2 - 수집 스캐너 (Syncthing 수신 폴더 스캔 + 등록 + synced 전이)
# @SPEC docs/planning/06-tasks.md#P1-R1-T2
# @TEST tests/test_ingest.py
"""Syncthing 수신 폴더를 스캔해 신규 오디오 파일을 recordings 테이블에 등록한다.

Syncthing 은 파일 전송이 끝난 뒤에만 최종 파일명으로 노출하므로(전송 중에는
`.syncthing.*.tmp` / `~syncthing~*` 임시 파일로 존재), 스캔에서 발견되는
최종 파일명은 이미 동기화가 완료된 것으로 간주한다.
"""

from __future__ import annotations

import fnmatch
import re
import sqlite3
from datetime import UTC, datetime
from pathlib import Path

from airvoice.db import (
    Recording,
    Status,
    get_recordings,
    insert_recording,
    update_recording_status,
)

AUDIO_EXTENSIONS = frozenset({".m4a", ".wav", ".mp3", ".ogg"})

_SYNCTHING_TEMP_PATTERNS = (".syncthing.*.tmp", "~syncthing~*")

# 앱 녹음: 'rec_20260719_101500.m4a' — 4자리 연도.
_FILENAME_TIMESTAMP_RE = re.compile(
    r"(20\d{2})(\d{2})(\d{2})[_-]?(\d{2})(\d{2})(\d{2})"
)

# 삼성 녹음기/통화녹음: '음성 260719_101500.m4a', '통화 홍길동_260704_115255.m4a'
# — 연도가 2자리다. 이게 안 잡히면 녹음 시각 대신 파일 수정시각으로 폴백해서
# 날짜가 실제 녹음일이 아닌 전송일로 붙는다.
#
# 임의의 숫자열을 날짜로 오인하지 않도록 구분자('_'/'-')를 필수로 요구하고,
# 앞뒤가 숫자면 매칭하지 않는다(4자리 연도 형식의 일부를 잘라 먹는 것 방지).
_FILENAME_TIMESTAMP_SHORT_RE = re.compile(
    r"(?<!\d)(\d{2})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})(?!\d)"
)

_TIMESTAMP_FORMAT = "%Y-%m-%d %H:%M:%S"


def _is_syncthing_temp(filename: str) -> bool:
    """Syncthing 임시 파일(.syncthing.*.tmp, ~syncthing~*) 여부를 반환한다."""
    return any(
        fnmatch.fnmatch(filename, pattern) for pattern in _SYNCTHING_TEMP_PATTERNS
    )


def scan_ingest_folder(ingest_dir: str | Path) -> list[Path]:
    """수신 폴더에서 지원되는 오디오 파일 목록을 찾는다.

    Args:
        ingest_dir: Syncthing 수신 폴더 경로.

    Returns:
        지원 형식(.m4a/.wav/.mp3/.ogg)이면서 Syncthing 임시 파일이 아닌
        파일 경로 목록 (이름순 정렬). 폴더가 없으면 빈 목록.
    """
    dir_path = Path(ingest_dir).expanduser()
    if not dir_path.is_dir():
        return []

    return sorted(
        entry
        for entry in dir_path.iterdir()
        if entry.is_file()
        and entry.suffix.lower() in AUDIO_EXTENSIONS
        and not _is_syncthing_temp(entry.name)
    )


def _to_datetime(parts: tuple[str, ...]) -> datetime | None:
    """(연,월,일,시,분,초) 문자열 묶음을 datetime 으로 변환한다 (범위 밖이면 None)."""
    try:
        return datetime(*(int(part) for part in parts))
    except ValueError:
        return None


def _parse_recorded_at_from_filename(stem: str) -> datetime | None:
    """파일명에서 타임스탬프 패턴을 찾아 datetime 으로 변환한다 (실패 시 None).

    4자리 연도(앱 녹음)를 먼저 보고, 없으면 2자리 연도(삼성 녹음기/통화녹음)를
    본다. 2자리는 20xx 로 해석한다.
    """
    match = _FILENAME_TIMESTAMP_RE.search(stem)
    if match is not None:
        parsed = _to_datetime(match.groups())
        if parsed is not None:
            return parsed

    match = _FILENAME_TIMESTAMP_SHORT_RE.search(stem)
    if match is None:
        return None
    year, *rest = match.groups()
    return _to_datetime((f"20{year}", *rest))


def _extract_recorded_at(path: Path) -> str:
    """recorded_at 값을 구한다 (파일명 파싱 우선, 실패 시 mtime)."""
    parsed = _parse_recorded_at_from_filename(path.stem)
    if parsed is not None:
        return parsed.strftime(_TIMESTAMP_FORMAT)
    mtime = datetime.fromtimestamp(path.stat().st_mtime, tz=UTC)
    return mtime.strftime(_TIMESTAMP_FORMAT)


def register_recording(
    db_path: str | Path, source_path: str | Path
) -> Recording | None:
    """오디오 파일을 pending 상태로 등록한다 (recorded_at/file_size 자동 추출).

    Args:
        db_path: SQLite DB 파일 경로.
        source_path: 등록할 오디오 파일의 경로.

    Returns:
        새로 등록된 Recording. 이미 등록된 source_path 이면 아무 것도 하지
        않고 None 을 반환한다 (멱등 - 매일 재실행 안전).
    """
    path = Path(source_path)
    try:
        return insert_recording(
            db_path,
            source_path=str(path),
            filename=path.name,
            recorded_at=_extract_recorded_at(path),
            file_size=path.stat().st_size,
        )
    except sqlite3.IntegrityError:
        return None


def sync_ingest_folder(
    ingest_dir: str | Path, db_path: str | Path
) -> list[Recording]:
    """수신 폴더를 스캔해 신규 파일을 등록하고 synced 상태로 전이한다.

    스캔에서 발견된 파일은 Syncthing 이 전송을 마친 뒤에만 노출한 최종
    파일명이므로 곧바로 pending -> synced 로 전이한다. 이미 등록/동기화된
    파일은 건너뛰므로 매일 재실행해도 안전하다(멱등).

    Args:
        ingest_dir: Syncthing 수신 폴더 경로.
        db_path: SQLite DB 파일 경로.

    Returns:
        이번 호출에서 새로 synced 상태로 전이된 Recording 목록.
    """
    for file_path in scan_ingest_folder(ingest_dir):
        register_recording(db_path, file_path)

    pending = get_recordings(db_path, Status.PENDING)
    now = datetime.now(UTC).strftime(_TIMESTAMP_FORMAT)
    for recording in pending:
        update_recording_status(db_path, recording.id, Status.SYNCED, now)

    pending_ids = {recording.id for recording in pending}
    return [
        recording
        for recording in get_recordings(db_path, Status.SYNCED)
        if recording.id in pending_ids
    ]
