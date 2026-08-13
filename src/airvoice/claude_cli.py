# @TASK AI_PROVIDER - 로컬 claude CLI(구독 OAuth) 백엔드
# @SPEC (사용자 요청) 로컬 claude CLI를 통한 AI 백엔드
# @TEST tests/test_claude_cli.py
"""로컬 claude CLI(구독 OAuth 로그인)로 프롬프트를 실행하는 헬퍼.

API 키(CLAUDE_API_KEY) 없이 `claude -p` 헤드리스 모드를 사용해 extract/emerge
등의 LLM 호출을 대체할 수 있다. Windows에서는 `claude` 가 .cmd 셔임일 수 있어
반드시 :func:`shutil.which` 로 실제 경로를 찾아 실행한다.

프롬프트는 커맨드라인 인자가 아니라 stdin 으로 전달한다. emerge 처럼 주제 노트
전체를 합친 긴 프롬프트를 인자로 넘기면 Windows 명령줄 길이 한계(약 32KB)를
넘겨 `WinError 206`(파일 이름/확장명이 너무 김)으로 실패하기 때문이다.
"""

from __future__ import annotations

import shutil
import subprocess

DEFAULT_CLI_TIMEOUT = 120.0


def run_claude_cli(
    prompt: str, model: str | None = None, timeout: float = DEFAULT_CLI_TIMEOUT
) -> str:
    """로컬 claude CLI(-p 헤드리스)로 프롬프트를 실행하고 응답 텍스트(stdout)를
    반환한다.

    구독 로그인(OAuth)으로 인증되므로 API 키가 필요 없다. 실패 시 RuntimeError.

    Args:
        prompt: claude CLI에 전달할 프롬프트 전문.
        model: 사용할 모델 ID. None 이면 claude CLI 기본 모델을 사용한다.
        timeout: 프로세스 실행 타임아웃 (초).

    Returns:
        claude CLI의 표준출력(stdout) 텍스트.

    Raises:
        RuntimeError: claude 명령을 찾을 수 없거나 실행이 실패했을 때.
    """
    exe = shutil.which("claude")
    if exe is None:
        raise RuntimeError("claude 명령을 찾을 수 없습니다 (PATH/로그인 확인).")
    # 프롬프트는 인자가 아니라 stdin(input=)으로 전달해 명령줄 길이 한계를 피한다.
    cmd = [exe, "-p"]
    if model:
        cmd += ["--model", model]
    result = subprocess.run(
        cmd,
        input=prompt,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=timeout,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"claude CLI 실패 (code {result.returncode}): "
            f"{(result.stderr or '').strip()[:500]}"
        )
    return result.stdout
