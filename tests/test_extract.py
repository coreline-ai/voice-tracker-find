# @TASK P3-R1-T1 - LLM 추출 (Claude API)
# @SPEC docs/planning/06-tasks.md#P3-R1-T1
from __future__ import annotations

import dataclasses
import json
from pathlib import Path

import pytest

from thinktank.db import (
    Recording,
    Status,
    get_recordings,
    get_retry_targets,
    init_db,
    insert_recording,
    update_recording_status,
)
from thinktank.extract import (
    ExtractedItem,
    chunk_transcript_text,
    extract_items,
    items_from_json,
    items_to_json,
    parse_extraction_response,
    run_extract_batch,
)

try:
    import anthropic  # noqa: F401

    _ANTHROPIC_AVAILABLE = True
except ImportError:
    _ANTHROPIC_AVAILABLE = False


@pytest.fixture
def db_path(tmp_path: Path) -> Path:
    """초기화된 임시 DB 파일 경로."""
    path = tmp_path / "pipeline.db"
    init_db(path)
    return path


@pytest.fixture
def temp_dir(tmp_path: Path) -> Path:
    """전사 텍스트 파일(.txt)을 저장할 임시 폴더."""
    path = tmp_path / "temp"
    path.mkdir()
    return path


def _make_transcribed_recording(
    db_path: Path,
    filename: str = "a.m4a",
    recorded_at: str = "2025-01-15 09:00:00",
) -> Recording:
    """transcribed 상태의 레코딩을 만든다."""
    recording = insert_recording(
        db_path,
        source_path=str(Path("/audio") / filename),
        filename=filename,
        recorded_at=recorded_at,
        file_size=1234567,
    )
    update_recording_status(
        db_path, recording.id, Status.SYNCED, "2025-01-15 09:00:01"
    )
    update_recording_status(
        db_path, recording.id, Status.VAD_DONE, "2025-01-15 09:05:00"
    )
    update_recording_status(
        db_path, recording.id, Status.TRANSCRIBED, "2025-01-15 09:10:00"
    )
    return next(
        r for r in get_recordings(db_path, Status.TRANSCRIBED) if r.id == recording.id
    )


def _write_transcript_txt(
    temp_dir: Path, filename: str, text: str = "hello world\n"
) -> Path:
    path = temp_dir / f"{Path(filename).stem}.txt"
    path.write_text(text, encoding="utf-8")
    return path


# ---------------------------------------------------------------------------
# ExtractedItem: 데일리 노트/주제 병합이 소비하는 계약
# ---------------------------------------------------------------------------


def test_extracted_item_is_frozen_dataclass():
    item = ExtractedItem(
        category="idea", text="foo", topics=["t1"], tags=["#a"], event_at=None
    )
    assert item.category == "idea"
    assert item.text == "foo"
    assert item.topics == ["t1"]
    assert item.tags == ["#a"]
    assert item.event_at is None
    with pytest.raises(dataclasses.FrozenInstanceError):
        item.text = "bar"  # type: ignore[misc]


# ---------------------------------------------------------------------------
# items_to_json / items_from_json: DB 왕복 직렬화
# ---------------------------------------------------------------------------


def test_items_to_json_and_back_round_trip():
    items = [
        ExtractedItem(
            category="idea", text="a", topics=["t"], tags=["#x"], event_at=None
        ),
        ExtractedItem(
            category="schedule",
            text="b",
            topics=[],
            tags=[],
            event_at="2025-01-15T14:00:00",
        ),
    ]
    restored = items_from_json(items_to_json(items))
    assert restored == items


def test_items_from_json_empty_string_returns_empty_list():
    assert items_from_json("") == []


def test_items_from_json_falls_back_invalid_category_to_etc():
    raw = json.dumps(
        [{"category": "bogus", "text": "x", "topics": [], "tags": [], "event_at": None}]
    )
    [item] = items_from_json(raw)
    assert item.category == "etc"


def test_items_from_json_defaults_missing_fields():
    raw = json.dumps([{"category": "idea", "text": "x"}])
    [item] = items_from_json(raw)
    assert item.topics == []
    assert item.tags == []
    assert item.event_at is None


# ---------------------------------------------------------------------------
# parse_extraction_response: Claude 응답 방어적 파싱
# ---------------------------------------------------------------------------


def test_parse_extraction_response_plain_json_array():
    raw = json.dumps(
        [{"category": "idea", "text": "x", "topics": [], "tags": [], "event_at": None}]
    )
    items = parse_extraction_response(raw)
    assert items == [
        ExtractedItem(category="idea", text="x", topics=[], tags=[], event_at=None)
    ]


def test_parse_extraction_response_strips_json_code_fence():
    raw = (
        '```json\n[{"category": "idea", "text": "x", "topics": [], '
        '"tags": [], "event_at": null}]\n```'
    )
    items = parse_extraction_response(raw)
    assert items[0].text == "x"


def test_parse_extraction_response_strips_plain_code_fence():
    raw = (
        '```\n[{"category": "important", "text": "y", "topics": [], '
        '"tags": [], "event_at": null}]\n```'
    )
    items = parse_extraction_response(raw)
    assert items[0].category == "important"


def test_parse_extraction_response_finds_json_array_within_surrounding_text():
    raw = (
        "Here is the result:\n"
        '[{"category": "etc", "text": "z", "topics": [], "tags": [], '
        '"event_at": null}]\n'
        "Done."
    )
    items = parse_extraction_response(raw)
    assert items[0].text == "z"


def test_parse_extraction_response_invalid_category_falls_back_to_etc():
    raw = json.dumps(
        [
            {
                "category": "urgent",
                "text": "x",
                "topics": [],
                "tags": [],
                "event_at": None,
            }
        ]
    )
    items = parse_extraction_response(raw)
    assert items[0].category == "etc"


def test_parse_extraction_response_malformed_json_raises_value_error():
    with pytest.raises(ValueError):
        parse_extraction_response("not json at all {{{")


# ---------------------------------------------------------------------------
# chunk_transcript_text: 문자수 기준 청크 분할 (줄 경계 유지)
# ---------------------------------------------------------------------------


def test_chunk_transcript_text_empty_returns_empty_list():
    assert chunk_transcript_text("") == []


def test_chunk_transcript_text_short_text_single_chunk():
    text = "hello\nworld\n"
    assert chunk_transcript_text(text, chunk_chars=100) == [text]


def test_chunk_transcript_text_splits_long_text_by_lines():
    text = "".join(f"line-{i}\n" for i in range(10))
    chunks = chunk_transcript_text(text, chunk_chars=30)

    assert len(chunks) > 1
    assert "".join(chunks) == text


# ---------------------------------------------------------------------------
# extract_items: 단일 레코딩 처리 (fake extract_fn 주입, API 호출 없음)
# ---------------------------------------------------------------------------


def test_extract_items_transitions_transcribed_to_extracted_and_stores_json(db_path):
    recording = _make_transcribed_recording(db_path)

    def fake_extract(text: str) -> list[ExtractedItem]:
        return [
            ExtractedItem(
                category="idea", text=text.strip(), topics=[], tags=[], event_at=None
            )
        ]

    items = extract_items(recording.id, "hello world\n", db_path, fake_extract)

    assert items == [
        ExtractedItem(
            category="idea", text="hello world", topics=[], tags=[], event_at=None
        )
    ]

    [updated] = get_recordings(db_path, Status.EXTRACTED)
    assert updated.id == recording.id
    assert updated.extracted_at is not None
    assert items_from_json(updated.extracted_json) == items


def test_extract_items_merges_results_across_chunks(db_path):
    recording = _make_transcribed_recording(db_path)
    calls: list[str] = []

    def fake_extract(text: str) -> list[ExtractedItem]:
        calls.append(text)
        return [
            ExtractedItem(
                category="etc", text=text.strip(), topics=[], tags=[], event_at=None
            )
        ]

    long_text = "".join(f"line-{i}\n" for i in range(20))
    items = extract_items(
        recording.id, long_text, db_path, fake_extract, chunk_chars=30
    )

    assert len(calls) > 1
    assert len(items) == len(calls)


def test_extract_items_marks_extracted_failed_on_error(db_path):
    recording = _make_transcribed_recording(db_path)

    def fake_extract_fails(text: str) -> list[ExtractedItem]:
        raise RuntimeError("API timeout")

    with pytest.raises(RuntimeError):
        extract_items(recording.id, "hello\n", db_path, fake_extract_fails)

    [failed] = get_recordings(db_path, Status.EXTRACTED_FAILED)
    assert failed.id == recording.id
    assert failed.error_msg == "API timeout"


def test_extract_items_retry_succeeds_from_extracted_failed(db_path):
    recording = _make_transcribed_recording(db_path)

    def fake_extract_fails(text: str) -> list[ExtractedItem]:
        raise RuntimeError("timeout")

    with pytest.raises(RuntimeError):
        extract_items(recording.id, "hello\n", db_path, fake_extract_fails)

    [failed] = get_retry_targets(db_path)
    assert failed.status == Status.EXTRACTED_FAILED

    def fake_extract_ok(text: str) -> list[ExtractedItem]:
        return [
            ExtractedItem(category="idea", text="ok", topics=[], tags=[], event_at=None)
        ]

    items = extract_items(failed.id, "hello\n", db_path, fake_extract_ok)

    assert items == [
        ExtractedItem(category="idea", text="ok", topics=[], tags=[], event_at=None)
    ]
    [updated] = get_recordings(db_path, Status.EXTRACTED)
    assert updated.id == recording.id


def test_extract_items_empty_transcript_produces_no_items(db_path):
    recording = _make_transcribed_recording(db_path)

    def fake_extract_should_not_be_called(text: str) -> list[ExtractedItem]:
        raise AssertionError("빈 전사문에는 extract_fn이 호출되면 안 됨")

    items = extract_items(
        recording.id, "", db_path, fake_extract_should_not_be_called
    )

    assert items == []
    [updated] = get_recordings(db_path, Status.EXTRACTED)
    assert updated.extracted_json == "[]"


# ---------------------------------------------------------------------------
# run_extract_batch: transcribed + extracted_failed 전체를 처리하는 배치
# ---------------------------------------------------------------------------


def test_run_extract_batch_transitions_all_transcribed_to_extracted(db_path, temp_dir):
    r1 = _make_transcribed_recording(db_path, "a.m4a")
    r2 = _make_transcribed_recording(db_path, "b.m4a")
    _write_transcript_txt(temp_dir, "a.m4a")
    _write_transcript_txt(temp_dir, "b.m4a")

    def fake_extract(text: str) -> list[ExtractedItem]:
        return [
            ExtractedItem(
                category="idea", text=text.strip(), topics=[], tags=[], event_at=None
            )
        ]

    processed = run_extract_batch(db_path, temp_dir, fake_extract)

    assert {r.id for r in processed} == {r1.id, r2.id}
    assert all(r.status == Status.EXTRACTED for r in processed)
    assert get_recordings(db_path, Status.TRANSCRIBED) == []


def test_run_extract_batch_keeps_failed_recording_for_retry(db_path, temp_dir):
    ok = _make_transcribed_recording(db_path, "ok.m4a")
    broken = _make_transcribed_recording(db_path, "broken.m4a")
    _write_transcript_txt(temp_dir, "ok.m4a", "ok content\n")
    _write_transcript_txt(temp_dir, "broken.m4a", "broken content\n")

    def fake_extract(text: str) -> list[ExtractedItem]:
        if "broken" in text:
            raise RuntimeError("llm error")
        return [
            ExtractedItem(
                category="idea", text=text.strip(), topics=[], tags=[], event_at=None
            )
        ]

    processed = run_extract_batch(db_path, temp_dir, fake_extract)

    assert {r.id for r in processed} == {ok.id}
    assert [r.id for r in get_recordings(db_path, Status.EXTRACTED_FAILED)] == [
        broken.id
    ]
    assert [r.id for r in get_recordings(db_path, Status.EXTRACTED)] == [ok.id]


def test_run_extract_batch_retries_previously_failed_recordings(db_path, temp_dir):
    recording = _make_transcribed_recording(db_path, "flaky.m4a")
    _write_transcript_txt(temp_dir, "flaky.m4a", "flaky content\n")
    call_count = {"n": 0}

    def fake_extract_first_fails(text: str) -> list[ExtractedItem]:
        call_count["n"] += 1
        if call_count["n"] == 1:
            raise RuntimeError("temporary failure")
        return [
            ExtractedItem(category="idea", text="ok", topics=[], tags=[], event_at=None)
        ]

    first = run_extract_batch(db_path, temp_dir, fake_extract_first_fails)
    assert first == []
    assert [r.id for r in get_recordings(db_path, Status.EXTRACTED_FAILED)] == [
        recording.id
    ]

    second = run_extract_batch(db_path, temp_dir, fake_extract_first_fails)
    assert [r.id for r in second] == [recording.id]
    assert get_recordings(db_path, Status.EXTRACTED_FAILED) == []


def test_run_extract_batch_no_candidates_returns_empty(db_path, temp_dir):
    def fake_extract(text: str) -> list[ExtractedItem]:
        return [
            ExtractedItem(category="idea", text="x", topics=[], tags=[], event_at=None)
        ]

    assert run_extract_batch(db_path, temp_dir, fake_extract) == []


def test_run_extract_batch_is_idempotent_on_rerun(db_path, temp_dir):
    """한 번 추출된 레코딩은 다음 배치 재실행에서 다시 처리되지 않는다."""
    _make_transcribed_recording(db_path, "a.m4a")
    _write_transcript_txt(temp_dir, "a.m4a")

    def fake_extract(text: str) -> list[ExtractedItem]:
        return [
            ExtractedItem(category="idea", text="x", topics=[], tags=[], event_at=None)
        ]

    first = run_extract_batch(db_path, temp_dir, fake_extract)
    second = run_extract_batch(db_path, temp_dir, fake_extract)

    assert len(first) == 1
    assert second == []


# ---------------------------------------------------------------------------
# load_extractor: anthropic lazy import (모듈 임포트 자체는 anthropic 불필요)
# ---------------------------------------------------------------------------


def test_extract_module_importable_without_anthropic_installed():
    """anthropic 미설치 환경에서도 모듈 최상단 임포트는 성공해야 한다."""
    import thinktank.extract as extract_module

    assert hasattr(extract_module, "load_extractor")


@pytest.mark.skipif(
    _ANTHROPIC_AVAILABLE,
    reason="anthropic 이 설치된 환경에서는 이 미설치 케이스를 검증할 수 없음",
)
def test_load_extractor_raises_when_anthropic_not_installed():
    from thinktank.extract import load_extractor

    with pytest.raises(ModuleNotFoundError):
        load_extractor(api_key="test-key")


@pytest.mark.skipif(
    not _ANTHROPIC_AVAILABLE,
    reason="anthropic 미설치 - 실 API 검증은 P5 E2E에서 수행",
)
def test_load_extractor_returns_callable_with_real_client():
    from thinktank.extract import load_extractor

    extractor = load_extractor(api_key="test-key")

    assert callable(extractor)


# ---------------------------------------------------------------------------
# load_cli_extractor: 로컬 claude CLI(구독 OAuth) 백엔드 (API 키 불필요)
# ---------------------------------------------------------------------------


def test_load_cli_extractor_returns_parsed_items(monkeypatch):
    """CLI 응답(JSON 문자열)을 올바른 ExtractedItem 목록으로 파싱한다."""
    from thinktank.extract import load_cli_extractor

    raw_response = json.dumps(
        [
            {
                "category": "idea",
                "text": "새 아이디어",
                "topics": ["design"],
                "tags": ["#idea"],
                "event_at": None,
            },
            {
                "category": "schedule",
                "text": "회의 일정",
                "topics": [],
                "tags": [],
                "event_at": "2025-01-20T10:00:00",
            },
        ]
    )

    def _fake_run_claude_cli(prompt: str, model=None, timeout=120.0) -> str:
        assert "어떤 전사 텍스트" in prompt  # 프롬프트에 전사문이 포함되어야 함
        return raw_response

    monkeypatch.setattr(
        "thinktank.claude_cli.run_claude_cli", _fake_run_claude_cli
    )

    extractor = load_cli_extractor()
    items = extractor("어떤 전사 텍스트")

    assert items == [
        ExtractedItem(
            category="idea",
            text="새 아이디어",
            topics=["design"],
            tags=["#idea"],
            event_at=None,
        ),
        ExtractedItem(
            category="schedule",
            text="회의 일정",
            topics=[],
            tags=[],
            event_at="2025-01-20T10:00:00",
        ),
    ]


def test_load_cli_extractor_strips_code_fence(monkeypatch):
    """코드펜스로 감싸인 CLI 응답도 방어적으로 파싱된다."""
    from thinktank.extract import load_cli_extractor

    fenced = "```json\n" + json.dumps(
        [
            {
                "category": "etc",
                "text": "기타 항목",
                "topics": [],
                "tags": [],
                "event_at": None,
            }
        ]
    ) + "\n```"

    def _fake_run_claude_cli(prompt: str, model=None, timeout=120.0) -> str:
        return fenced

    monkeypatch.setattr("thinktank.claude_cli.run_claude_cli", _fake_run_claude_cli)

    extractor = load_cli_extractor()
    items = extractor("전사 텍스트")

    assert items == [
        ExtractedItem(
            category="etc", text="기타 항목", topics=[], tags=[], event_at=None
        )
    ]
