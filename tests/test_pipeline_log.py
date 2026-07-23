# @TASK P4-S5-T1 - 파이프라인 로그 기록 (_pipeline.md) 테스트 (TDD RED)
# @SPEC docs/planning/06-tasks.md#P4-S5-T1
from __future__ import annotations

from pathlib import Path

from thinktank.notes.pipeline_log import PIPELINE_LOG_FILENAME, append_pipeline_log
from thinktank.report import PipelineRun, RunError


def _sample_run(**overrides) -> PipelineRun:
    """success only 실행 1건 (기본값)."""
    defaults: dict = dict(
        run_date="2025-01-15",
        executed_at="2025-01-15T02:00:00",
        success_count=12,
        fail_count=0,
        duration="2시간 15분",
        status="완료",
        errors=[],
    )
    defaults.update(overrides)
    return PipelineRun(**defaults)


def _partial_run(**overrides) -> PipelineRun:
    """일부 실패 실행 1건 (기본값)."""
    defaults: dict = dict(
        run_date="2025-01-14",
        executed_at="2025-01-14T02:10:00",
        success_count=11,
        fail_count=1,
        duration="2시간 20분",
        status="부분완료",
        errors=[
            RunError(
                filename="file_x.m4a",
                cause="전사 타임아웃",
                action="다음 배치에서 재시도",
            )
        ],
    )
    defaults.update(overrides)
    return PipelineRun(**defaults)


class TestAppendPipelineLogCreatesFile:
    def test_creates_file_at_vault_root(self, tmp_path: Path):
        path = append_pipeline_log(_sample_run(), tmp_path)
        assert path == tmp_path / PIPELINE_LOG_FILENAME
        assert path.exists()

    def test_creates_vault_dir_if_missing(self, tmp_path: Path):
        vault = tmp_path / "vault"
        append_pipeline_log(_sample_run(), vault)
        assert (vault / "_pipeline.md").exists()

    def test_frontmatter_type_is_log(self, tmp_path: Path):
        path = append_pipeline_log(_sample_run(), tmp_path)
        content = path.read_text(encoding="utf-8")
        assert "type: log" in content

    def test_frontmatter_date_is_run_date(self, tmp_path: Path):
        path = append_pipeline_log(_sample_run(), tmp_path)
        content = path.read_text(encoding="utf-8")
        assert "date: 2025-01-15" in content

    def test_includes_title_heading(self, tmp_path: Path):
        path = append_pipeline_log(_sample_run(), tmp_path)
        content = path.read_text(encoding="utf-8")
        assert "# Pipeline 실행 로그" in content

    def test_does_not_touch_other_vault_entries(self, tmp_path: Path):
        (tmp_path / "10-daily").mkdir()
        append_pipeline_log(_sample_run(), tmp_path)
        assert (tmp_path / "10-daily").is_dir()


class TestFieldCoverage:
    """pipeline_runs.[run_date, executed_at, success_count, fail_count, duration,
    status, errors] 7개 필드가 모두 기록에 포함되어야 한다."""

    def test_all_seven_fields_present(self, tmp_path: Path):
        run = _partial_run()
        path = append_pipeline_log(run, tmp_path)
        content = path.read_text(encoding="utf-8")

        assert run.run_date in content
        assert run.executed_at in content
        assert str(run.success_count) in content
        assert str(run.fail_count) in content
        assert run.duration in content
        assert run.status in content
        # errors: filename/cause/action 모두 표시
        assert run.errors[0].filename in content
        assert run.errors[0].cause in content
        assert run.errors[0].action in content


class TestStatusEmoji:
    def test_완료_shows_check_emoji(self, tmp_path: Path):
        path = append_pipeline_log(_sample_run(status="완료", fail_count=0), tmp_path)
        content = path.read_text(encoding="utf-8")
        assert "✅" in content
        assert "❌" not in content

    def test_부분완료_shows_warning_emoji(self, tmp_path: Path):
        path = append_pipeline_log(_partial_run(), tmp_path)
        content = path.read_text(encoding="utf-8")
        assert "⚠️" in content

    def test_실패_shows_cross_emoji(self, tmp_path: Path):
        run = _partial_run(
            success_count=0,
            fail_count=1,
            status="실패",
        )
        path = append_pipeline_log(run, tmp_path)
        content = path.read_text(encoding="utf-8")
        assert "❌" in content


class TestErrorSection:
    def test_success_run_has_no_failure_line(self, tmp_path: Path):
        path = append_pipeline_log(_sample_run(), tmp_path)
        content = path.read_text(encoding="utf-8")
        assert "**실패**:" not in content

    def test_failed_run_includes_failure_line(self, tmp_path: Path):
        run = _partial_run()
        path = append_pipeline_log(run, tmp_path)
        content = path.read_text(encoding="utf-8")
        assert run.errors[0].filename in content
        assert run.errors[0].cause in content
        assert run.errors[0].action in content


class TestPrependOrder:
    def test_new_run_date_prepended_above_existing(self, tmp_path: Path):
        append_pipeline_log(_sample_run(run_date="2025-01-13"), tmp_path)
        path = append_pipeline_log(_sample_run(run_date="2025-01-14"), tmp_path)
        content = path.read_text(encoding="utf-8")

        idx_14 = content.index("## 2025-01-14")
        idx_13 = content.index("## 2025-01-13")
        assert idx_14 < idx_13

    def test_existing_history_preserved(self, tmp_path: Path):
        append_pipeline_log(_sample_run(run_date="2025-01-13"), tmp_path)
        path = append_pipeline_log(_sample_run(run_date="2025-01-14"), tmp_path)
        content = path.read_text(encoding="utf-8")

        assert "## 2025-01-13" in content
        assert "## 2025-01-14" in content

    def test_frontmatter_date_tracks_latest_run(self, tmp_path: Path):
        append_pipeline_log(_sample_run(run_date="2025-01-13"), tmp_path)
        path = append_pipeline_log(_sample_run(run_date="2025-01-14"), tmp_path)
        content = path.read_text(encoding="utf-8")
        assert "date: 2025-01-14" in content


class TestIdempotentSameRunDate:
    def test_same_run_date_does_not_duplicate(self, tmp_path: Path):
        append_pipeline_log(_sample_run(run_date="2025-01-15"), tmp_path)
        path = append_pipeline_log(_sample_run(run_date="2025-01-15"), tmp_path)
        content = path.read_text(encoding="utf-8")
        assert content.count("## 2025-01-15") == 1

    def test_same_run_date_replaces_content(self, tmp_path: Path):
        append_pipeline_log(
            _sample_run(run_date="2025-01-15", success_count=1), tmp_path
        )
        path = append_pipeline_log(
            _sample_run(run_date="2025-01-15", success_count=99), tmp_path
        )
        content = path.read_text(encoding="utf-8")
        assert "99건 성공" in content or "99" in content
        assert content.count("## 2025-01-15") == 1

    def test_rerun_of_older_date_replaces_in_place(self, tmp_path: Path):
        append_pipeline_log(_sample_run(run_date="2025-01-13"), tmp_path)
        append_pipeline_log(_sample_run(run_date="2025-01-14"), tmp_path)
        append_pipeline_log(_sample_run(run_date="2025-01-15"), tmp_path)

        # 2025-01-14 를 다른 값으로 재실행
        path = append_pipeline_log(
            _partial_run(run_date="2025-01-14"), tmp_path
        )
        content = path.read_text(encoding="utf-8")

        idx_15 = content.index("## 2025-01-15")
        idx_14 = content.index("## 2025-01-14")
        idx_13 = content.index("## 2025-01-13")
        assert idx_15 < idx_14 < idx_13
        assert content.count("## 2025-01-14") == 1
