# @TASK P1-S0-T1 - 공통 노트 렌더러 (frontmatter, 백링크, 파일명 규칙)
# @SPEC docs/planning/06-tasks.md#P1-S0-T1
"""모든 노트 유형이 공유하는 렌더링 유틸리티.

YAML frontmatter 블록, `[[위키링크]]`, 파일명 규칙(데일리/주제/창발/아카이브)을
생성한다. 근거: specs/shared/components.yaml, specs/shared/types.yaml,
docs/planning/04-database-design.md §3-4.
"""

from __future__ import annotations

import re
from datetime import date as _date
from pathlib import Path
from typing import Any

# frontmatter.type 허용값 (specs/shared/types.yaml NoteType)
# daily_section: 일일 노트를 구분별(아이디어/일정/중요/기타)로 나눈 노트.
VALID_NOTE_TYPES = frozenset(
    {"daily", "daily_section", "topic", "emerged_idea", "archive", "log"}
)


def _validate_date(date_str: str) -> str:
    """YYYY-MM-DD 형식인지 검증하고 그대로 반환."""
    try:
        _date.fromisoformat(date_str)
    except ValueError as exc:
        raise ValueError(f"date 는 YYYY-MM-DD 형식이어야 합니다: {date_str!r}") from exc
    return date_str


def normalize_filename(text: str) -> str:
    """임의 텍스트(주제명 등)를 소문자+하이픈 파일명 slug로 정규화한다.

    공백/언더스코어는 하이픈으로, 그 외 특수문자는 제거하며 연속된
    하이픈은 하나로 합친다.

    Args:
        text: 정규화할 원본 텍스트 (예: 주제명).

    Returns:
        소문자 + 하이픈으로 구성된 파일명 slug.
    """
    slug = text.strip().lower()
    slug = re.sub(r"[\s_]+", "-", slug)
    slug = re.sub(r"[^\w\-]", "", slug)
    slug = re.sub(r"-{2,}", "-", slug)
    return slug.strip("-")


def render_wikilink(topic_name: str) -> str:
    """주제명을 Obsidian 백링크 형식(`[[주제명]]`)으로 감싼다.

    Args:
        topic_name: 링크할 노트/주제 이름.

    Returns:
        `[[topic_name]]` 형식 문자열.
    """
    return f"[[{topic_name}]]"


def render_frontmatter(note_data: dict[str, Any]) -> str:
    """노트 상단 YAML frontmatter 블록을 생성한다.

    Args:
        note_data: 다음 키를 포함하는 딕셔너리.
            - type (필수): daily | daily_section | topic | emerged_idea | archive | log
            - date (필수): YYYY-MM-DD
            - category (선택): daily_section 의 구분명 (아이디어/일정/중요/기타)
            - tags (선택): 태그 문자열 목록
            - sources (선택): 소스 파일/노트 문자열 목록
            - related (선택): 백링크로 감쌀 관련 주제명 목록

    Returns:
        `---`로 감싼 YAML frontmatter 블록 (끝에 개행 포함).

    Raises:
        ValueError: type/date 가 없거나 형식이 올바르지 않을 때.
    """
    note_type = note_data.get("type")
    if not note_type:
        raise ValueError("type 은 필수 필드입니다.")
    if note_type not in VALID_NOTE_TYPES:
        raise ValueError(
            f"type 은 {sorted(VALID_NOTE_TYPES)} 중 하나여야 합니다: {note_type!r}"
        )

    date_str = note_data.get("date")
    if not date_str:
        raise ValueError("date 는 필수 필드입니다.")
    _validate_date(date_str)

    lines = ["---", f"type: {note_type}", f"date: {date_str}"]

    category = note_data.get("category")
    if category:
        lines.append(f"category: {category}")

    tags = note_data.get("tags")
    if tags:
        lines.append(f"tags: [{', '.join(tags)}]")

    sources = note_data.get("sources")
    if sources:
        lines.append(f"sources: [{', '.join(sources)}]")

    related = note_data.get("related")
    if related:
        lines.append(f"related: {', '.join(render_wikilink(r) for r in related)}")

    lines.append("---")
    return "\n".join(lines) + "\n"


def daily_filename(date_str: str) -> str:
    """데일리 노트 파일명(`YYYY-MM-DD.md`)을 생성한다."""
    _validate_date(date_str)
    return f"{date_str}.md"


def daily_section_stem(date_str: str, section_title: str) -> str:
    """섹션 노트의 확장자 없는 이름(`YYYY-MM-DD_아이디어`)을 생성한다.

    위키링크에도 같은 값을 써야 하므로 파일명과 분리해 둔다.
    """
    return f"{_validate_date(date_str)}_{section_title}"


def daily_section_filename(date_str: str, section_title: str) -> str:
    """섹션 노트 파일명(`YYYY-MM-DD_아이디어.md`)을 생성한다."""
    return f"{daily_section_stem(date_str, section_title)}.md"


def emerged_idea_filename(date_str: str, n: int) -> str:
    """창발 아이디어 노트 파일명(`YYYY-MM-DD_idea_{n}.md`)을 생성한다."""
    _validate_date(date_str)
    return f"{date_str}_idea_{n}.md"


def archive_filename(source_filename: str, date_str: str) -> str:
    """아카이브 노트 파일명(`{filename}_{YYYY-MM-DD}.md`)을 생성한다.

    원본 파일명의 확장자는 제거하고 날짜를 덧붙인다.
    """
    _validate_date(date_str)
    stem = Path(source_filename).stem
    return f"{stem}_{date_str}.md"
