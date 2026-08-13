# @TASK P5-T5.2 - E2E 통합 테스트 (전체 배치 파이프라인)
# @SPEC docs/planning/06-tasks.md#P5-T5.2
"""airvoice.main.run_pipeline 전체 플로우를 오프라인(fake 의존성 전부 주입)으로
검증하는 E2E 테스트.

실제 GPU/torch/faster-whisper/anthropic 는 전혀 사용하지 않고, detect_speech/
cut_audio/transcribe_fn/extract_fn/emerge_fn 을 모두 fake 로 주입한다. 가짜
.m4a 파일(바이트 내용은 의미 없음, 파일명의 타임스탬프만 recorded_at 파싱에
사용됨)로 ingest 부터 cleanup/emerge 까지 파이프라인 전 구간을 수 초 내에
검증한다.

검증 시나리오 (docs/planning/06-tasks.md#P5-T5.2):
1. 정상 플로우: SQLite 상태 시퀀스 전체 + 90-archive/10-daily/20-notes/
   _pipeline.md 노트 생성.
2. 실패 주입 -> catch-up: extract_fn 예외 -> extracted_failed(+error_msg) ->
   _pipeline.md ⚠️ + 에러 상세 -> 다음 배치(정상 extract_fn)에서 자동 재시도
   -> organized, 데일리/주제 노트 반영.
3. 멱등성: 같은 상태에서 재실행 -> 중복 노트/중복 로그 블록/재전이 없음.
4. 7일 폐기: organized_at 8일 전 조작 -> 녹음 원본/temp 전사본/90-archive
   노트 3종 실삭제 + deleted 전이. recorded_at 날짜가 처리일과 다른 레코드로
   검증(아카이브 노트 파일명 재구성 경로 회귀 테스트).
5. 창발 주기: force_emerge=True + 주제 3개 이상 -> 30-ideas/ 노트 생성 +
   근거 링크(20-notes 슬러그) 정합, should_run_emerge 직후 재판단 False.
"""

from __future__ import annotations

import sqlite3
from datetime import UTC, datetime
from pathlib import Path

import pytest

from tests.vault_text import daily_all_text
from airvoice.config import Settings
from airvoice.db import Status, get_recordings
from airvoice.extract import ExtractedItem
from airvoice.main import run_pipeline, should_run_emerge
from airvoice.notes.emerged import IDEAS_SUBDIR
from airvoice.notes.renderer import archive_filename, normalize_filename
from airvoice.transcribe import RawSegment
from airvoice.vad import VadSegment


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    """모든 경로가 tmp_path 아래로 격리된 검증된 Settings."""
    ingest_dir = tmp_path / "ingest"
    ingest_dir.mkdir()
    vault = tmp_path / "vault"
    vault.mkdir()
    temp_dir = tmp_path / "temp"
    temp_dir.mkdir()
    return Settings(
        claude_api_key="test-api-key",
        ingest_dir=ingest_dir,
        obsidian_vault=vault,
        db_path=tmp_path / "pipeline.db",
        temp_dir=temp_dir,
        whisper_model="large-v3",
        vad_sample_rate=16000,
        vad_threshold=0.5,
        retention_days=7,
    )


def _write_audio(ingest_dir: Path, filename: str) -> Path:
    """파일명에 타임스탬프를 담아 recorded_at 이 결정적으로 파싱되게 한다."""
    path = ingest_dir / filename
    path.write_bytes(b"fake-audio-bytes")
    return path


def _fake_detect_speech(_audio_path: Path) -> list[VadSegment]:
    return [VadSegment(start=0.0, end=5.0)]


def _fake_cut_audio(_audio_path: Path, output_path: Path, _segments) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(b"fake-vad-audio")


def _fake_emerge_fn(_prior_ideas: str, _topics: dict[str, str]) -> list[dict]:
    return []


def _make_transcribe_fn(text_map: dict[str, str]):
    """audio_path.stem 별로 다른 세그먼트 텍스트를 반환하는 fake transcribe_fn."""

    def _transcribe(audio_path: Path) -> list[RawSegment]:
        text = text_map.get(audio_path.stem, "회의를 시작합니다.")
        return [RawSegment(start=0.0, end=5.0, text=text)]

    return _transcribe


def _make_extract_fn(
    item_map: dict[str, list[ExtractedItem]], *, fail_marker: str | None = None
):
    """청크 텍스트에 담긴 마커 문자열로 반환할 ExtractedItem 목록을 결정하는 fake.

    fail_marker 가 청크 텍스트에 포함되면 RuntimeError 를 던져 추출 실패를
    재현한다.
    """

    def _extract(chunk: str) -> list[ExtractedItem]:
        if fail_marker and fail_marker in chunk:
            raise RuntimeError("추출 실패 (fake)")
        for marker, items in item_map.items():
            if marker in chunk:
                return items
        return []

    return _extract


def _today() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%d")


def _set_organized_at_days_ago(db_path: Path, recording_id: int, days: int) -> None:
    """organized_at 값을 테스트 목적으로 days 일 전으로 되돌린다 (7일 폐기 검증용)."""
    conn = sqlite3.connect(db_path)
    try:
        conn.execute(
            "UPDATE recordings SET organized_at = datetime('now', ?) WHERE id = ?",
            (f"-{days} days", recording_id),
        )
        conn.commit()
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# 시나리오 1: 정상 플로우 - SQLite 상태 시퀀스 + 4종 노트 생성.
# ---------------------------------------------------------------------------


class TestNormalFlow:
    def test_two_recordings_reach_organized_with_full_status_and_notes(
        self, settings: Settings
    ):
        _write_audio(settings.ingest_dir, "20250115_090000_standup.m4a")
        _write_audio(settings.ingest_dir, "20250115_140000_review.m4a")

        transcribe_fn = _make_transcribe_fn(
            {
                "20250115_090000_standup": (
                    "스탠드업 회의입니다. STANDUP_MARKER 고객 인터뷰 내용을 공유합니다."
                ),
                "20250115_140000_review": (
                    "리뷰 회의입니다. REVIEW_MARKER 고객 인터뷰 후속 조치를 논의합니다."
                ),
            }
        )
        extract_fn = _make_extract_fn(
            {
                "STANDUP_MARKER": [
                    ExtractedItem(
                        category="idea",
                        text="스탠드업 아이디어",
                        topics=["고객 인터뷰"],
                        tags=["#standup"],
                        event_at=None,
                    )
                ],
                "REVIEW_MARKER": [
                    ExtractedItem(
                        category="idea",
                        text="리뷰 아이디어",
                        topics=["고객 인터뷰"],
                        tags=["#review"],
                        event_at=None,
                    )
                ],
            }
        )

        run = run_pipeline(
            settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=transcribe_fn,
            extract_fn=extract_fn,
            emerge_fn=_fake_emerge_fn,
        )

        # --- SQLite 상태 시퀀스: pending -> ... -> organized, 각 타임스탬프 채워짐 ---
        recordings = get_recordings(settings.db_path, Status.ORGANIZED)
        assert {r.filename for r in recordings} == {
            "20250115_090000_standup.m4a",
            "20250115_140000_review.m4a",
        }
        for recording in recordings:
            assert recording.synced_at is not None
            assert recording.vad_at is not None
            assert recording.transcribed_at is not None
            assert recording.extracted_at is not None
            assert recording.organized_at is not None
            assert recording.status == Status.ORGANIZED

        # --- 90-archive/: 전사 세그먼트 포함 노트 ---
        archive_standup = (
            settings.obsidian_vault
            / "90-archive"
            / "20250115_090000_standup_2025-01-15.md"
        )
        archive_review = (
            settings.obsidian_vault
            / "90-archive"
            / "20250115_140000_review_2025-01-15.md"
        )
        assert archive_standup.exists()
        assert archive_review.exists()
        assert "STANDUP_MARKER" in archive_standup.read_text(encoding="utf-8")
        assert "REVIEW_MARKER" in archive_review.read_text(encoding="utf-8")

        # --- 10-daily/: 섹션 + 처리 결과 헤더 ---
        run_date = _today()
        rec_date = "2025-01-15"  # 노트는 실행일이 아니라 녹음일로 묶인다
        daily_note = settings.obsidian_vault / "10-daily" / f"{rec_date}.md"
        assert daily_note.exists()
        index_text = daily_note.read_text(encoding="utf-8")
        assert "## 처리 결과" in index_text
        assert "✅ 2건 처리 / 0건 실패" in index_text
        # 항목 본문은 구분별 노트에 있다.
        daily_text = daily_all_text(settings.obsidian_vault, rec_date)
        assert "스탠드업 아이디어" in daily_text
        assert "리뷰 아이디어" in daily_text

        topic_slug = normalize_filename("고객 인터뷰")
        assert f"[[{topic_slug}]]" in daily_text

        # --- 20-notes/: 주제 노트 (백링크 slug 정합) ---
        topic_note = settings.obsidian_vault / "20-notes" / f"{topic_slug}.md"
        assert topic_note.exists()
        topic_text = topic_note.read_text(encoding="utf-8")
        assert "스탠드업 아이디어" in topic_text
        assert "리뷰 아이디어" in topic_text

        # --- _pipeline.md: 완료 기록 ---
        pipeline_log = settings.obsidian_vault / "_pipeline.md"
        assert pipeline_log.exists()
        log_text = pipeline_log.read_text(encoding="utf-8")
        assert f"## {run_date}" in log_text
        assert "✅ 완료" in log_text

        assert run.run_date == run_date
        assert run.success_count == 2
        assert run.fail_count == 0


# ---------------------------------------------------------------------------
# 시나리오 2: 실패 주입 -> catch-up.
# ---------------------------------------------------------------------------


class TestFailureInjectionAndCatchUp:
    def test_extract_failure_then_retry_reaches_organized(self, settings: Settings):
        _write_audio(settings.ingest_dir, "20250116_090000_good.m4a")
        _write_audio(settings.ingest_dir, "20250116_091500_bad.m4a")

        transcribe_fn = _make_transcribe_fn(
            {
                "20250116_090000_good": "정상 회의 내용 GOOD_MARKER",
                "20250116_091500_bad": "문제가 되는 내용 FAIL_TRIGGER",
            }
        )
        failing_extract_fn = _make_extract_fn(
            {
                "GOOD_MARKER": [
                    ExtractedItem(
                        category="idea",
                        text="정상 아이디어",
                        topics=["파이프라인"],
                        tags=["#ok"],
                        event_at=None,
                    )
                ],
            },
            fail_marker="FAIL_TRIGGER",
        )

        run1 = run_pipeline(
            settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=transcribe_fn,
            extract_fn=failing_extract_fn,
            emerge_fn=_fake_emerge_fn,
        )

        # --- 실패 레코드: extracted_failed + error_msg ---
        [bad] = get_recordings(settings.db_path, Status.EXTRACTED_FAILED)
        assert bad.filename == "20250116_091500_bad.m4a"
        assert bad.error_msg == "추출 실패 (fake)"
        [good] = [
            r
            for r in get_recordings(settings.db_path, Status.ORGANIZED)
            if r.filename == "20250116_090000_good.m4a"
        ]
        assert good.status == Status.ORGANIZED

        run_date = _today()
        pipeline_log = settings.obsidian_vault / "_pipeline.md"
        log_text_1 = pipeline_log.read_text(encoding="utf-8")
        assert "⚠️" in log_text_1
        assert "추출 실패 (fake)" in log_text_1
        assert bad.filename in log_text_1
        assert run1.fail_count == 1
        assert run1.success_count == 1

        # --- 다음 배치: extract_fn 정상화 -> 자동 재시도 ---
        fixed_extract_fn = _make_extract_fn(
            {
                "GOOD_MARKER": [
                    ExtractedItem(
                        category="idea",
                        text="정상 아이디어",
                        topics=["파이프라인"],
                        tags=["#ok"],
                        event_at=None,
                    )
                ],
                "FAIL_TRIGGER": [
                    ExtractedItem(
                        category="idea",
                        text="재시도 성공 아이디어",
                        topics=["파이프라인"],
                        tags=["#retry"],
                        event_at=None,
                    )
                ],
            }
        )

        run2 = run_pipeline(
            settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=transcribe_fn,
            extract_fn=fixed_extract_fn,
            emerge_fn=_fake_emerge_fn,
        )

        organized = {
            r.filename for r in get_recordings(settings.db_path, Status.ORGANIZED)
        }
        assert organized == {
            "20250116_090000_good.m4a",
            "20250116_091500_bad.m4a",
        }
        assert get_recordings(settings.db_path, Status.EXTRACTED_FAILED) == []

        # --- 데일리/주제 노트에 재시도 결과(및 기존 성공 건)가 모두 반영 ---
        daily_text = daily_all_text(settings.obsidian_vault, "2025-01-16")
        assert "재시도 성공 아이디어" in daily_text
        assert "정상 아이디어" in daily_text

        topic_note = settings.obsidian_vault / "20-notes" / "파이프라인.md"
        topic_text = topic_note.read_text(encoding="utf-8")
        assert "정상 아이디어" in topic_text
        assert "재시도 성공 아이디어" in topic_text

        log_text_2 = pipeline_log.read_text(encoding="utf-8")
        assert log_text_2.count(f"## {run_date}") == 1
        assert "✅ 완료" in log_text_2
        assert run2.fail_count == 0
        assert run2.success_count == 2


# ---------------------------------------------------------------------------
# 시나리오 3: 멱등성 - 같은 상태에서 재실행해도 중복이 생기지 않는다.
# ---------------------------------------------------------------------------


class TestIdempotency:
    def test_rerun_same_state_creates_no_duplicates_and_no_retransition(
        self, settings: Settings
    ):
        _write_audio(settings.ingest_dir, "20250117_090000_meeting.m4a")
        transcribe_fn = _make_transcribe_fn(
            {"20250117_090000_meeting": "회의 내용 MEETING_MARKER"}
        )
        extract_fn = _make_extract_fn(
            {
                "MEETING_MARKER": [
                    ExtractedItem(
                        category="idea",
                        text="재실행 아이디어",
                        topics=["재실행-검증"],
                        tags=[],
                        event_at=None,
                    )
                ],
            }
        )
        kwargs = dict(
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=transcribe_fn,
            extract_fn=extract_fn,
            emerge_fn=_fake_emerge_fn,
        )

        run_pipeline(settings, **kwargs)

        run_date = _today()
        daily_note = settings.obsidian_vault / "10-daily" / "2025-01-17.md"
        topic_note = settings.obsidian_vault / "20-notes" / "재실행-검증.md"
        pipeline_log = settings.obsidian_vault / "_pipeline.md"

        first_daily = daily_note.read_text(encoding="utf-8")
        first_topic = topic_note.read_text(encoding="utf-8")
        first_log = pipeline_log.read_text(encoding="utf-8")
        [first_organized] = get_recordings(settings.db_path, Status.ORGANIZED)
        first_organized_at = first_organized.organized_at

        run_pipeline(settings, **kwargs)

        assert daily_note.read_text(encoding="utf-8") == first_daily
        assert topic_note.read_text(encoding="utf-8") == first_topic
        second_log = pipeline_log.read_text(encoding="utf-8")
        assert second_log.count(f"## {run_date}") == 1
        # 실행 시각(executed_at)만 갱신되고 나머지 내용은 동일해야 하므로, 최소한
        # 로그 블록 개수가 늘지 않았는지만 확인한다(전체 텍스트 완전 동일은 아님).
        assert first_log.count("## ") == second_log.count("## ")

        organized = get_recordings(settings.db_path, Status.ORGANIZED)
        assert len(organized) == 1
        assert organized[0].organized_at == first_organized_at


# ---------------------------------------------------------------------------
# 시나리오 4: 7일 폐기 E2E - recorded_at 날짜와 처리일이 다른 레코드 검증.
# ---------------------------------------------------------------------------


class TestSevenDayCleanupE2E:
    def test_recorded_at_differs_from_processing_day_all_three_files_deleted(
        self, settings: Settings
    ):
        # 파일명에 먼 과거 타임스탬프를 담아, recorded_at 날짜가 실제 처리일과
        # 다르도록 재현한다 ("어젯밤 녹음을 오늘 처리"의 극단적 사례).
        filename = "20240101_235900_late_night.m4a"
        _write_audio(settings.ingest_dir, filename)

        transcribe_fn = _make_transcribe_fn(
            {"20240101_235900_late_night": "늦은 밤 메모 NIGHT_MARKER"}
        )
        extract_fn = _make_extract_fn(
            {
                "NIGHT_MARKER": [
                    ExtractedItem(
                        category="idea",
                        text="야간 메모",
                        topics=["야간"],
                        tags=[],
                        event_at=None,
                    )
                ],
            }
        )
        kwargs = dict(
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=transcribe_fn,
            extract_fn=extract_fn,
            emerge_fn=_fake_emerge_fn,
        )

        run_pipeline(settings, **kwargs)

        [recording] = get_recordings(settings.db_path, Status.ORGANIZED)
        recorded_date = (recording.recorded_at or "")[:10]
        run_date = _today()
        # 전제 조건: recorded_at 날짜가 실제 처리일과 다르다.
        assert recorded_date != run_date
        assert recorded_date == "2024-01-01"

        archive_note = (
            settings.obsidian_vault
            / "90-archive"
            / archive_filename(filename, recorded_date)
        )
        assert archive_note.exists()

        temp_txt = settings.temp_dir / f"{Path(filename).stem}.txt"
        assert temp_txt.exists()
        ingest_audio = settings.ingest_dir / filename
        assert ingest_audio.exists()

        _set_organized_at_days_ago(settings.db_path, recording.id, 8)

        run_pipeline(settings, **kwargs)

        # 폐기 대상 3종이 모두 실제로 삭제됐는지 검증 (아카이브 노트 파일명이
        # recorded_at[:10] 기준으로 재구성되므로, 처리일과 무관하게 삭제돼야 한다).
        assert not ingest_audio.exists()
        assert not temp_txt.exists()
        assert not archive_note.exists()

        [deleted] = get_recordings(settings.db_path, Status.DELETED)
        assert deleted.id == recording.id
        assert deleted.deleted_at is not None
        assert get_recordings(settings.db_path, Status.ORGANIZED) == []


# ---------------------------------------------------------------------------
# 시나리오 5: 창발 주기 - force_emerge=True + 주제 3개 이상.
# ---------------------------------------------------------------------------


class TestEmergeCycleE2E:
    def test_force_emerge_with_three_topics_creates_idea_note_with_valid_backlinks(
        self, settings: Settings
    ):
        files = [
            ("20250120_090000_alpha.m4a", "ALPHA_MARKER 회의 내용", "topic-alpha"),
            ("20250120_100000_beta.m4a", "BETA_MARKER 회의 내용", "topic-beta"),
            ("20250120_110000_gamma.m4a", "GAMMA_MARKER 회의 내용", "topic-gamma"),
        ]
        text_map: dict[str, str] = {}
        item_map: dict[str, list[ExtractedItem]] = {}
        for filename, text, topic in files:
            _write_audio(settings.ingest_dir, filename)
            stem = Path(filename).stem
            text_map[stem] = text
            marker = text.split()[0]
            item_map[marker] = [
                ExtractedItem(
                    category="idea",
                    text=f"{topic} 아이디어",
                    topics=[topic],
                    tags=[],
                    event_at=None,
                )
            ]

        def _fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
            assert set(topics) == {"topic-alpha", "topic-beta", "topic-gamma"}
            return [
                {
                    "title": "세 주제의 교차 통찰",
                    "insight": "세 주제가 만나는 지점",
                    "examples": ["사례1"],
                    "next_steps": ["다음 스텝1"],
                    "evidence": [
                        {"topic": "topic-alpha", "mention_count": 1},
                        {"topic": "topic-beta", "mention_count": 1},
                        {"topic": "topic-gamma", "mention_count": 1},
                    ],
                }
            ]

        run_date = "2025-02-01"
        run = run_pipeline(
            settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=_make_transcribe_fn(text_map),
            extract_fn=_make_extract_fn(item_map),
            emerge_fn=_fake_emerge,
            force_emerge=True,
            today=run_date,
        )

        for _, _, topic in files:
            assert (settings.obsidian_vault / "20-notes" / f"{topic}.md").exists()

        # 녹음별 메모도 같은 30-ideas 폴더에 들어가므로, 이 검증은 창발 노트만
        # 분리한다.
        idea_files = list((settings.obsidian_vault / IDEAS_SUBDIR).glob("*_idea_*.md"))
        assert len(idea_files) == 1
        idea_text = idea_files[0].read_text(encoding="utf-8")
        assert "세 주제의 교차 통찰" in idea_text
        # 근거 링크(evidence)가 실제 20-notes 슬러그와 정합하는지 검증.
        for _, _, topic in files:
            assert f"[[{topic}]]" in idea_text
            assert (settings.obsidian_vault / "20-notes" / f"{topic}.md").exists()

        assert run.run_date == run_date
        # 방금 창발을 실행했으므로, 같은 날짜 기준으로는 재실행이 불필요하다.
        assert should_run_emerge(settings.obsidian_vault, run_date) is False
