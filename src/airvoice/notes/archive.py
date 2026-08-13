# @TASK P2-S4-T1 - 전사 아카이브 노트 생성 (90-archive/{filename}_{YYYY-MM-DD}.md)
# @SPEC docs/planning/06-tasks.md#P2-S4-T1
# @TEST tests/test_archive_note.py
"""전사 완료 직후 원문 전체를 90-archive/ 에 마크다운 노트로 저장한다.

LLM 분류 오류를 검증할 때 데일리/주제 노트에서 원본 전사문으로 되돌아갈 수
있도록, 타임스탬프 세그먼트를 포함한 원문 전체를 보관한다 (7일 보관 후
cleanup.py 가 삭제). 근거: specs/screens/archive.yaml, specs/domain/resources.yaml
transcripts 리소스.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from airvoice.notes.renderer import archive_filename, render_frontmatter

ARCHIVE_SUBDIR = "90-archive"


@dataclass(frozen=True)
class Segment:
    """전사 세그먼트 1건 (specs/shared/types.yaml Segment)."""

    start: str
    end: str
    text: str


@dataclass(frozen=True)
class Transcript:
    """전사 원문 1건 (specs/domain/resources.yaml transcripts 리소스).

    P2-R1-T2(transcribe_audio)가 반환해야 하는 데이터 형태이기도 하다.
    """

    source_file: str
    date: str
    recorded_at: str
    file_size: int
    segments: list[Segment]


def _render_frontmatter(transcript: Transcript) -> str:
    """archive 타입 frontmatter 를 생성한다.

    render_frontmatter 는 type/date/tags/sources/related 만 지원하므로,
    아카이브 전용 필드(source_file/recorded_at/file_size)는 마지막 '---'
    앞에 추가로 삽입한다.
    """
    base = render_frontmatter({"type": "archive", "date": transcript.date})
    lines = base.splitlines()
    extra = [
        f"source_file: {transcript.source_file}",
        f"recorded_at: {transcript.recorded_at}",
        f"file_size: {transcript.file_size}",
    ]
    lines = lines[:-1] + extra + [lines[-1]]
    return "\n".join(lines) + "\n"


def _render_segment(segment: Segment) -> str:
    """세그먼트 1건을 `[start-end]\\n텍스트` 블록으로 렌더링한다."""
    return f"[{segment.start}-{segment.end}]\n{segment.text}"


def render_archive_note(transcript: Transcript) -> str:
    """전사 결과를 아카이브 노트 마크다운 문자열로 렌더링한다.

    Args:
        transcript: 전사 원문 데이터 (source_file/date/recorded_at/file_size/segments).

    Returns:
        frontmatter + `# 전사 원본` 제목 + 세그먼트 블록으로 구성된 마크다운 텍스트.
    """
    body_lines = ["# 전사 원본", ""]
    for segment in transcript.segments:
        body_lines.append(_render_segment(segment))
        body_lines.append("")
    body = "\n".join(body_lines).rstrip("\n") + "\n"

    return _render_frontmatter(transcript) + body


def write_archive_note(transcript: Transcript, vault_path: str | Path) -> Path:
    """아카이브 노트를 볼트의 90-archive/ 폴더에 파일로 저장한다.

    Args:
        transcript: 전사 원문 데이터.
        vault_path: Obsidian 볼트 루트 경로 (DI - config 를 import 하지 않음).

    Returns:
        생성된 노트 파일의 경로.
    """
    archive_dir = Path(vault_path).expanduser() / ARCHIVE_SUBDIR
    archive_dir.mkdir(parents=True, exist_ok=True)

    note_path = archive_dir / archive_filename(transcript.source_file, transcript.date)
    note_path.write_text(render_archive_note(transcript), encoding="utf-8")
    return note_path
