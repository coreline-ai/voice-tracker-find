# @TASK P4-S5-T1 - 파이프라인 로그 기록 (_pipeline.md)
# @SPEC docs/planning/06-tasks.md#P4-S5-T1
# @TEST tests/test_pipeline_log.py
"""배치 실행 결과(PipelineRun)를 볼트 루트 _pipeline.md 에 기록한다.

근거: specs/screens/pipeline-log.yaml, docs/planning/04-database-design.md
§2 "파이프라인 로그". 새 실행은 기존 이력 위에 최신순으로 prepend 하되,
같은 run_date 로 재실행된 경우 기존 항목을 같은 위치에서 교체한다 (멱등).
"""

from __future__ import annotations

from pathlib import Path

from thinktank.notes.renderer import render_frontmatter
from thinktank.report import PipelineRun

PIPELINE_LOG_FILENAME = "_pipeline.md"

_TITLE = "# Pipeline 실행 로그"

_STATUS_EMOJI: dict[str, str] = {
    "완료": "✅",
    "부분완료": "⚠️",
    "실패": "❌",
}


def _render_run_block(run: PipelineRun) -> str:
    """실행 1건을 '## {run_date}' 블록(실행/처리/실패/처리 시간/상태)으로 렌더링한다."""
    lines = [
        f"## {run.run_date}",
        f"- **실행**: {run.executed_at}",
        f"- **처리**: {run.success_count}건 성공 / {run.fail_count}건 실패",
    ]
    if run.fail_count > 0:
        for error in run.errors:
            lines.append(
                f"- **실패**: {error.filename} ({error.cause}) - {error.action}"
            )
    lines.append(f"- **처리 시간**: {run.duration}")
    lines.append(f"- **상태**: {_STATUS_EMOJI[run.status]} {run.status}")
    return "\n".join(lines)


def _parse_existing_records(text: str) -> list[tuple[str, str]]:
    """기존 파일 본문에서 '## {run_date}' 블록들을 순서(최신순)대로 추출한다."""
    body = text.split(_TITLE, 1)[-1]

    records: list[tuple[str, str]] = []
    current_date: str | None = None
    current_lines: list[str] = []

    def _flush() -> None:
        if current_date is not None:
            records.append((current_date, "\n".join(current_lines).strip("\n")))

    for line in body.splitlines():
        if line.startswith("## "):
            _flush()
            current_date = line[len("## ") :].strip()
            current_lines = [line]
        elif current_date is not None:
            current_lines.append(line)
    _flush()
    return records


def append_pipeline_log(run: PipelineRun, vault_path: str | Path) -> Path:
    """배치 실행 결과를 볼트 루트 _pipeline.md 에 최신순으로 기록한다.

    Args:
        run: 하루 배치 실행 1회의 집계 결과 (build_run_report 반환값).
        vault_path: Obsidian 볼트 루트 경로 (DI - config 를 import 하지 않음).

    Returns:
        갱신된 _pipeline.md 파일 경로.
    """
    vault = Path(vault_path).expanduser()
    vault.mkdir(parents=True, exist_ok=True)
    note_path = vault / PIPELINE_LOG_FILENAME

    records = (
        _parse_existing_records(note_path.read_text(encoding="utf-8"))
        if note_path.exists()
        else []
    )

    new_block = (run.run_date, _render_run_block(run))
    existing_idx = next(
        (i for i, (date, _) in enumerate(records) if date == run.run_date), None
    )
    if existing_idx is None:
        records.insert(0, new_block)
    else:
        records[existing_idx] = new_block

    latest_date = max(date for date, _ in records)
    frontmatter = render_frontmatter({"type": "log", "date": latest_date})
    body = "\n\n".join(block for _, block in records)
    note_path.write_text(f"{frontmatter}\n{_TITLE}\n\n{body}\n", encoding="utf-8")
    return note_path
