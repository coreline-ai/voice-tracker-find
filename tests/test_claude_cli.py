# @TASK AI_PROVIDER - 로컬 claude CLI 헬퍼 테스트 (TDD RED)
# @SPEC (사용자 요청) 로컬 claude CLI를 통한 AI 백엔드
# @TEST tests/test_claude_cli.py
"""run_claude_cli 단위 테스트.

실제 claude CLI를 호출하지 않는다 (구독 사용량 소모/속도 문제). shutil.which,
subprocess.run 을 monkeypatch 로 대체해 검증한다.
"""

from __future__ import annotations

import subprocess

import pytest

from airvoice.claude_cli import run_claude_cli


class _FakeCompletedProcess:
    def __init__(self, returncode: int, stdout: str = "", stderr: str = "") -> None:
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


def test_run_claude_cli_returns_stdout_on_success(monkeypatch):
    monkeypatch.setattr(
        "airvoice.claude_cli.shutil.which", lambda name: "C:/fake/claude.cmd"
    )

    captured_cmd: list[str] = []
    captured_kwargs: dict = {}

    def _fake_run(cmd, **kwargs):
        captured_cmd.extend(cmd)
        captured_kwargs.update(kwargs)
        return _FakeCompletedProcess(returncode=0, stdout="응답 텍스트")

    monkeypatch.setattr("airvoice.claude_cli.subprocess.run", _fake_run)

    result = run_claude_cli("프롬프트")

    assert result == "응답 텍스트"
    assert captured_cmd[0] == "C:/fake/claude.cmd"
    assert "-p" in captured_cmd
    # 프롬프트는 인자가 아니라 stdin(input=)으로 전달돼야 한다 (WinError 206 회피).
    assert captured_kwargs["input"] == "프롬프트"
    assert "프롬프트" not in captured_cmd


def test_run_claude_cli_raises_on_nonzero_returncode(monkeypatch):
    monkeypatch.setattr(
        "airvoice.claude_cli.shutil.which", lambda name: "C:/fake/claude.cmd"
    )
    monkeypatch.setattr(
        "airvoice.claude_cli.subprocess.run",
        lambda cmd, **kwargs: _FakeCompletedProcess(returncode=1, stderr="뭔가 실패함"),
    )

    with pytest.raises(RuntimeError):
        run_claude_cli("프롬프트")


def test_run_claude_cli_raises_when_claude_not_found(monkeypatch):
    monkeypatch.setattr("airvoice.claude_cli.shutil.which", lambda name: None)

    with pytest.raises(RuntimeError):
        run_claude_cli("프롬프트")


def test_run_claude_cli_includes_model_flag_when_given(monkeypatch):
    monkeypatch.setattr(
        "airvoice.claude_cli.shutil.which", lambda name: "C:/fake/claude.cmd"
    )

    captured_cmd: list[str] = []

    def _fake_run(cmd, **kwargs):
        captured_cmd.extend(cmd)
        return _FakeCompletedProcess(returncode=0, stdout="ok")

    monkeypatch.setattr("airvoice.claude_cli.subprocess.run", _fake_run)

    run_claude_cli("프롬프트", model="claude-sonnet-5")

    assert "--model" in captured_cmd
    assert "claude-sonnet-5" in captured_cmd


def test_run_claude_cli_passes_timeout_to_subprocess_run(monkeypatch):
    monkeypatch.setattr(
        "airvoice.claude_cli.shutil.which", lambda name: "C:/fake/claude.cmd"
    )

    captured_kwargs: dict = {}

    def _fake_run(cmd, **kwargs):
        captured_kwargs.update(kwargs)
        return _FakeCompletedProcess(returncode=0, stdout="ok")

    monkeypatch.setattr("airvoice.claude_cli.subprocess.run", _fake_run)

    run_claude_cli("프롬프트", timeout=42.0)

    assert captured_kwargs["timeout"] == 42.0


def test_run_claude_cli_calls_real_subprocess_signature(monkeypatch):
    """subprocess.run 이 실제로는 존재하는 함수임을 확인 (import 안전성)."""
    assert callable(subprocess.run)
