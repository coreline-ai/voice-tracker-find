"""전사 완료 녹음마다 남기는 사실 기반 메모 노트.

LLM 추출 결과가 비어도 전사 원본은 사용자가 다시 확인할 가치가 있다. 이 모듈은
`30-ideas`에 녹음별로 정확히 한 건의 메모를 남기되, 근거 없는 아이디어나 할 일을
만들지 않고 전사 발췌와 원문 링크만 기록한다.
"""

from __future__ import annotations

from pathlib import Path

from thinktank.extract import ExtractedItem
from thinktank.notes.archive import Transcript
from thinktank.notes.emerged import IDEAS_SUBDIR
from thinktank.notes.renderer import (
    archive_filename,
    recording_memo_filename,
    render_frontmatter,
    render_wikilink,
)


def _archive_stem(transcript: Transcript) -> str:
    return archive_filename(transcript.source_file, transcript.date).removesuffix(".md")


def _archive_link(transcript: Transcript) -> str:
    return f"[[{_archive_stem(transcript)}|원문 전사 보기]]"


def render_recording_memo(transcript: Transcript, items: list[ExtractedItem]) -> str:
    """전사 발췌와 원문 링크를 담은 녹음별 메모를 렌더링한다."""
    archive_stem = _archive_stem(transcript)
    frontmatter = render_frontmatter(
        {
            "type": "recording_memo",
            "date": transcript.date,
            "tags": ["#음성메모"],
            "sources": [render_wikilink(archive_stem)],
        }
    )

    lines = ["# 음성 메모", "", "## 전사 발췌"]
    excerpts = [segment for segment in transcript.segments if segment.text.strip()][:3]
    if excerpts:
        lines.extend(
            f"- [{segment.start}-{segment.end}] {segment.text.strip()}"
            for segment in excerpts
        )
    else:
        lines.append("- 전사 내용이 비어 있습니다.")

    lines.extend(["", "## 분류 결과"])
    if items:
        lines.extend(f"- {item.text}" for item in items)
    else:
        lines.append("- 자동 추출 항목 없음 · 원문 전사를 확인해 주세요.")

    lines.extend(["", "## 원문 전사", _archive_link(transcript)])
    return frontmatter + "\n".join(lines).rstrip("\n") + "\n"


def write_recording_memo(
    transcript: Transcript,
    items: list[ExtractedItem],
    vault_path: str | Path,
    *,
    overwrite: bool = False,
) -> Path:
    """녹음 파일명 기준의 결정적 경로에 메모를 저장한다.

    같은 녹음이 organize 단계에서 재시도돼도 같은 파일을 재사용하므로 중복 파일이
    생기지 않고, 사용자가 수정한 기존 메모도 덮어쓰지 않는다.
    """
    ideas_dir = Path(vault_path).expanduser() / IDEAS_SUBDIR
    ideas_dir.mkdir(parents=True, exist_ok=True)
    note_path = ideas_dir / recording_memo_filename(
        transcript.source_file, transcript.date
    )
    if note_path.exists() and not overwrite:
        return note_path
    note_path.write_text(render_recording_memo(transcript, items), encoding="utf-8")
    return note_path
