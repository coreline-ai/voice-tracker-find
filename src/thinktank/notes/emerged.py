# @TASK P4-S3-T1 - 창발 노트 생성 (30-ideas/YYYY-MM-DD_idea_{n}.md)
# @SPEC docs/planning/06-tasks.md#P4-S3-T1
# @TEST tests/test_emerged_note.py
"""thinktank.emerge 가 만든 EmergedIdea 를 창발 노트 마크다운으로 렌더링하고
30-ideas/ 폴더에 저장한다.

근거: specs/screens/emerged-idea.yaml, docs/planning/04-database-design.md §2
"창발 노트" (frontmatter type/date/tags/sources + 통찰/구체적 사례/다음 스텝/근거
노트 섹션).
"""

from __future__ import annotations

from pathlib import Path

from thinktank.emerge import EmergedIdea
from thinktank.notes.renderer import (
    emerged_idea_filename,
    render_frontmatter,
    render_wikilink,
)

IDEAS_SUBDIR = "30-ideas"


def render_emerged_note(idea: EmergedIdea) -> str:
    """창발 아이디어 1건을 노트 마크다운 문자열로 렌더링한다.

    Args:
        idea: emerge.run_emerge 가 생성한 창발 아이디어.

    Returns:
        frontmatter(type/date/tags/sources) + `# 제목` + 통찰/구체적 사례/다음
        스텝/근거 노트 섹션으로 구성된 마크다운 문자열.
    """
    sources = [render_wikilink(ev.topic) for ev in idea.evidence]
    frontmatter = render_frontmatter(
        {
            "type": "emerged_idea",
            "date": idea.date,
            "tags": idea.tags or None,
            "sources": sources or None,
        }
    )

    lines = [f"# {idea.title}", "", "## 통찰", idea.insight]
    if idea.evidence:
        lines.append("")
        lines.append(" ".join(render_wikilink(ev.topic) for ev in idea.evidence))

    lines.append("")
    lines.append("## 구체적 사례")
    lines.extend(f"- {example}" for example in idea.examples)

    lines.append("")
    lines.append("## 다음 스텝")
    lines.extend(f"{i}. {step}" for i, step in enumerate(idea.next_steps, start=1))

    lines.append("")
    lines.append("## 근거 노트")
    lines.extend(
        f"- {render_wikilink(ev.topic)}: {ev.mention_count}개 항목"
        for ev in idea.evidence
    )

    body = "\n".join(lines).rstrip("\n") + "\n"
    return frontmatter + body


def _existing_sequences_for_date(ideas_dir: Path, date_str: str) -> list[int]:
    """ideas_dir 에 이미 저장된 date_str 창발 노트의 sequence 번호 목록."""
    prefix = f"{date_str}_idea_"
    sequences = []
    for path in ideas_dir.glob(f"{prefix}*.md"):
        suffix = path.stem[len(prefix) :]
        if suffix.isdigit():
            sequences.append(int(suffix))
    return sequences


def _find_matching_note(ideas_dir: Path, date_str: str, content: str) -> Path | None:
    """ideas_dir 에서 date_str 창발 노트 중 content 와 동일한 파일을 찾는다.

    동일 내용 재실행 멱등성(같은 아이디어로 다시 실행해도 중복 파일을 만들지
    않음)을 위해 sequence 위치와 무관하게 내용 일치 여부로 판단한다.
    """
    prefix = f"{date_str}_idea_"
    for path in sorted(ideas_dir.glob(f"{prefix}*.md")):
        if path.read_text(encoding="utf-8") == content:
            return path
    return None


def write_emerged_notes(ideas: list[EmergedIdea], vault_path: str | Path) -> list[Path]:
    """창발 아이디어 목록을 볼트의 30-ideas/ 폴더에 파일로 저장한다.

    같은 날짜에 이미 저장된 idea 파일이 있으면 그 파일들을 덮어쓰지 않고
    다음 sequence 번호부터 이어서 저장한다 (emerge.run_emerge 의 sequence는
    호출 단위로 1부터 시작하므로 파일명 결정에 그대로 쓰지 않는다). 동일
    내용의 아이디어가 이미 저장되어 있으면 새 파일을 만들지 않고 기존 파일
    경로를 재사용한다(재실행 멱등성).

    Args:
        ideas: 저장할 EmergedIdea 목록.
        vault_path: Obsidian 볼트 루트 경로 (DI - config 를 import 하지 않음).

    Returns:
        생성(또는 재사용)된 노트 파일 경로 목록 (ideas 순서 유지). ideas 가
        비어 있으면 폴더를 만들지 않고 빈 목록을 반환한다.
    """
    if not ideas:
        return []

    ideas_dir = Path(vault_path).expanduser() / IDEAS_SUBDIR
    ideas_dir.mkdir(parents=True, exist_ok=True)

    written: list[Path] = []
    next_seq_by_date: dict[str, int] = {}

    for idea in ideas:
        content = render_emerged_note(idea)
        date_str = idea.date

        existing_match = _find_matching_note(ideas_dir, date_str, content)
        if existing_match is not None:
            written.append(existing_match)
            continue

        if date_str not in next_seq_by_date:
            existing_sequences = _existing_sequences_for_date(ideas_dir, date_str)
            next_seq_by_date[date_str] = max(existing_sequences, default=0) + 1

        sequence = next_seq_by_date[date_str]
        note_path = ideas_dir / emerged_idea_filename(date_str, sequence)
        while note_path.exists():
            sequence += 1
            note_path = ideas_dir / emerged_idea_filename(date_str, sequence)

        note_path.write_text(content, encoding="utf-8")
        written.append(note_path)
        next_seq_by_date[date_str] = sequence + 1

    return written
