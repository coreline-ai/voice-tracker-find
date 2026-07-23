# @TASK PB-T2 - 파이프라인 동시 실행 방지
# @SPEC docs/HANDOFF.md#4-L
# @TEST tests/test_lock.py
from __future__ import annotations

import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

from thinktank.lock import AlreadyRunning, PipelineLock


def test_잠금을_잡으면_파일이_생긴다(tmp_path: Path) -> None:
    db = tmp_path / "pipeline.db"

    with PipelineLock(db) as lock:
        assert lock.path.exists()
        assert lock.path.name == "pipeline.db.lock"


def test_같은_사용자를_두_번_잡을_수_없다(tmp_path: Path) -> None:
    db = tmp_path / "pipeline.db"

    with PipelineLock(db), pytest.raises(AlreadyRunning), PipelineLock(db):
        pass


def test_다른_사용자는_동시에_잡을_수_있다(tmp_path: Path) -> None:
    # 사용자마다 DB 가 다르므로 동시에 처리해도 안전하다.
    with PipelineLock(tmp_path / "user1.db"), PipelineLock(tmp_path / "user2.db"):
        pass


def test_풀린_뒤에는_다시_잡을_수_있다(tmp_path: Path) -> None:
    db = tmp_path / "pipeline.db"

    with PipelineLock(db):
        pass

    with PipelineLock(db):
        pass  # 예외가 안 나야 한다


def test_예외가_나도_잠금이_풀린다(tmp_path: Path) -> None:
    db = tmp_path / "pipeline.db"

    with pytest.raises(ValueError), PipelineLock(db):
        raise ValueError("처리 중 실패")

    with PipelineLock(db):
        pass


def test_프로세스가_죽으면_OS_가_잠금을_푼다(tmp_path: Path) -> None:
    """PID 파일 방식이면 여기서 '죽은 잠금'이 남아 다음 실행이 막힌다.

    OS 잠금은 커널이 정리하므로 크래시 후에도 다음 실행이 정상 동작해야 한다.
    """
    db = tmp_path / "pipeline.db"
    src = Path(__file__).resolve().parents[1] / "src"
    script = textwrap.dedent(f"""
        import sys
        sys.path.insert(0, {str(src)!r})
        from thinktank.lock import PipelineLock
        lock = PipelineLock({str(db)!r})
        lock.__enter__()
        sys.stdout.write("locked")
        sys.stdout.flush()
        import os
        os._exit(1)   # 정리 없이 즉사 (크래시 재현)
    """)

    result = subprocess.run(  # noqa: S603
        [sys.executable, "-c", script], capture_output=True, text=True, timeout=30
    )
    assert result.stdout == "locked", f"자식 프로세스가 잠금을 못 잡음: {result.stderr}"

    with PipelineLock(db):
        pass  # 죽은 프로세스의 잠금이 남아 있으면 여기서 AlreadyRunning


# --- 파이프라인 통합 ---------------------------------------------------


def test_실행_중이면_두_번째_파이프라인이_거부된다(tmp_path: Path) -> None:
    """야간 작업과 수동 실행이 겹쳐 상태 전이가 어긋나던 문제를 막는다."""
    from thinktank.config import Settings
    from thinktank.main import run_pipeline

    settings = Settings(
        claude_api_key="k",
        ingest_dir=tmp_path / "inbox",
        obsidian_vault=tmp_path / "vault",
        db_path=tmp_path / "pipeline.db",
        temp_dir=tmp_path / "temp",
        whisper_model="large-v3",
        vad_sample_rate=16000,
        vad_threshold=0.5,
        retention_days=7,
    )
    settings.ingest_dir.mkdir(parents=True)
    settings.obsidian_vault.mkdir(parents=True)
    settings.temp_dir.mkdir(parents=True)

    with PipelineLock(settings.db_path), pytest.raises(AlreadyRunning):
        run_pipeline(settings)
