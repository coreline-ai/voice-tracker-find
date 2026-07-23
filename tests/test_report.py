# @TASK P4-R1-T1 - 배치 실행 집계 (pipeline_runs)
# @SPEC docs/planning/06-tasks.md#P4-R1-T1
# @TEST tests/test_report.py
from __future__ import annotations

import dataclasses
import sqlite3
from datetime import datetime
from pathlib import Path

import pytest

from thinktank.db import Status, init_db, insert_recording
from thinktank.report import PipelineRun, RunError, build_run_report, format_duration


@pytest.fixture
def db_path(tmp_path: Path) -> Path:
    """초기화된 임시 DB 파일 경로."""
    path = tmp_path / "pipeline.db"
    init_db(path)
    return path


def _mark(db_path: Path, recording_id: int, status: str, **timestamps: str) -> None:
    """레코딩 상태와 타임스탬프 컬럼(들)을 직접 갱신한다 (테스트 전용 헬퍼)."""
    columns = ", ".join(f"{col} = ?" for col in timestamps)
    assignment = f"status = ?{', ' + columns if columns else ''}"
    sql = f"UPDATE recordings SET {assignment} WHERE id = ?"
    conn = sqlite3.connect(db_path)
    conn.execute(sql, (status, *timestamps.values(), recording_id))
    conn.commit()
    conn.close()


def _set_error_msg(db_path: Path, recording_id: int, error_msg: str) -> None:
    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE recordings SET error_msg = ? WHERE id = ?", (error_msg, recording_id)
    )
    conn.commit()
    conn.close()


class TestFormatDuration:
    def test_hours_and_minutes(self):
        assert format_duration(2 * 3600 + 15 * 60) == "2시간 15분"

    def test_seconds_only(self):
        assert format_duration(45) == "45초"

    def test_minutes_only(self):
        assert format_duration(5 * 60) == "5분"

    def test_minutes_and_seconds(self):
        assert format_duration(5 * 60 + 30) == "5분 30초"

    def test_zero(self):
        assert format_duration(0) == "0초"


class TestBuildRunReportStatus:
    def test_완료_when_no_failures(self, db_path: Path):
        ok = insert_recording(db_path, source_path="/a/ok.m4a", filename="ok.m4a")
        _mark(db_path, ok.id, Status.ORGANIZED, organized_at="2025-01-14 10:00:00")

        report = build_run_report(
            db_path,
            run_date="2025-01-14",
            started_at=datetime(2025, 1, 14, 2, 0, 0),
            finished_at=datetime(2025, 1, 14, 2, 0, 45),
        )

        assert isinstance(report, PipelineRun)
        assert report.status == "완료"
        assert report.success_count == 1
        assert report.fail_count == 0
        assert report.errors == []

    def test_부분완료_when_some_failures(self, db_path: Path):
        ok = insert_recording(db_path, source_path="/a/ok.m4a", filename="ok.m4a")
        fail = insert_recording(db_path, source_path="/a/fail.m4a", filename="fail.m4a")
        _mark(db_path, ok.id, Status.ORGANIZED, organized_at="2025-01-14 10:00:00")
        _mark(
            db_path,
            fail.id,
            Status.EXTRACTED_FAILED,
            extracted_at="2025-01-14 11:00:00",
        )
        _set_error_msg(db_path, fail.id, "API timeout")

        report = build_run_report(
            db_path,
            run_date="2025-01-14",
            started_at=datetime(2025, 1, 14, 2, 0, 0),
            finished_at=datetime(2025, 1, 14, 4, 15, 0),
        )

        assert report.status == "부분완료"
        assert report.success_count == 1
        assert report.fail_count == 1
        assert report.duration == "2시간 15분"
        assert report.errors == [
            RunError(
                filename="fail.m4a", cause="API timeout", action="다음 배치에서 재시도"
            )
        ]

    def test_실패_when_all_failed(self, db_path: Path):
        fail = insert_recording(db_path, source_path="/a/fail.m4a", filename="fail.m4a")
        _mark(
            db_path,
            fail.id,
            Status.TRANSCRIBED_FAILED,
            transcribed_at="2025-01-14 09:00:00",
        )
        _set_error_msg(db_path, fail.id, "no speech detected")

        report = build_run_report(
            db_path,
            run_date="2025-01-14",
            started_at=datetime(2025, 1, 14, 2, 0, 0),
            finished_at=datetime(2025, 1, 14, 2, 0, 45),
        )

        assert report.status == "실패"
        assert report.success_count == 0
        assert report.fail_count == 1
        assert report.errors == [
            RunError(
                filename="fail.m4a",
                cause="no speech detected",
                action="다음 배치에서 재시도",
            )
        ]


def test_build_run_report_excludes_other_dates(db_path: Path):
    """run_date 와 다른 날짜의 성공/실패 레코딩은 집계에서 제외된다."""
    other_day = insert_recording(
        db_path, source_path="/a/other.m4a", filename="other.m4a"
    )
    _mark(db_path, other_day.id, Status.ORGANIZED, organized_at="2025-01-13 10:00:00")

    report = build_run_report(
        db_path,
        run_date="2025-01-14",
        started_at=datetime(2025, 1, 14, 2, 0, 0),
        finished_at=datetime(2025, 1, 14, 2, 0, 45),
    )

    assert report.success_count == 0
    assert report.status == "완료"


def test_build_run_report_executed_at_uses_started_at(db_path: Path):
    started = datetime(2025, 1, 14, 2, 0, 0)
    report = build_run_report(
        db_path,
        run_date="2025-01-14",
        started_at=started,
        finished_at=datetime(2025, 1, 14, 2, 0, 45),
    )
    assert report.executed_at == started.isoformat()


def test_build_run_report_is_idempotent(db_path: Path):
    """동일 run_date 로 반복 호출해도 동일한 결과를 반환한다 (멱등 집계)."""
    ok = insert_recording(db_path, source_path="/a/ok.m4a", filename="ok.m4a")
    _mark(db_path, ok.id, Status.ORGANIZED, organized_at="2025-01-14 10:00:00")

    started = datetime(2025, 1, 14, 2, 0, 0)
    finished = datetime(2025, 1, 14, 2, 0, 45)

    first = build_run_report(
        db_path, run_date="2025-01-14", started_at=started, finished_at=finished
    )
    second = build_run_report(
        db_path, run_date="2025-01-14", started_at=started, finished_at=finished
    )

    assert first == second


def test_pipeline_run_is_frozen(db_path: Path):
    report = build_run_report(
        db_path,
        run_date="2025-01-14",
        started_at=datetime(2025, 1, 14, 2, 0, 0),
        finished_at=datetime(2025, 1, 14, 2, 0, 45),
    )
    with pytest.raises(dataclasses.FrozenInstanceError):
        report.status = "완료"  # type: ignore[misc]


def test_run_error_is_frozen():
    error = RunError(filename="a.m4a", cause="timeout", action="재시도")
    with pytest.raises(dataclasses.FrozenInstanceError):
        error.cause = "다른 사유"  # type: ignore[misc]
