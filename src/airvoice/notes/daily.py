# @TASK P3-S1-T1 - 데일리 노트 생성 (10-daily/YYYY-MM-DD.md)
# @SPEC docs/planning/06-tasks.md#P3-S1-T1
# @TEST tests/test_daily_note.py
"""당일 처리된 ExtractedItem 을 카테고리별로 묶어 데일리 노트를 생성한다.

근거: specs/screens/daily-note.yaml, docs/planning/04-database-design.md §2
"데일리 노트". 처리 통계(성공/실패 건수)는 db.get_daily_stats 반환 형태
(``{"success_count": int, "fail_count": int}``)를 그대로 받는다.
"""

from __future__ import annotations

from pathlib import Path

from airvoice.extract import ExtractedItem
from airvoice.notes.renderer import (
    daily_filename,
    daily_section_filename,
    daily_section_stem,
    normalize_filename,
    render_frontmatter,
    render_wikilink,
)

DAILY_SUBDIR = "10-daily"

_SECTION_TITLES: dict[str, str] = {
    "idea": "아이디어",
    "schedule": "일정",
    "important": "중요",
    "etc": "기타",
}
_SECTION_ORDER = ("idea", "schedule", "important", "etc")


def section_items(items: list[ExtractedItem], category: str) -> list[ExtractedItem]:
    """해당 구분의 항목만 추린다 (일정은 시각순 정렬)."""
    matching = [item for item in items if item.category == category]
    if category == "schedule":
        matching = sorted(
            matching, key=lambda item: (item.event_at is None, item.event_at)
        )
    return matching


def render_daily_section(
    date_str: str, category: str, items: list[ExtractedItem]
) -> str:
    """구분별 노트 1개(아이디어/일정/중요/기타)를 렌더링한다.

    H1 제목을 넣는다 — 볼트의 다른 노트와 형태를 맞추고, 앱 목록에서 파일명
    아래에 보여줄 제목이 생긴다(일일 노트 본체에는 H1 이 없었다).
    """
    title = _SECTION_TITLES[category]
    frontmatter = render_frontmatter(
        {"type": "daily_section", "date": date_str, "category": title}
    )
    lines = [f"# {date_str} {title}", ""]
    lines.extend(
        _render_item_line(item, prefix_time=category == "schedule") for item in items
    )
    return frontmatter + "\n" + "\n".join(lines) + "\n"


def _render_status_header(stats: dict[str, int]) -> str:
    """처리 통계를 헤더 한 줄로 렌더링한다 (성공/실패 건수)."""
    success = stats.get("success_count", 0)
    fail = stats.get("fail_count", 0)
    if fail == 0:
        return f"✅ {success}건 처리 / {fail}건 실패"
    return f"⚠️ {success}건 처리 / {fail}건 실패 (재시도 대기)"


def _format_event_time(event_at: str | None) -> str:
    """event_at(ISO `YYYY-MM-DDTHH:MM:SS`)를 `YYYY-MM-DD HH:MM` 으로 변환한다."""
    if not event_at:
        return ""
    date_part, _, time_part = event_at.partition("T")
    return f"{date_part} {time_part[:5]}"


def _render_item_line(item: ExtractedItem, *, prefix_time: bool = False) -> str:
    """항목 1건을 `- [[주제]]: 텍스트 #태그` 형식(주제 없으면 링크 생략)으로 렌더링한다.

    schedule 항목은 prefix_time=True 로 호출되어 앞에 event_at 시각이 붙는다.
    """
    body = item.text
    if item.topics:
        links = " ".join(
            render_wikilink(normalize_filename(topic)) for topic in item.topics
        )
        body = f"{links}: {body}"
    if item.tags:
        body = f"{body} {' '.join(item.tags)}"
    if prefix_time:
        time_str = _format_event_time(item.event_at)
        if time_str:
            body = f"{time_str} {body}"
    return f"- {body}"


def render_daily_note(
    date_str: str,
    items: list[ExtractedItem],
    stats: dict[str, int],
    sources: list[str],
) -> str | None:
    """데일리 노트 마크다운을 생성한다. 항목이 0건이면 None을 반환한다.

    Args:
        date_str: 데일리 노트 날짜 (YYYY-MM-DD).
        items: 당일 추출된 ExtractedItem 목록.
        stats: 처리 통계 (db.get_daily_stats 반환 형태:
            {"success_count": int, "fail_count": int}).
        sources: 소스 파일명 목록 (frontmatter.sources).

    Returns:
        렌더링된 마크다운 문자열. items 가 비어 있으면 None.
    """
    if not items:
        return None

    frontmatter = render_frontmatter(
        {"type": "daily", "date": date_str, "sources": sources}
    )

    body_parts = ["## 처리 결과", _render_status_header(stats)]
    topics: list[str] = []
    seen_topics: set[str] = set()
    section_links: list[str] = []

    for category in _SECTION_ORDER:
        matching = section_items(items, category)
        if not matching:
            continue

        stem = daily_section_stem(date_str, _SECTION_TITLES[category])
        section_links.append(f"- {render_wikilink(stem)} — {len(matching)}건")
        for item in matching:
            for topic in item.topics:
                if topic not in seen_topics:
                    seen_topics.add(topic)
                    topics.append(topic)

    if section_links:
        body_parts.append("")
        body_parts.append("## 구분")
        body_parts.append("\n".join(section_links))

    if topics:
        body_parts.append("")
        body_parts.append("## 참고")
        body_parts.append(
            " ".join(render_wikilink(normalize_filename(topic)) for topic in topics)
        )

    body = "\n".join(body_parts) + "\n"
    return frontmatter + "\n" + body


def write_daily_note(
    date_str: str,
    items: list[ExtractedItem],
    stats: dict[str, int],
    sources: list[str],
    vault_path: str | Path,
) -> Path | None:
    """데일리 노트를 볼트의 10-daily/ 폴더에 파일로 저장한다.

    Args:
        date_str: 데일리 노트 날짜 (YYYY-MM-DD).
        items: 당일 추출된 ExtractedItem 목록.
        stats: 처리 통계 (db.get_daily_stats 반환 형태).
        sources: 소스 파일명 목록.
        vault_path: Obsidian 볼트 루트 경로 (DI - config 를 import 하지 않음).

    Returns:
        생성된 노트 파일의 경로. items 가 비어 있으면 파일을 만들지 않고 None.
    """
    content = render_daily_note(date_str, items, stats, sources)
    if content is None:
        return None

    daily_dir = Path(vault_path).expanduser() / DAILY_SUBDIR
    daily_dir.mkdir(parents=True, exist_ok=True)

    # 구분별 노트. 항목이 없는 구분은 파일을 만들지 않고, 이전 실행에서 만들어졌다면
    # 지운다 — 안 그러면 빈 노트가 목록에 남는다. 대상은 이번 실행 날짜뿐이라
    # 지난 날짜의 노트는 건드리지 않는다.
    for category in _SECTION_ORDER:
        section_path = daily_dir / daily_section_filename(
            date_str, _SECTION_TITLES[category]
        )
        matching = section_items(items, category)
        if matching:
            section_path.write_text(
                render_daily_section(date_str, category, matching), encoding="utf-8"
            )
        elif section_path.exists():
            section_path.unlink()

    note_path = daily_dir / daily_filename(date_str)
    note_path.write_text(content, encoding="utf-8")
    return note_path
