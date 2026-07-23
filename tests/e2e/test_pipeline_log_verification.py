# @TASK P4-S5-V - 파이프라인 로그 연결점 검증 (통합 관점)
# @SPEC docs/planning/06-tasks.md#P4-S5-V
# @TEST tests/e2e/test_pipeline_log_verification.py
"""배치 실행 집계(report.build_run_report) -> 파이프라인 로그 기록
(notes/pipeline_log.append_pipeline_log)로 이어지는 연결점을 검증한다.

이미 구현된 각 모듈(report.py/notes/pipeline_log.py)의 단위 동작은 각자의
단위 테스트(tests/test_report.py, tests/test_pipeline_log.py)에서 검증됐다.
이 파일은 모듈 간 계약이 어댑터 없이 그대로 맞물리는지(통합 관점)를 검증한다.
recordings 는 실제 상태 전이도(db.py _VALID_TRANSITIONS)를 따라
pending -> ... -> organized/extracted_failed/transcribed_failed 로 전이시킨
tmp DB를 사용하고, 기록 대상은 tmp_path 볼트를 사용한다.
근거: specs/screens/pipeline-log.yaml.
"""

from __future__ import annotations

from datetime import datetime
from pathlib import Path

import pytest

from thinktank.db import Status, init_db, insert_recording, update_recording_status
from thinktank.notes.pipeline_log import append_pipeline_log
from thinktank.report import PipelineRun, build_run_report


@pytest.fixture
def db_path(tmp_path: Path) -> Path:
    """초기화된 임시 DB 파일 경로."""
    path = tmp_path / "pipeline.db"
    init_db(path)
    return path


@pytest.fixture
def vault_path(tmp_path: Path) -> Path:
    """Obsidian 볼트 루트로 사용할 임시 폴더."""
    path = tmp_path / "vault"
    path.mkdir()
    return path


def _to_organized(db_path: Path, recording_id: int, run_date: str) -> None:
    """실제 상태 전이도를 따라 pending -> ... -> organized 로 전이시킨다
    (organized_at = run_date, get_daily_stats 의 success_count 집계 대상)."""
    update_recording_status(
        db_path, recording_id, Status.SYNCED, f"{run_date} 01:00:00"
    )
    update_recording_status(
        db_path, recording_id, Status.VAD_DONE, f"{run_date} 01:05:00"
    )
    update_recording_status(
        db_path, recording_id, Status.TRANSCRIBED, f"{run_date} 01:10:00"
    )
    update_recording_status(
        db_path, recording_id, Status.EXTRACTED, f"{run_date} 01:20:00"
    )
    update_recording_status(
        db_path, recording_id, Status.ORGANIZED, f"{run_date} 01:30:00"
    )


def _to_extracted_failed(
    db_path: Path, recording_id: int, run_date: str, error_msg: str
) -> None:
    """전사까지는 성공했지만 추출 단계에서 실패한 레코딩
    (extracted_at = run_date, get_daily_stats 의 fail_count 집계 대상)."""
    update_recording_status(
        db_path, recording_id, Status.SYNCED, f"{run_date} 01:00:00"
    )
    update_recording_status(
        db_path, recording_id, Status.VAD_DONE, f"{run_date} 01:05:00"
    )
    update_recording_status(
        db_path, recording_id, Status.TRANSCRIBED, f"{run_date} 01:10:00"
    )
    update_recording_status(
        db_path,
        recording_id,
        Status.EXTRACTED_FAILED,
        f"{run_date} 01:20:00",
        error_msg=error_msg,
    )


def _run_and_log(
    db_path: Path,
    vault_path: Path,
    run_date: str,
    started_at: datetime,
    finished_at: datetime,
) -> tuple[PipelineRun, Path]:
    """build_run_report -> append_pipeline_log 를 어댑터 없이 그대로 연결한다."""
    run = build_run_report(
        db_path, run_date=run_date, started_at=started_at, finished_at=finished_at
    )
    note_path = append_pipeline_log(run, vault_path)
    return run, note_path


# ---------------------------------------------------------------------------
# 검증 항목 1: Field Coverage - pipeline_runs 7필드(run_date/executed_at/
# success_count/fail_count/duration/status/errors)가 DB 집계 -> PipelineRun ->
# _pipeline.md 에 어댑터 없이 흘러가는가.
# ---------------------------------------------------------------------------


class TestFieldCoverage:
    def test_all_seven_fields_flow_from_db_to_note_without_adapter(
        self, db_path: Path, vault_path: Path
    ):
        run_date = "2025-01-15"
        ok = insert_recording(db_path, source_path="/a/ok.m4a", filename="ok.m4a")
        _to_organized(db_path, ok.id, run_date)
        fail = insert_recording(
            db_path, source_path="/a/fail.m4a", filename="fail.m4a"
        )
        _to_extracted_failed(db_path, fail.id, run_date, "API timeout")

        run, note_path = _run_and_log(
            db_path,
            vault_path,
            run_date,
            started_at=datetime(2025, 1, 15, 2, 0, 0),
            finished_at=datetime(2025, 1, 15, 4, 15, 0),
        )

        # DB 집계 결과가 실제로 정확한지 (fixture가 의도한 값과 일치).
        assert isinstance(run, PipelineRun)
        assert run.success_count == 1
        assert run.fail_count == 1
        assert run.status == "부분완료"

        content = note_path.read_text(encoding="utf-8")
        assert run.run_date in content
        assert run.executed_at in content
        assert str(run.success_count) in content
        assert str(run.fail_count) in content
        assert run.duration in content
        assert run.status in content
        assert run.errors[0].filename in content
        assert run.errors[0].cause in content
        assert run.errors[0].action in content

    def test_success_only_run_flows_seven_fields_without_adapter(
        self, db_path: Path, vault_path: Path
    ):
        run_date = "2025-01-16"
        for i in range(3):
            rec = insert_recording(
                db_path, source_path=f"/a/ok{i}.m4a", filename=f"ok{i}.m4a"
            )
            _to_organized(db_path, rec.id, run_date)

        run, note_path = _run_and_log(
            db_path,
            vault_path,
            run_date,
            started_at=datetime(2025, 1, 16, 2, 0, 0),
            finished_at=datetime(2025, 1, 16, 2, 0, 45),
        )

        assert run.success_count == 3
        assert run.fail_count == 0
        assert run.status == "완료"
        assert run.errors == []

        content = note_path.read_text(encoding="utf-8")
        assert run.run_date in content
        assert run.executed_at in content
        assert str(run.success_count) in content
        assert str(run.fail_count) in content
        assert run.duration in content
        assert run.status in content


# ---------------------------------------------------------------------------
# 검증 항목 2: 화면 명세 tests 4개 (specs/screens/pipeline-log.yaml)
# ---------------------------------------------------------------------------


class TestScreenSpecScenarios:
    def test_scenario_batch_completion_history_prepended(
        self, db_path: Path, vault_path: Path
    ):
        """시나리오 1: 배치 완료 후 실행 이력 기록 - 최신 실행일이 상단에
        prepend되고, run_date/executed_at/success_count/fail_count/duration/
        status 가 정확히 기록되며, 기존 이력은 아래로 내려간다."""
        older = insert_recording(
            db_path, source_path="/a/older.m4a", filename="older.m4a"
        )
        _to_organized(db_path, older.id, "2025-01-14")
        _run_and_log(
            db_path,
            vault_path,
            "2025-01-14",
            started_at=datetime(2025, 1, 14, 2, 0, 0),
            finished_at=datetime(2025, 1, 14, 2, 20, 0),
        )

        newer = insert_recording(
            db_path, source_path="/a/newer.m4a", filename="newer.m4a"
        )
        _to_organized(db_path, newer.id, "2025-01-15")
        run, note_path = _run_and_log(
            db_path,
            vault_path,
            "2025-01-15",
            started_at=datetime(2025, 1, 15, 2, 0, 0),
            finished_at=datetime(2025, 1, 15, 2, 15, 0),
        )

        content = note_path.read_text(encoding="utf-8")
        idx_new = content.index("2025-01-15")
        idx_old = content.index("2025-01-14")
        assert idx_new < idx_old, "최신 실행일 이력이 상단에 prepend되어야 한다."

        assert run.run_date in content
        assert run.executed_at in content
        assert str(run.success_count) in content
        assert str(run.fail_count) in content
        assert run.duration in content
        assert run.status in content

    def test_scenario_success_no_error_section(
        self, db_path: Path, vault_path: Path
    ):
        """시나리오 2: 배치 성공 시(fail_count = 0) 에러 로그 섹션이 표시되지
        않고, 실행 이력에는 '✅ 완료' 상태만 표시된다."""
        run_date = "2025-01-15"
        ok = insert_recording(db_path, source_path="/a/ok.m4a", filename="ok.m4a")
        _to_organized(db_path, ok.id, run_date)

        run, note_path = _run_and_log(
            db_path,
            vault_path,
            run_date,
            started_at=datetime(2025, 1, 15, 2, 0, 0),
            finished_at=datetime(2025, 1, 15, 2, 15, 0),
        )

        assert run.fail_count == 0
        content = note_path.read_text(encoding="utf-8")
        assert "**실패**:" not in content
        assert "❌" not in content
        assert "⚠️" not in content
        assert "✅" in content
        assert "완료" in content

    def test_scenario_failure_shows_filename_cause_action_and_status(
        self, db_path: Path, vault_path: Path
    ):
        """시나리오 3: 배치 중 일부/전체 실패 시(fail_count > 0) 에러 로그에
        filename/cause/action 이 표시되고, 상태는 '⚠️ 부분완료' 또는
        '❌ 실패'로 표시되며, 재시도 계획(다음 배치)이 명시된다."""
        run_date = "2025-01-15"
        ok = insert_recording(db_path, source_path="/a/ok.m4a", filename="ok.m4a")
        _to_organized(db_path, ok.id, run_date)
        fail = insert_recording(
            db_path, source_path="/a/fail.m4a", filename="fail.m4a"
        )
        _to_extracted_failed(db_path, fail.id, run_date, "전사 타임아웃")

        run, note_path = _run_and_log(
            db_path,
            vault_path,
            run_date,
            started_at=datetime(2025, 1, 15, 2, 0, 0),
            finished_at=datetime(2025, 1, 15, 4, 20, 0),
        )
        content = note_path.read_text(encoding="utf-8")

        assert run.status == "부분완료"
        assert "⚠️" in content
        assert "fail.m4a" in content
        assert "전사 타임아웃" in content
        assert "다음 배치" in content
        assert "재시도" in content

        # 전체 실패(success_count = 0) -> ❌ 실패
        run_date_2 = "2025-01-16"
        total_fail = insert_recording(
            db_path, source_path="/a/total_fail.m4a", filename="total_fail.m4a"
        )
        _to_extracted_failed(db_path, total_fail.id, run_date_2, "인코딩 오류")
        run2, note_path2 = _run_and_log(
            db_path,
            vault_path,
            run_date_2,
            started_at=datetime(2025, 1, 16, 2, 0, 0),
            finished_at=datetime(2025, 1, 16, 2, 5, 0),
        )
        content2 = note_path2.read_text(encoding="utf-8")

        assert run2.status == "실패"
        assert "❌" in content2
        assert "total_fail.m4a" in content2
        assert "인코딩 오류" in content2

    def test_scenario_weekly_monitoring_multiple_dates_latest_first(
        self, db_path: Path, vault_path: Path
    ):
        """시나리오 4: 주간 모니터링 - 지난주 모든 배치 실행 이력이 최신순으로
        표시되고, '완료' 상태는 한눈에 구분되며, '부분완료' 상태는 에러 내역으로
        드릴다운(같은 문서 내 filename 확인) 가능하다."""
        run_dates = [
            "2025-01-12",
            "2025-01-13",
            "2025-01-14",
            "2025-01-15",
            "2025-01-16",
        ]
        note_path = None
        for i, run_date in enumerate(run_dates):
            ok = insert_recording(
                db_path, source_path=f"/a/ok_{run_date}.m4a", filename=f"ok_{i}.m4a"
            )
            _to_organized(db_path, ok.id, run_date)
            if run_date == "2025-01-14":
                fail = insert_recording(
                    db_path,
                    source_path=f"/a/fail_{run_date}.m4a",
                    filename="week_fail.m4a",
                )
                _to_extracted_failed(db_path, fail.id, run_date, "네트워크 오류")

            _, note_path = _run_and_log(
                db_path,
                vault_path,
                run_date,
                started_at=datetime.fromisoformat(f"{run_date}T02:00:00"),
                finished_at=datetime.fromisoformat(f"{run_date}T02:20:00"),
            )

        assert note_path is not None
        content = note_path.read_text(encoding="utf-8")

        # 모든 날짜의 이력이 남아있고, 최신순(내림차순)으로 나열된다.
        indices = [content.index(d) for d in run_dates]
        assert indices == sorted(indices, reverse=True)

        # 완료 상태(✅)와 부분완료 상태(⚠️)가 함께 존재해 구분 가능하다.
        assert "✅" in content
        assert "⚠️" in content
        # 부분완료 배치의 실패 파일까지 같은 문서에서 드릴다운 가능하다.
        assert "week_fail.m4a" in content
        assert "네트워크 오류" in content


# ---------------------------------------------------------------------------
# 검증 항목 3: 멱등성 - 같은 run_date 로 재실행해도 중복 블록이 생기지 않는다.
# ---------------------------------------------------------------------------


class TestIdempotency:
    def test_rerun_same_run_date_does_not_duplicate_block(
        self, db_path: Path, vault_path: Path
    ):
        run_date = "2025-01-15"
        ok = insert_recording(db_path, source_path="/a/ok.m4a", filename="ok.m4a")
        _to_organized(db_path, ok.id, run_date)

        started = datetime(2025, 1, 15, 2, 0, 0)
        finished = datetime(2025, 1, 15, 2, 15, 0)

        _run_and_log(db_path, vault_path, run_date, started, finished)
        run2, note_path = _run_and_log(db_path, vault_path, run_date, started, finished)

        content = note_path.read_text(encoding="utf-8")
        assert content.count(f"## {run_date}") == 1
        assert run2.success_count == 1

    def test_rerun_after_new_failure_replaces_block_in_place(
        self, db_path: Path, vault_path: Path
    ):
        """같은 run_date 를 재실행했을 때(예: 실패 건이 새로 잡혀 최신 집계로
        갱신된 경우) 기존 항목이 같은 위치에서 값만 교체되고 중복되지 않는다."""
        run_date = "2025-01-15"
        ok = insert_recording(db_path, source_path="/a/ok.m4a", filename="ok.m4a")
        _to_organized(db_path, ok.id, run_date)

        started = datetime(2025, 1, 15, 2, 0, 0)
        _run_and_log(
            db_path,
            vault_path,
            run_date,
            started,
            datetime(2025, 1, 15, 2, 15, 0),
        )

        fail = insert_recording(
            db_path, source_path="/a/fail.m4a", filename="fail.m4a"
        )
        _to_extracted_failed(db_path, fail.id, run_date, "지연된 재시도 실패")
        run2, note_path = _run_and_log(
            db_path,
            vault_path,
            run_date,
            started,
            datetime(2025, 1, 15, 4, 30, 0),
        )

        content = note_path.read_text(encoding="utf-8")
        assert content.count(f"## {run_date}") == 1
        assert run2.fail_count == 1
        assert "fail.m4a" in content
        assert "지연된 재시도 실패" in content
