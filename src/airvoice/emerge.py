# @TASK P4-R3-T1 - 창발 아이디어 엔진
# @SPEC docs/planning/06-tasks.md#P4-R3-T1
# @TEST tests/test_emerge.py
"""20-notes/ 에 누적된 주제 노트를 LLM으로 조합해 신규(창발) 아이디어를 만든다.

anthropic SDK는 이 모듈 최상단에서 import하지 않는다. 실 API 호출
(:func:`load_emerger`)만 함수 본문 안에서 지연 import하며, 단위 테스트는 이를
대체하는 fake 함수를 :func:`run_emerge` 에 주입해 anthropic 설치나 API 키 없이
통과한다. 실 API 검증은 P5 E2E 테스트에서 수행한다.

프라이버시: :func:`scan_topic_notes` 는 주제 노트의 frontmatter(sources 등
파일 경로 정보 포함)를 제거하고 본문(제목·날짜별 항목·관련 주제)만 반환하므로,
Claude API에는 주제명과 항목 텍스트만 전송된다 (04-database-design.md,
specs/domain/resources.yaml emerged_ideas 참조).

30일 주기(또는 수동 트리거) 판단은 이 모듈 밖(배치/스케줄러)의 책임이며, 이
모듈은 한 번의 창발 실행 함수(:func:`run_emerge`)만 제공한다.
"""

from __future__ import annotations

import json
import re
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from airvoice.extract import _strip_code_fence
from airvoice.notes.renderer import normalize_filename
from airvoice.topics import TOPICS_SUBDIR

DEFAULT_EMERGE_MODEL = "claude-sonnet-5"
DEFAULT_EMERGE_MAX_RETRIES = 3
# 창발은 누적된 주제 노트 전체를 한 프롬프트로 합쳐 조합하므로(주제가 수백 개면
# 프롬프트가 MB급) 추출(파일 1건)보다 훨씬 오래 걸린다. 하루치 실데이터(주제
# ~440개)에서도 120초로는 부족해 타임아웃이 나므로 넉넉히 잡는다.
DEFAULT_EMERGE_TIMEOUT = 600.0  # seconds
DEFAULT_MIN_TOPICS = 3

_RELATED_HEADING = "## 관련 주제"
_IDEAS_SUBDIR = "30-ideas"
# 창발 노트 파일명: YYYY-MM-DD_idea_{n}.md
_IDEA_FILENAME_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})_idea_\d+\.md$")
# 주제 노트의 날짜 섹션 헤딩: "## YYYY-MM-DD"
_DATE_HEADING_RE = re.compile(r"^## (\d{4}-\d{2}-\d{2})\s*$")

# 증분 창발: 누적 주제 노트 전체가 아니라, 이미 도출된 아이디어(압축본)와
# 마지막 창발 이후 새로 추가된 주제 항목만 연결한다 → 입력 크기가 코퍼스
# 누적과 무관하게 일정하게 유지된다.
_EMERGE_PROMPT = """지금까지 도출된 창발 아이디어입니다(이미 정리된 결과):
{prior_ideas}

아래는 그 이후 새로 추가된 주제별 메모(주제 이름과 그 아래 쌓인 항목들)입니다:
{topics}

새로 추가된 내용을 기존 아이디어와 연결하거나, 새 조합에서 나타나는 신규 \
아이디어를 찾아 JSON 배열로만 응답하세요.

각 아이디어는 다음 필드를 가집니다:
- title: 아이디어 제목
- insight: 왜 이 조합이 의미있는가 (통찰)
- examples: 구체적 사례 목록 (문자열 배열)
- next_steps: 실행 액션 아이템 목록 (문자열 배열)
- evidence: 근거가 된 주제와 언급 수 목록. 각 원소는 {{"topic": 주제명, \
"mention_count": 정수}} 형식

다른 설명 없이 JSON 배열만 응답하세요.
"""


@dataclass(frozen=True)
class Evidence:
    """창발 아이디어 근거가 된 주제 1건 (specs/domain/resources.yaml
    emerged_ideas.evidence)."""

    topic: str
    mention_count: int


@dataclass(frozen=True)
class EmergedIdea:
    """LLM이 주제 조합으로 제안한 신규 아이디어 1건 (specs/domain/resources.yaml
    emerged_ideas).

    창발 노트 생성(P4-S3-T1)이 이 계약을 그대로 소비한다.
    """

    title: str
    date: str
    sequence: int
    tags: list[str]
    insight: str
    examples: list[str]
    next_steps: list[str]
    evidence: list[Evidence]


# (기존 아이디어 압축 텍스트, 신규 주제 항목 dict) -> 원시 아이디어 dict 목록
EmergeFn = Callable[[str, dict[str, str]], list[dict[str, Any]]]


def _strip_frontmatter(content: str) -> str:
    """콘텐츠 앞의 YAML frontmatter(`---` 블록)를 제거하고 본문만 반환한다."""
    if not content.startswith("---"):
        return content
    end = content.find("\n---", 3)
    if end == -1:
        return content
    newline_after = content.find("\n", end + 4)
    return content[newline_after + 1 :] if newline_after != -1 else ""


def scan_topic_notes(vault_path: str | Path) -> dict[str, str]:
    """20-notes/*.md 파일을 읽어 {slug: 본문} 딕셔너리로 수집한다.

    frontmatter(sources 등 파일 경로 정보 포함)는 제거하고 본문(제목, 날짜별
    항목, 관련 주제)만 반환한다.

    Args:
        vault_path: Obsidian 볼트 루트 경로.

    Returns:
        {slug: 본문 마크다운 텍스트} 딕셔너리 (파일명 오름차순). 20-notes
        디렉터리가 없으면 빈 딕셔너리.
    """
    notes_dir = Path(vault_path).expanduser() / TOPICS_SUBDIR
    if not notes_dir.is_dir():
        return {}
    return {
        md_file.stem: _strip_frontmatter(md_file.read_text(encoding="utf-8"))
        for md_file in sorted(notes_dir.glob("*.md"))
    }


def count_topic_items(body: str) -> int:
    """주제 노트 본문에서 날짜 섹션에 누적된 항목(불릿) 개수를 센다.

    "## 관련 주제" 섹션의 위키링크 불릿은 항목으로 세지 않는다.

    Args:
        body: :func:`scan_topic_notes` 가 반환한 주제 노트 본문 텍스트.

    Returns:
        날짜 섹션 아래 불릿(`- `로 시작하는 줄) 개수.
    """
    count = 0
    in_related = False
    for line in body.splitlines():
        if line.strip() == _RELATED_HEADING:
            in_related = True
            continue
        if in_related:
            continue
        if line.startswith("- "):
            count += 1
    return count


def _format_topics_for_prompt(topics: dict[str, str]) -> str:
    """스캔된 주제 노트를 프롬프트용 텍스트(주제명 + 항목 수 + 본문)로 변환한다."""
    return "\n\n".join(
        f"### {slug} ({count_topic_items(body)}개 항목)\n{body}"
        for slug, body in topics.items()
    )


def last_emerge_date(vault_path: str | Path) -> str | None:
    """30-ideas/ 창발 노트 파일명 중 가장 최근 날짜(YYYY-MM-DD)를 반환한다.

    창발 노트가 하나도 없으면 None 을 반환한다(첫 창발 → 전체 주제가 신규).
    """
    ideas_dir = Path(vault_path).expanduser() / _IDEAS_SUBDIR
    if not ideas_dir.is_dir():
        return None
    dates = [
        match.group(1)
        for path in ideas_dir.glob("*.md")
        if (match := _IDEA_FILENAME_RE.match(path.name))
    ]
    return max(dates) if dates else None


def filter_new_entries(body: str, since_date: str | None) -> str:
    """주제 노트 본문에서 since_date 이후의 날짜 섹션만 남긴다(제목 유지).

    `## YYYY-MM-DD` 섹션 중 날짜가 since_date 보다 큰 것만 남기고, "## 관련
    주제" 등 그 외 섹션은 제거한다. since_date 가 None 이면(첫 창발) 본문을
    그대로 반환한다. 남은 날짜 섹션이 없으면 제목만 남는다(호출자가 거른다).

    Args:
        body: :func:`scan_topic_notes` 가 반환한 주제 노트 본문.
        since_date: 이 날짜(포함)까지는 이미 창발에 반영됨 — 이후 것만 신규.

    Returns:
        제목 + since_date 이후 날짜 섹션만 남긴 본문.
    """
    if since_date is None:
        return body

    kept: list[str] = []
    keeping = False
    for line in body.splitlines():
        if line.startswith("# ") and not line.startswith("## "):
            kept.append(line)  # 제목은 항상 유지
            keeping = False
            continue
        if line.startswith("## "):
            heading = _DATE_HEADING_RE.match(line)
            keeping = bool(heading) and heading.group(1) > since_date
            if keeping:
                kept.append(line)
            continue
        if keeping:
            kept.append(line)
    return "\n".join(kept)


def scan_new_topic_entries(
    vault_path: str | Path, since_date: str | None
) -> dict[str, str]:
    """since_date 이후 새 항목이 추가된 주제만 {slug: 신규 항목 본문}으로 모은다.

    since_date 가 None 이면(첫 창발) :func:`scan_topic_notes` 전체를 반환한다.
    신규 항목이 없는 주제는 제외한다.
    """
    topics = scan_topic_notes(vault_path)
    if since_date is None:
        return topics
    result: dict[str, str] = {}
    for slug, body in topics.items():
        filtered = filter_new_entries(body, since_date)
        if count_topic_items(filtered) > 0:
            result[slug] = filtered
    return result


def _parse_idea_note(content: str) -> tuple[str, str]:
    """창발 노트 마크다운에서 제목(`# ...`)과 통찰(`## 통찰` 아래 본문)을 뽑는다."""
    title = ""
    insight_lines: list[str] = []
    in_insight = False
    for line in content.splitlines():
        if not title and line.startswith("# ") and not line.startswith("## "):
            title = line[2:].strip()
            continue
        if line.strip() == "## 통찰":
            in_insight = True
            continue
        if in_insight:
            stripped = line.strip()
            if not stripped or stripped.startswith("## ") or stripped.startswith("[["):
                in_insight = False
                continue
            insight_lines.append(stripped)
    return title, " ".join(insight_lines)


def scan_prior_ideas(vault_path: str | Path) -> str:
    """30-ideas/ 기존 창발 아이디어를 '제목: 통찰' 압축 텍스트로 반환한다.

    누적 주제 노트 전체 대신 이미 도출된 아이디어(압축본)를 컨텍스트로 넘겨
    입력 크기를 일정하게 유지한다. 창발 노트가 없으면 빈 문자열.
    """
    ideas_dir = Path(vault_path).expanduser() / _IDEAS_SUBDIR
    if not ideas_dir.is_dir():
        return ""
    blocks: list[str] = []
    for path in sorted(ideas_dir.glob("*.md")):
        if not _IDEA_FILENAME_RE.match(path.name):
            continue
        title, insight = _parse_idea_note(path.read_text(encoding="utf-8"))
        if title:
            blocks.append(f"- {title}: {insight}" if insight else f"- {title}")
    return "\n".join(blocks)


def parse_emerge_response(raw_text: str) -> list[dict[str, Any]]:
    """Claude 응답 텍스트를 방어적으로 파싱해 원시 아이디어 dict 목록으로 변환한다.

    extract.parse_extraction_response 와 동일한 방어 파싱 전략(코드펜스 제거,
    첫 `[` ~ 마지막 `]` 구간 재시도)을 재사용한다.

    Args:
        raw_text: Claude API가 반환한 원본 텍스트.

    Returns:
        파싱된 원시 아이디어 dict 목록 (title/insight/examples/next_steps/evidence).

    Raises:
        ValueError: 텍스트에서 유효한 JSON 배열을 찾을 수 없을 때.
    """
    cleaned = _strip_code_fence(raw_text)
    try:
        raw_items = json.loads(cleaned)
    except json.JSONDecodeError:
        start = cleaned.find("[")
        end = cleaned.rfind("]")
        if start == -1 or end == -1 or end < start:
            raise ValueError(
                f"LLM 응답에서 JSON 배열을 찾을 수 없습니다: {raw_text!r}"
            ) from None
        raw_items = json.loads(cleaned[start : end + 1])

    if not isinstance(raw_items, list):
        raise ValueError("LLM 응답이 JSON 배열이 아닙니다.")
    return raw_items


def load_emerger(
    api_key: str,
    model: str = DEFAULT_EMERGE_MODEL,
    max_retries: int = DEFAULT_EMERGE_MAX_RETRIES,
    timeout: float = DEFAULT_EMERGE_TIMEOUT,
) -> EmergeFn:
    """Claude API를 호출해 '스캔된 주제 노트 -> 원시 아이디어 dict 목록' 함수를 만든다.

    anthropic SDK를 이 함수 안에서만 import하므로, 이 함수를 호출하지 않는 한
    (예: 단위 테스트에서 fake emerge_fn을 주입하는 경우) anthropic 설치가
    필요 없다. 주제명과 본문 텍스트만 전송하고 sources/파일 경로는 전송하지
    않는다 (scan_topic_notes가 frontmatter를 제거하므로 자동으로 보장된다).

    Args:
        api_key: Claude API 키.
        model: 사용할 모델 ID (기본: claude-sonnet-5).
        max_retries: API 호출 실패 시 SDK 차원의 재시도 횟수.
        timeout: 요청 타임아웃 (초).

    Returns:
        스캔된 주제 노트 dict(slug -> 본문)를 받아 원시 아이디어 dict 목록을
        반환하는 함수.
    """
    import anthropic

    client = anthropic.Anthropic(
        api_key=api_key, max_retries=max_retries, timeout=timeout
    )

    def _emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict[str, Any]]:
        prompt = _EMERGE_PROMPT.format(
            prior_ideas=prior_ideas or "(아직 없음 — 첫 창발)",
            topics=_format_topics_for_prompt(topics),
        )
        message = client.messages.create(
            model=model,
            max_tokens=4096,
            messages=[{"role": "user", "content": prompt}],
        )
        response_text = "".join(
            block.text for block in message.content if hasattr(block, "text")
        )
        return parse_emerge_response(response_text)

    return _emerge


def load_cli_emerger(
    model: str | None = None, timeout: float = DEFAULT_EMERGE_TIMEOUT
) -> EmergeFn:
    """로컬 claude CLI로 '스캔된 주제 노트 -> 원시 아이디어 dict 목록' 함수를 만든다.

    구독 로그인(OAuth)으로 인증된 로컬 claude CLI(`claude -p`)를 호출하므로
    CLAUDE_API_KEY 가 필요 없다.

    Args:
        model: 사용할 모델 ID. None 이면 claude CLI 기본 모델을 사용한다.
        timeout: CLI 프로세스 실행 타임아웃 (초).

    Returns:
        스캔된 주제 노트 dict(slug -> 본문)를 받아 원시 아이디어 dict 목록을
        반환하는 함수.
    """
    from airvoice.claude_cli import run_claude_cli

    def _emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict[str, Any]]:
        prompt = _EMERGE_PROMPT.format(
            prior_ideas=prior_ideas or "(아직 없음 — 첫 창발)",
            topics=_format_topics_for_prompt(topics),
        )
        response = run_claude_cli(prompt, model=model, timeout=timeout)
        return parse_emerge_response(response)

    return _emerge


def run_emerge(
    vault_path: str | Path,
    emerge_fn: EmergeFn,
    date: str,
    min_topics: int = DEFAULT_MIN_TOPICS,
) -> list[EmergedIdea]:
    """증분 방식으로 창발 아이디어를 생성한다(기존 아이디어 + 신규 주제만).

    누적 주제 노트 전체를 매번 다시 넣지 않고, 마지막 창발
    (:func:`last_emerge_date`) 이후 새로 추가된 주제 항목만
    (:func:`scan_new_topic_entries`) 스캔하고, 이미 도출된 창발 아이디어의
    압축본(:func:`scan_prior_ideas`)을 함께 emerge_fn 에 넘긴다. 이렇게 하면
    입력 크기가 코퍼스 누적과 무관하게 일정하게 유지된다.

    신규 주제 수가 min_topics 미만이면 조합할 신규 재료가 부족하다고 보고
    emerge_fn 을 호출하지 않고 빈 목록을 반환한다(같은 날 재실행 시 신규 항목이
    없어 자동으로 빈 목록 → 중복 방지). 충분하면 각 아이디어에 date와 순번
    (sequence, 1부터)을 부여하고 evidence 주제명을 정규화(normalize_filename)해
    20-notes 슬러그와 맞추며 이를 태그로도 쓴다.

    실행 주기(매일/수동 트리거) 판단은 이 함수 밖(배치/스케줄러)의 책임이다.

    Args:
        vault_path: Obsidian 볼트 루트 경로.
        emerge_fn: (기존 아이디어 압축 텍스트, 신규 주제 dict[slug, 본문])를
            받아 원시 아이디어 dict 목록을 반환하는 함수 (실 API는 load_emerger,
            테스트는 fake 주입).
        date: 이 창발 실행에 부여할 날짜 (YYYY-MM-DD).
        min_topics: 창발을 시도하기 위한 최소 신규 주제 수 (기본 3).

    Returns:
        생성된 EmergedIdea 목록 (date 내 1부터 순번). 신규 주제가 부족하면
        빈 목록.
    """
    since = last_emerge_date(vault_path)
    new_topics = scan_new_topic_entries(vault_path, since)
    if len(new_topics) < min_topics:
        return []

    prior_ideas = scan_prior_ideas(vault_path)
    raw_ideas = emerge_fn(prior_ideas, new_topics)

    ideas: list[EmergedIdea] = []
    for sequence, raw in enumerate(raw_ideas, start=1):
        evidence = [
            Evidence(
                topic=normalize_filename(str(e.get("topic", ""))),
                mention_count=int(e.get("mention_count") or 0),
            )
            for e in (raw.get("evidence") or [])
        ]
        tags = [f"#{ev.topic}" for ev in evidence if ev.topic]
        ideas.append(
            EmergedIdea(
                title=str(raw.get("title", "")),
                date=date,
                sequence=sequence,
                tags=tags,
                insight=str(raw.get("insight", "")),
                examples=list(raw.get("examples") or []),
                next_steps=list(raw.get("next_steps") or []),
                evidence=evidence,
            )
        )
    return ideas
