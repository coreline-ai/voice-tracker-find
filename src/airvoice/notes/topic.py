# @TASK P3-S2-T1 - 주제 노트 렌더링 (20-notes/{topic}.md 화면 계층)
# @SPEC docs/planning/06-tasks.md#P3-S2-T1
# @TEST tests/test_topic_note.py
"""주제 노트(20-notes/{topic}.md) 마크다운 렌더링 (frontmatter 포함).

주제 병합 로직(기존 노트 파싱, 날짜 섹션 삽입/멱등 병합, 관련 주제·tags·sources
누적)은 :mod:`airvoice.topics` 가 담당하고, 이 모듈은 병합이 끝난 데이터를
전달받아 마크다운 문자열로 렌더링하는 순수 함수만 제공한다 (P3-R2-T1이 하던
렌더링 코드를 이 모듈로 분리).

근거: specs/screens/topic-note.yaml data_requirements
(name, date, tags, sources, entries, related), docs/planning/04-database-design.md
§2 "주제 노트" (frontmatter에 tags/sources/related 포함).
"""

from __future__ import annotations

from dataclasses import dataclass

from airvoice.notes.renderer import render_frontmatter, render_wikilink

_RELATED_HEADING = "## 관련 주제"


@dataclass(frozen=True)
class TopicEntry:
    """주제 노트의 날짜별 누적 항목 (specs/domain/resources.yaml topics.entries)."""

    date: str
    items: list[str]


def render_topic_note(
    title: str,
    entries: list[TopicEntry],
    related: list[str],
    tags: list[str] | None = None,
    sources: list[str] | None = None,
) -> str:
    """주제 노트 마크다운 전체(frontmatter + 본문)를 렌더링한다.

    Args:
        title: 주제의 원래 표시 이름 (`# 제목`에 사용, 슬러그 아님).
        entries: 날짜별 누적 항목 목록. entries[0] 이 최신 날짜여야 하며,
            frontmatter의 date는 entries[0].date 로 채워진다.
        related: 관련 주제 슬러그 목록. frontmatter의 related와 본문
            "## 관련 주제" 위키링크 목록 양쪽에 반영된다.
        tags: frontmatter에 반영할 태그 목록 (예: "#design"). 없으면 생략.
        sources: frontmatter에 반영할 소스 문자열 목록
            (예: "file_1.m4a (2025-01-15)"). 없으면 생략.

    Returns:
        `---`로 감싼 frontmatter와 본문(제목, 날짜별 섹션, 관련 주제 섹션)을
        포함한 전체 마크다운 문자열.
    """
    frontmatter = render_frontmatter(
        {
            "type": "topic",
            "date": entries[0].date,
            "tags": tags or None,
            "sources": sources or None,
            "related": related or None,
        }
    )

    lines = [f"# {title}", ""]
    for entry in entries:
        lines.append(f"## {entry.date}")
        lines.extend(f"- {text}" for text in entry.items)
        lines.append("")
    lines.append(_RELATED_HEADING)
    lines.extend(f"- {render_wikilink(name)}" for name in related)

    body = "\n".join(lines).rstrip("\n") + "\n"
    return frontmatter + body
