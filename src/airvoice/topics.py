# @TASK P3-R2-T1 - 주제 노트 병합 (20-notes/{topic}.md 생성/누적)
# @SPEC docs/planning/06-tasks.md#P3-R2-T1
# @TEST tests/test_topics.py
"""추출된 항목(ExtractedItem)을 주제별로 묶어 20-notes/*.md 노트에 병합한다.

주제가 처음 등장하면 새 노트를 생성하고, 재등장하면 기존 노트를 파싱해 새
날짜 섹션을 본문 최상단(기존 날짜 섹션들 위)에 삽입한다. 같은 날짜로 재실행해도
텍스트 기준으로 멱등 병합되어 항목이 중복 추가되지 않는다. 같은 항목에 함께
등장한 다른 주제는 "## 관련 주제" 섹션에 위키링크로 추가된다 (각 주제를
개별적으로 처리하므로 양방향 백링크가 자연히 성립한다). 항목의 tags와 호출자가
전달한 sources도 기존 노트 값과 중복 없이 누적 병합된다.

마크다운 렌더링(frontmatter + 본문)은 :mod:`airvoice.notes.topic` 이 담당하고,
이 모듈은 기존 노트 파싱과 병합 로직만 담당한다 (P3-S2-T1에서 분리).

근거: specs/domain/resources.yaml topics 리소스, specs/screens/topic-note.yaml,
docs/planning/04-database-design.md §2 "주제 노트".
"""

from __future__ import annotations

import re
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

from airvoice.extract import ExtractedItem
from airvoice.notes.renderer import normalize_filename
from airvoice.notes.topic import TopicEntry, render_topic_note

TOPICS_SUBDIR = "20-notes"

_DATE_HEADING_RE = re.compile(r"^## (\d{4}-\d{2}-\d{2})$")
_RELATED_HEADING = "## 관련 주제"
_WIKILINK_RE = re.compile(r"\[\[(.+?)\]\]")
_FRONTMATTER_LIST_RE_TEMPLATE = r"^{key}: \[(.*)\]$"


@dataclass(frozen=True)
class Topic:
    """merge_topics 가 반환하는 주제 노트 1건 (specs/domain/resources.yaml topics)."""

    name: str
    date: str
    entries: list[TopicEntry]
    related: list[str]
    tags: list[str]
    sources: list[str]


def _parse_frontmatter_list(content: str, key: str) -> list[str]:
    """frontmatter YAML 블록에서 `key: [a, b]` 형식 필드를 파싱한다.

    노트가 frontmatter로 시작하지 않거나 필드가 없으면 빈 리스트를 반환한다.
    """
    if not content.startswith("---"):
        return []
    end = content.find("\n---", 3)
    if end == -1:
        return []
    frontmatter = content[3:end]
    pattern = re.compile(_FRONTMATTER_LIST_RE_TEMPLATE.format(key=key), re.MULTILINE)
    match = pattern.search(frontmatter)
    if not match or not match.group(1).strip():
        return []
    return [item.strip() for item in match.group(1).split(",")]


def _parse_topic_note(
    content: str,
) -> tuple[str, list[TopicEntry], list[str], list[str], list[str]]:
    """기존 주제 노트 텍스트를 (제목, 날짜별 항목, 관련 주제, tags, sources)로 파싱."""
    tags = _parse_frontmatter_list(content, "tags")
    sources = _parse_frontmatter_list(content, "sources")

    body = content
    if content.startswith("---"):
        end = content.find("\n---", 3)
        if end != -1:
            newline_after = content.find("\n", end + 4)
            body = content[newline_after + 1 :] if newline_after != -1 else ""

    title = ""
    entries: list[TopicEntry] = []
    related: list[str] = []
    current_date: str | None = None
    current_items: list[str] | None = None
    in_related = False

    for line in body.splitlines():
        if line.startswith("# ") and not line.startswith("## "):
            title = line[2:].strip()
            continue

        date_match = _DATE_HEADING_RE.match(line)
        if date_match:
            if current_date is not None:
                entries.append(TopicEntry(date=current_date, items=current_items or []))
            current_date, current_items, in_related = date_match.group(1), [], False
            continue

        if line.strip() == _RELATED_HEADING:
            if current_date is not None:
                entries.append(TopicEntry(date=current_date, items=current_items or []))
                current_date, current_items = None, None
            in_related = True
            continue

        if in_related:
            link_match = _WIKILINK_RE.search(line)
            if link_match:
                related.append(link_match.group(1))
            continue

        if current_items is not None and line.startswith("- "):
            current_items.append(line[2:])

    if current_date is not None:
        entries.append(TopicEntry(date=current_date, items=current_items or []))

    return title, entries, related, tags, sources


def _merge_entries(
    entries: list[TopicEntry], date: str, new_texts: list[str]
) -> list[TopicEntry]:
    """새 날짜 섹션을 최상단에 삽입하되, 같은 날짜면 텍스트 기준으로 멱등 병합한다."""
    if entries and entries[0].date == date:
        merged_items = list(entries[0].items)
        for text in new_texts:
            if text not in merged_items:
                merged_items.append(text)
        return [TopicEntry(date=date, items=merged_items), *entries[1:]]
    return [TopicEntry(date=date, items=new_texts), *entries]


def _merge_unique(existing: list[str], new: list[str]) -> list[str]:
    """기존 목록을 그대로 유지하며 새 항목을 중복 없이 뒤에 추가한다."""
    merged = list(existing)
    seen = set(existing)
    for value in new:
        if value not in seen:
            seen.add(value)
            merged.append(value)
    return merged


def merge_topics(
    new_items: list[ExtractedItem],
    vault_path: str | Path,
    date: str,
    sources: list[str] | None = None,
) -> list[Topic]:
    """추출 항목을 item.topics 기준으로 그룹핑해 주제 노트에 병합한다.

    주제가 처음 등장하면 20-notes/{slug}.md를 새로 만들고, 이미 있으면 파싱한
    뒤 새 날짜 섹션을 본문 최상단에 삽입한다 (같은 날짜로 재실행해도 텍스트
    기준 멱등 병합). 같은 항목에 함께 등장한 다른 주제는 서로의 "## 관련 주제"
    섹션에 위키링크로 추가된다 (각 주제를 개별 처리하므로 상호 링크가 자연히
    성립한다). 항목들의 tags는 주제별로 집계되어 기존 노트의 tags와 중복 없이
    병합되고, sources는 호출자가 전달한 배치 소스 목록이 기존 노트의 sources와
    중복 없이 병합된다.

    Args:
        new_items: 이번 배치에서 추출된 항목 목록 (topics 가 비어 있는 항목은
            무시된다).
        vault_path: Obsidian 볼트 루트 경로 (DI - config 를 import 하지 않음).
        date: 이 배치가 속한 날짜 (YYYY-MM-DD). ExtractedItem 자체는 날짜를
            담지 않으므로 호출자가 명시적으로 전달한다.
        sources: 이 배치가 유래한 소스 문자열 목록 (예: "file_1.m4a
            (2025-01-15)"). 배치에서 새 항목을 만든 모든 주제 노트에 동일하게
            반영되며, 기존 노트의 sources와 중복 없이 병합된다.

    Returns:
        이번 호출에서 병합된 주제들의 Topic 목록 (배치에 처음 등장한 순서).
    """
    groups: dict[str, list[ExtractedItem]] = defaultdict(list)
    display_names: dict[str, str] = {}
    for item in new_items:
        for topic_name in item.topics:
            slug = normalize_filename(topic_name)
            if not slug:
                continue
            groups[slug].append(item)
            display_names.setdefault(slug, topic_name)

    if not groups:
        return []

    topics_dir = Path(vault_path).expanduser() / TOPICS_SUBDIR
    topics_dir.mkdir(parents=True, exist_ok=True)

    batch_sources = sources or []

    results: list[Topic] = []
    for slug, items in groups.items():
        note_path = topics_dir / f"{slug}.md"

        new_texts: list[str] = []
        seen_texts: set[str] = set()
        new_related: list[str] = []
        seen_related: set[str] = set()
        new_tags: list[str] = []
        seen_tags: set[str] = set()
        for item in items:
            if item.text not in seen_texts:
                seen_texts.add(item.text)
                new_texts.append(item.text)
            for other in item.topics:
                other_slug = normalize_filename(other)
                if not other_slug or other_slug == slug or other_slug in seen_related:
                    continue
                seen_related.add(other_slug)
                new_related.append(other_slug)
            for tag in item.tags:
                if tag not in seen_tags:
                    seen_tags.add(tag)
                    new_tags.append(tag)

        if note_path.exists():
            title, entries, related, tags, existing_sources = _parse_topic_note(
                note_path.read_text(encoding="utf-8")
            )
        else:
            title, entries, related, tags, existing_sources = (
                display_names[slug],
                [],
                [],
                [],
                [],
            )

        entries = _merge_entries(entries, date, new_texts)
        related = _merge_unique(related, new_related)
        tags = _merge_unique(tags, new_tags)
        merged_sources = _merge_unique(existing_sources, batch_sources)

        note_path.write_text(
            render_topic_note(title, entries, related, tags, merged_sources),
            encoding="utf-8",
        )
        results.append(
            Topic(
                name=title,
                date=entries[0].date,
                entries=entries,
                related=related,
                tags=tags,
                sources=merged_sources,
            )
        )

    return results
