# @TASK P3-R1-T1 - LLM 추출 (Claude API)
# @SPEC docs/planning/06-tasks.md#P3-R1-T1
# @TEST tests/test_extract.py
"""transcribed 상태 전사문에서 Claude API로 항목(아이디어/일정/중요/기타)을 추출한다.

anthropic SDK는 이 모듈 최상단에서 import하지 않는다. 실 API 호출
(:func:`load_extractor`)만 함수 본문 안에서 지연 import하며, 단위 테스트는 이를
대체하는 fake 함수를 :func:`extract_items`/:func:`run_extract_batch` 에 주입해
anthropic 설치나 API 키 없이 통과한다. 실 API 검증은 P5 E2E 테스트에서 수행한다.

프라이버시: Claude API에는 전사 텍스트만 전송하고 오디오/파일 경로는 전송하지
않는다 (04-database-design.md, specs/domain/resources.yaml extracted_items 참조).

장시간 전사문은 :func:`chunk_transcript_text` 로 문자수 기준 청크 분할 후
청크별 추출 결과를 병합하고, 실패 재시도는 extracted_failed 상태 +
get_retry_targets 를 통한 다음 배치 재처리로 처리한다 (상태 전이도는
04-database-design.md §1 참조).
"""

from __future__ import annotations

import json
import logging
from collections.abc import Callable
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path

from airvoice.db import (
    Recording,
    Status,
    get_recordings,
    get_retry_targets,
    update_extracted_json,
    update_recording_status,
)

logger = logging.getLogger(__name__)

_TIMESTAMP_FORMAT = "%Y-%m-%d %H:%M:%S"

# recordings.status 갱신에 쓰이는 타임스탬프 포맷과 동일 (transcribe.py 참조)

DEFAULT_CHUNK_CHARS = 6000
DEFAULT_EXTRACT_MODEL = "claude-sonnet-5"
DEFAULT_EXTRACT_MAX_RETRIES = 3
DEFAULT_EXTRACT_TIMEOUT = 120.0  # seconds

_VALID_CATEGORIES = frozenset({"idea", "schedule", "important", "etc"})

_EXTRACTION_PROMPT = """다음은 음성 녹음의 전사문입니다. 이 텍스트에서 아이디어, \
일정, 중요 사항, 기타로 분류할 수 있는 항목을 추출해 JSON 배열로만 응답하세요.

각 항목은 다음 필드를 가집니다:
- category: "idea" | "schedule" | "important" | "etc" 중 하나
- text: 항목을 한 줄로 요약한 텍스트
- topics: 연결할 주제 이름 목록 (문자열 배열)
- tags: "#태그" 형식의 태그 목록 (문자열 배열)
- event_at: category가 "schedule"일 때만 "YYYY-MM-DDTHH:MM:SS" 형식의 일시, \
그 외에는 null

다른 설명 없이 JSON 배열만 응답하세요.

전사문:
{transcript}
"""


@dataclass(frozen=True)
class ExtractedItem:
    """전사문에서 추출한 항목 1건 (specs/domain/resources.yaml extracted_items).

    데일리 노트/주제 병합(P3-R2-T1, P3-S1-T1)이 이 계약을 그대로 소비한다.
    """

    category: str  # "idea" | "schedule" | "important" | "etc"
    text: str
    topics: list[str]
    tags: list[str]
    event_at: str | None


ExtractFn = Callable[[str], list[ExtractedItem]]


def _item_from_dict(data: dict) -> ExtractedItem:
    """원시 dict(파싱된 JSON 항목 하나)를 ExtractedItem으로 변환한다.

    잘못되거나 없는 category는 "etc"로 폴백하고, topics/tags는 빠져 있으면
    빈 리스트로, event_at은 빠져 있으면 None으로 기본 처리한다.
    """
    category = data.get("category")
    if category not in _VALID_CATEGORIES:
        category = "etc"
    return ExtractedItem(
        category=category,
        text=str(data.get("text", "")),
        topics=list(data.get("topics") or []),
        tags=list(data.get("tags") or []),
        event_at=data.get("event_at"),
    )


def _strip_code_fence(text: str) -> str:
    """마크다운 코드펜스(` ```json ... ``` ` 또는 ` ``` ... ``` `)를 제거한다."""
    stripped = text.strip()
    if not stripped.startswith("```"):
        return stripped
    lines = stripped.splitlines()
    lines = lines[1:]  # 여는 펜스(옵션 "json" 라벨 포함) 제거
    if lines and lines[-1].strip() == "```":
        lines = lines[:-1]
    return "\n".join(lines).strip()


def parse_extraction_response(raw_text: str) -> list[ExtractedItem]:
    """Claude 응답 텍스트를 방어적으로 파싱해 ExtractedItem 목록으로 변환한다.

    마크다운 코드펜스를 제거하고, 응답에 다른 설명이 섞여 있으면 첫 `[` 부터
    마지막 `]` 까지를 JSON 배열로 시도한다. 잘못된 category는 "etc"로 폴백한다.

    Args:
        raw_text: Claude API가 반환한 원본 텍스트.

    Returns:
        파싱된 ExtractedItem 목록.

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
    return [_item_from_dict(item) for item in raw_items]


def items_to_json(items: list[ExtractedItem]) -> str:
    """ExtractedItem 목록을 recordings.extracted_json에 저장할 JSON 문자열로 변환."""
    return json.dumps([asdict(item) for item in items], ensure_ascii=False)


def items_from_json(raw_json: str) -> list[ExtractedItem]:
    """recordings.extracted_json 컬럼 값을 ExtractedItem 목록으로 역직렬화한다.

    데일리 노트/주제 병합이 DB에서 읽을 때 사용한다.
    """
    if not raw_json:
        return []
    raw_items = json.loads(raw_json)
    return [_item_from_dict(item) for item in raw_items]


def chunk_transcript_text(
    text: str, chunk_chars: int = DEFAULT_CHUNK_CHARS
) -> list[str]:
    """전사 텍스트를 문자수 기준으로 청크 분할한다 (줄 경계 유지).

    Args:
        text: 청크로 나눌 전사 텍스트.
        chunk_chars: 청크 하나의 대략적인 최대 문자 수.

    Returns:
        청크 문자열 목록. text가 비어 있으면 빈 목록.
    """
    if not text:
        return []
    lines = text.splitlines(keepends=True)
    chunks: list[str] = []
    current = ""
    for line in lines:
        if current and len(current) + len(line) > chunk_chars:
            chunks.append(current)
            current = ""
        current += line
    if current:
        chunks.append(current)
    return chunks


def load_extractor(
    api_key: str,
    model: str = DEFAULT_EXTRACT_MODEL,
    max_retries: int = DEFAULT_EXTRACT_MAX_RETRIES,
    timeout: float = DEFAULT_EXTRACT_TIMEOUT,
) -> ExtractFn:
    """Claude API를 호출해 '전사 텍스트 -> ExtractedItem 목록' 함수를 만든다.

    anthropic SDK를 이 함수 안에서만 import하므로, 이 함수를 호출하지 않는 한
    (예: 단위 테스트에서 fake extract_fn을 주입하는 경우) anthropic 설치가
    필요 없다. 텍스트만 전송하고 오디오/파일 경로는 전송하지 않는다.

    Args:
        api_key: Claude API 키.
        model: 사용할 모델 ID (기본: claude-sonnet-5).
        max_retries: API 호출 실패 시 SDK 차원의 재시도 횟수.
        timeout: 요청 타임아웃 (초).

    Returns:
        전사 텍스트(청크)를 받아 ExtractedItem 목록을 반환하는 함수.
    """
    import anthropic

    client = anthropic.Anthropic(
        api_key=api_key, max_retries=max_retries, timeout=timeout
    )

    def _extract(text: str) -> list[ExtractedItem]:
        message = client.messages.create(
            model=model,
            max_tokens=4096,
            messages=[
                {
                    "role": "user",
                    "content": _EXTRACTION_PROMPT.format(transcript=text),
                }
            ],
        )
        response_text = "".join(
            block.text for block in message.content if hasattr(block, "text")
        )
        return parse_extraction_response(response_text)

    return _extract


def load_cli_extractor(
    model: str | None = None, timeout: float = DEFAULT_EXTRACT_TIMEOUT
) -> ExtractFn:
    """로컬 claude CLI로 '전사 텍스트 -> ExtractedItem 목록' 함수를 만든다
    (API 키 불필요).

    구독 로그인(OAuth)으로 인증된 로컬 claude CLI(`claude -p`)를 호출하므로
    CLAUDE_API_KEY 가 필요 없다.

    Args:
        model: 사용할 모델 ID. None 이면 claude CLI 기본 모델을 사용한다.
        timeout: CLI 프로세스 실행 타임아웃 (초).

    Returns:
        전사 텍스트(청크)를 받아 ExtractedItem 목록을 반환하는 함수.
    """
    from airvoice.claude_cli import run_claude_cli

    def _extract(text: str) -> list[ExtractedItem]:
        response = run_claude_cli(
            _EXTRACTION_PROMPT.format(transcript=text), model=model, timeout=timeout
        )
        return parse_extraction_response(response)

    return _extract


def extract_items(
    recording_id: int,
    transcript_text: str,
    db_path: str | Path,
    extract_fn: ExtractFn,
    chunk_chars: int = DEFAULT_CHUNK_CHARS,
) -> list[ExtractedItem]:
    """단일 레코딩의 전사 텍스트에서 항목을 추출해 extracted로 전이한다.

    긴 전사문은 :func:`chunk_transcript_text` 로 분할해 청크별로 extract_fn을
    호출하고 결과를 하나의 목록으로 병합한 뒤 recordings.extracted_json에
    저장한다. 실패 시 extracted_failed로 전이하고 error_msg를 기록한 뒤 예외를
    다시 발생시킨다 (get_retry_targets가 다음 배치에서 재시도 대상으로 회수).

    Args:
        recording_id: transcribed 또는 extracted_failed 상태인 레코딩 id.
        transcript_text: 전사 원문 텍스트 (render_transcript_text 결과 등).
        db_path: SQLite DB 파일 경로.
        extract_fn: 전사 텍스트(청크) -> ExtractedItem 목록 함수
            (실 API는 :func:`load_extractor`, 테스트는 fake 주입).
        chunk_chars: 청크 하나의 대략적인 최대 문자 수.

    Returns:
        추출된 ExtractedItem 목록 (청크 결과 병합).

    Raises:
        Exception: extract_fn이 실패했을 때 (extracted_failed로 전이 후 재발생).
    """
    try:
        items: list[ExtractedItem] = []
        for chunk in chunk_transcript_text(transcript_text, chunk_chars):
            items.extend(extract_fn(chunk))
        update_extracted_json(db_path, recording_id, items_to_json(items))
    except Exception as exc:
        now = datetime.now(UTC).strftime(_TIMESTAMP_FORMAT)
        update_recording_status(
            db_path, recording_id, Status.EXTRACTED_FAILED, now, error_msg=str(exc)
        )
        raise

    now = datetime.now(UTC).strftime(_TIMESTAMP_FORMAT)
    update_recording_status(db_path, recording_id, Status.EXTRACTED, now)
    return items


def run_extract_batch(
    db_path: str | Path,
    temp_dir: str | Path,
    extract_fn: ExtractFn,
    chunk_chars: int = DEFAULT_CHUNK_CHARS,
) -> list[Recording]:
    """transcribed + extracted_failed 레코딩을 추출해 extracted로 전이한다.

    전사 텍스트는 temp_dir에 저장된 `{stem}.txt` 파일(transcribe.py
    save_transcript_text 결과)에서 읽는다. 실패한 레코딩은 extracted_failed
    상태로 남아 다음 배치 호출에서 자동으로 재시도된다. 실패는 로그로 남긴다.

    Args:
        db_path: SQLite DB 파일 경로.
        temp_dir: 전사 텍스트 파일(.txt)이 저장된 임시 폴더.
        extract_fn: 전사 텍스트(청크) -> ExtractedItem 목록 함수.
        chunk_chars: 청크 하나의 대략적인 최대 문자 수.

    Returns:
        이번 호출에서 extracted로 전이된 Recording 목록.
    """
    candidates = list(get_recordings(db_path, Status.TRANSCRIBED))
    candidates += [
        r for r in get_retry_targets(db_path) if r.status == Status.EXTRACTED_FAILED
    ]

    processed_ids: set[int] = set()
    for recording in candidates:
        text_path = Path(temp_dir) / f"{Path(recording.source_path).stem}.txt"
        try:
            transcript_text = text_path.read_text(encoding="utf-8")
            extract_items(
                recording.id, transcript_text, db_path, extract_fn, chunk_chars
            )
        except Exception as exc:  # noqa: BLE001 - 한 건 실패가 배치 전체를 막으면 안 됨
            logger.error(
                "추출 실패 (id=%s, path=%s): %s",
                recording.id,
                recording.source_path,
                exc,
            )
            continue
        processed_ids.add(recording.id)

    return [
        recording
        for recording in get_recordings(db_path, Status.EXTRACTED)
        if recording.id in processed_ids
    ]
