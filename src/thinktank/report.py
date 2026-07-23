# @TASK P4-R1-T1 - 배치 실행 집계 (pipeline_runs)
# @SPEC docs/planning/06-tasks.md#P4-R1-T1
# @TEST tests/test_report.py
"""하루 배치 실행 1회의 결과를 recordings 테이블에서 집계한다.

pipeline_runs 리소스(specs/domain/resources.yaml)에 정의된 형태로
success_count/fail_count/duration/status/errors 를 계산한다. 집계는
db.get_daily_stats/get_recordings 를 읽기만 하는 순수 조회이므로 같은
run_date 로 몇 번을 호출해도 항상 동일한 결과를 반환한다 (멱등).
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from thinktank.db import Status, get_daily_stats, get_recordings

_STATUS_완료 = "완료"
_STATUS_부분완료 = "부분완료"
_STATUS_실패 = "실패"

_RETRY_ACTION = "다음 배치에서 재시도"

# 실패 상태와, 해당 실패가 실제로 갱신하는 타임스탬프 컬럼의 짝.
# db.get_daily_stats() 의 날짜 매칭 기준(_STATUS_TIMESTAMP_COLUMN)과 동일하게
# 맞춰야 success_count/fail_count 와 errors 목록이 같은 날짜 기준으로 일치한다.
_FAILURE_STATUS_TIMESTAMPS: tuple[tuple[str, str], ...] = (
    (Status.EXTRACTED_FAILED, "extracted_at"),
    (Status.TRANSCRIBED_FAILED, "transcribed_at"),
)


@dataclass(frozen=True)
class RunError:
    """배치 실행 중 실패한 파일 1건의 상세 (filename/cause/action)."""

    filename: str
    cause: str
    action: str


@dataclass(frozen=True)
class PipelineRun:
    """하루 배치 실행 1회의 집계 결과 (pipeline_runs 리소스)."""

    run_date: str
    executed_at: str
    success_count: int
    fail_count: int
    duration: str
    status: str
    errors: list[RunError]


def format_duration(seconds: float) -> str:
    """초 단위 시간을 사람이 읽는 한국어 문자열로 바꾼다.

    예: 8100 -> "2시간 15분", 330 -> "5분 30초", 45 -> "45초".
    """
    total = int(round(seconds))
    hours, remainder = divmod(total, 3600)
    minutes, secs = divmod(remainder, 60)

    if hours > 0:
        return f"{hours}시간 {minutes}분"
    if minutes > 0:
        return f"{minutes}분 {secs}초" if secs else f"{minutes}분"
    return f"{secs}초"


def _determine_status(success_count: int, fail_count: int) -> str:
    if fail_count == 0:
        return _STATUS_완료
    if success_count > 0:
        return _STATUS_부분완료
    return _STATUS_실패


def _collect_errors(db_path: str | Path, run_date: str) -> list[RunError]:
    """run_date 에 실패한 레코딩들을 filename/cause/action 상세로 변환한다."""
    errors = [
        RunError(filename=rec.filename, cause=rec.error_msg or "", action=_RETRY_ACTION)
        for status, ts_field in _FAILURE_STATUS_TIMESTAMPS
        for rec in get_recordings(db_path, status)
        if getattr(rec, ts_field, None) and getattr(rec, ts_field)[:10] == run_date
    ]
    return sorted(errors, key=lambda e: e.filename)


def build_run_report(
    db_path: str | Path,
    run_date: str,
    started_at: datetime,
    finished_at: datetime,
) -> PipelineRun:
    """recordings 테이블을 집계해 하루 배치 실행 리포트를 만든다.

    Args:
        db_path: SQLite DB 파일 경로.
        run_date: 집계 대상 날짜 ('YYYY-MM-DD').
        started_at: 배치 실행 시작 시각.
        finished_at: 배치 실행 종료 시각.

    Returns:
        PipelineRun: success_count/fail_count/duration/status/errors 가
        채워진 집계 결과.
    """
    stats = get_daily_stats(db_path, date=run_date)
    success_count = stats["success_count"]
    fail_count = stats["fail_count"]

    return PipelineRun(
        run_date=run_date,
        executed_at=started_at.isoformat(),
        success_count=success_count,
        fail_count=fail_count,
        duration=format_duration((finished_at - started_at).total_seconds()),
        status=_determine_status(success_count, fail_count),
        errors=_collect_errors(db_path, run_date),
    )
