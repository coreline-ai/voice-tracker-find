# @TASK P4-R2-T1 - 7일 유예 삭제 (organized -> deleted 전이 + 파일 폐기)
# @SPEC docs/planning/06-tasks.md#P4-R2-T1
# @TEST tests/test_cleanup.py
"""organized 상태로 유예 기간(기본 7일)이 지난 레코딩의 파일을 폐기한다.

폐기 대상 3종 (04-database-design.md §5 폐기 정책):
1. 녹음 원본 오디오 (INGEST_DIR/{filename})
2. 전사 임시 파일(TEMP_DIR/{stem}.txt) 및 VAD 산출물(TEMP_DIR/{filename})
3. 전사 아카이브 노트 (VAULT/90-archive/{stem}_{YYYY-MM-DD}.md)

파일이 이미 없어도 에러 없이 진행하며(멱등), 한 레코딩의 삭제가 실패해도
배치 전체를 막지 않고 다음 레코딩으로 넘어간다(실패한 레코딩은 organized로
남아 다음 배치에서 재시도된다).
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from pathlib import Path

from thinktank.db import (
    Recording,
    Status,
    get_cleanup_targets,
    get_recordings,
    update_recording_status,
)
from thinktank.notes.renderer import archive_filename

logger = logging.getLogger(__name__)

_TIMESTAMP_FORMAT = "%Y-%m-%d %H:%M:%S"

ARCHIVE_SUBDIR = "90-archive"


def _delete_recording_files(
    recording: Recording, ingest_dir: Path, temp_dir: Path, vault_path: Path
) -> None:
    """레코딩 하나의 폐기 대상 파일 3종을 삭제한다 (없어도 통과 - 멱등)."""
    stem = Path(recording.filename).stem

    (ingest_dir / recording.filename).unlink(missing_ok=True)
    (temp_dir / f"{stem}.txt").unlink(missing_ok=True)
    (temp_dir / recording.filename).unlink(missing_ok=True)

    if recording.recorded_at:
        note_name = archive_filename(recording.filename, recording.recorded_at[:10])
        (vault_path / ARCHIVE_SUBDIR / note_name).unlink(missing_ok=True)


def run_cleanup(
    db_path: str | Path,
    ingest_dir: str | Path,
    temp_dir: str | Path,
    vault_path: str | Path,
    retention_days: int = 7,
) -> list[Recording]:
    """유예 기간이 지난 organized 레코딩의 파일을 삭제하고 deleted로 전이한다.

    Args:
        db_path: SQLite DB 파일 경로.
        ingest_dir: 녹음 원본이 있는 수신 폴더.
        temp_dir: 전사 임시 파일(.txt)/VAD 산출물이 있는 임시 폴더.
        vault_path: Obsidian 볼트 루트 (90-archive/ 하위에 아카이브 노트가 있다).
        retention_days: organized_at 로부터 삭제까지의 유예 기간(기본 7일).

    Returns:
        이번 호출에서 deleted로 전이된 Recording 목록.
    """
    ingest_dir = Path(ingest_dir)
    temp_dir = Path(temp_dir)
    vault_path = Path(vault_path)

    processed_ids: set[int] = set()
    for recording in get_cleanup_targets(db_path, retention_days):
        try:
            _delete_recording_files(recording, ingest_dir, temp_dir, vault_path)
        except Exception as exc:  # noqa: BLE001 - 한 건 실패가 배치 전체를 막으면 안 됨
            logger.error(
                "폐기 파일 삭제 실패 (id=%s, filename=%s): %s",
                recording.id,
                recording.filename,
                exc,
            )
            continue

        now = datetime.now(UTC).strftime(_TIMESTAMP_FORMAT)
        update_recording_status(db_path, recording.id, Status.DELETED, now)
        processed_ids.add(recording.id)

    return [
        recording
        for recording in get_recordings(db_path, Status.DELETED)
        if recording.id in processed_ids
    ]
