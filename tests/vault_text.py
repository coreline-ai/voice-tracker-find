# @TASK PA-T2 - 일일 노트 구분별 분리 (아이디어/일정/중요/기타)
# @SPEC docs/HANDOFF.md#3-개발-계획
"""테스트에서 볼트 내용을 읽는 보조 함수."""

from __future__ import annotations

from pathlib import Path

DAILY_SUBDIR = "10-daily"


def daily_all_text(vault: str | Path, date_str: str) -> str:
    """그날의 색인 노트와 구분별 노트를 모두 합쳐 읽는다.

    항목 본문은 색인(``YYYY-MM-DD.md``)이 아니라 구분별 노트
    (``YYYY-MM-DD_아이디어.md`` 등)에 저장된다. "그날 내용에 들어 있다"를
    확인하려면 둘을 함께 봐야 한다.
    """
    daily_dir = Path(vault) / DAILY_SUBDIR
    if not daily_dir.is_dir():
        return ""
    return "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(daily_dir.glob(f"{date_str}*.md"))
    )
