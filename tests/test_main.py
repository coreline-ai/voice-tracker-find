# @TASK P5-T5.1 - 배치 오케스트레이션 테스트 (TDD RED)
# @SPEC docs/planning/06-tasks.md#P5-T5.1
"""main.run_pipeline 이 ingest~cleanup(+조건부 emerge) 전 단계를 어댑터 없이
연결하는지, organize 단계(신규 로직)가 아카이브/데일리/주제 노트를 만들고
extracted -> organized 전이를 수행하는지, 그리고 배치 전체가 멱등하고 부분
실패를 허용하는지 검증한다. 실제 오디오/torch/faster-whisper/anthropic 은
사용하지 않고 전부 fake 함수로 대체한다.
"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

import pytest

import thinktank.main
from tests.vault_text import daily_all_text
from thinktank.config import Settings
from thinktank.db import Status, get_recordings
from thinktank.extract import ExtractedItem
from thinktank.main import main, run_pipeline, should_run_emerge
from thinktank.notes.emerged import IDEAS_SUBDIR
from thinktank.transcribe import RawSegment
from thinktank.vad import VadSegment


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


def _make_transcribe_fn(text_map: dict[str, str] | None = None):
    """audio_path.stem 별로 다른 세그먼트 텍스트를 반환하는 fake transcribe_fn."""

    def _transcribe(audio_path: Path) -> list[RawSegment]:
        text = (text_map or {}).get(audio_path.stem, "회의를 시작합니다.")
        return [RawSegment(start=0.0, end=5.0, text=text)]

    return _transcribe


def _make_extract_fn(topics: list[str], *, fail_marker: str | None = None):
    """고정된 topics 를 담은 항목 1건을 반환하는 fake extract_fn.

    fail_marker 가 청크 텍스트에 포함되면 RuntimeError 를 던져 부분 실패를
    재현한다.
    """

    def _extract(chunk: str) -> list[ExtractedItem]:
        if fail_marker and fail_marker in chunk:
            raise RuntimeError("추출 실패 (fake)")
        return [
            ExtractedItem(
                category="idea",
                text="자동 정리 아이디어",
                topics=topics,
                tags=["#auto"],
                event_at=None,
            )
        ]

    return _extract


def _today() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%d")


# ---------------------------------------------------------------------------
# 전체 흐름: ingest -> vad -> transcribe -> extract -> organize -> report ->
# log -> cleanup 이 어댑터 없이 연결되는가.
# ---------------------------------------------------------------------------


class TestFullPipelineFlow:
    def test_run_pipeline_organizes_recording_and_creates_all_notes(
        self, settings: Settings
    ):
        _write_audio(settings.ingest_dir, "20250115_090000_meeting.m4a")

        run = run_pipeline(
            settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=_make_transcribe_fn(),
            extract_fn=_make_extract_fn(["파이프라인"]),
            emerge_fn=_fake_emerge_fn,
        )

        [recording] = get_recordings(settings.db_path, Status.ORGANIZED)
        assert recording.filename == "20250115_090000_meeting.m4a"

        archive_note = (
            settings.obsidian_vault
            / "90-archive"
            / "20250115_090000_meeting_2025-01-15.md"
        )
        assert archive_note.exists()
        assert "회의를 시작합니다." in archive_note.read_text(encoding="utf-8")

        memo_note = (
            settings.obsidian_vault
            / IDEAS_SUBDIR
            / "20250115_090000_meeting_2025-01-15_memo.md"
        )
        assert memo_note.exists()
        assert "# 음성 메모" in memo_note.read_text(encoding="utf-8")

        # 노트는 실행일이 아니라 녹음일(파일명 20250115)로 묶인다.
        # 반면 _pipeline.md 는 처리 기록이라 실행일 그대로다.
        run_date = _today()
        rec_date = "2025-01-15"
        daily_note = settings.obsidian_vault / "10-daily" / f"{rec_date}.md"
        assert daily_note.exists()
        # 항목 본문은 구분별 노트(YYYY-MM-DD_아이디어.md 등)에 들어간다.
        assert "자동 정리 아이디어" in daily_all_text(
            settings.obsidian_vault, rec_date
        )

        topic_note = settings.obsidian_vault / "20-notes" / "파이프라인.md"
        assert topic_note.exists()

        pipeline_log = settings.obsidian_vault / "_pipeline.md"
        assert pipeline_log.exists()
        assert f"## {run_date}" in pipeline_log.read_text(encoding="utf-8")

        assert run.run_date == run_date
        assert run.success_count == 1
        assert run.fail_count == 0

    def test_run_pipeline_topic_note_source_uses_filename_and_date_format(
        self, settings: Settings
    ):
        _write_audio(settings.ingest_dir, "20250115_090000_meeting.m4a")

        run_pipeline(
            settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=_make_transcribe_fn(),
            extract_fn=_make_extract_fn(["파이프라인"]),
            emerge_fn=_fake_emerge_fn,
        )

        content = (settings.obsidian_vault / "20-notes" / "파이프라인.md").read_text(
            encoding="utf-8"
        )
        assert "sources: [20250115_090000_meeting.m4a (2025-01-15)]" in content


# ---------------------------------------------------------------------------
# 멱등성: 같은 입력으로 두 번 실행해도 중복 노트/중복 상태 전이가 없다.
# ---------------------------------------------------------------------------


class TestIdempotency:
    def test_rerun_does_not_duplicate_notes_or_transitions(self, settings: Settings):
        _write_audio(settings.ingest_dir, "20250115_090000_meeting.m4a")
        kwargs = dict(
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=_make_transcribe_fn(),
            extract_fn=_make_extract_fn(["파이프라인"]),
            emerge_fn=_fake_emerge_fn,
        )

        run_pipeline(settings, **kwargs)

        run_date = _today()
        daily_note = settings.obsidian_vault / "10-daily" / "2025-01-15.md"
        topic_note = settings.obsidian_vault / "20-notes" / "파이프라인.md"
        pipeline_log = settings.obsidian_vault / "_pipeline.md"

        first_daily = daily_note.read_text(encoding="utf-8")
        first_topic = topic_note.read_text(encoding="utf-8")

        run_pipeline(settings, **kwargs)

        assert daily_note.read_text(encoding="utf-8") == first_daily
        assert topic_note.read_text(encoding="utf-8") == first_topic
        assert pipeline_log.read_text(encoding="utf-8").count(f"## {run_date}") == 1

        organized = get_recordings(settings.db_path, Status.ORGANIZED)
        assert len(organized) == 1

    def test_rerun_backfills_a_missing_recording_memo(self, settings: Settings):
        _write_audio(settings.ingest_dir, "20250115_090000_meeting.m4a")
        kwargs = dict(
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=_make_transcribe_fn(),
            extract_fn=_make_extract_fn(["파이프라인"]),
            emerge_fn=_fake_emerge_fn,
        )
        memo = (
            settings.obsidian_vault
            / IDEAS_SUBDIR
            / "20250115_090000_meeting_2025-01-15_memo.md"
        )

        run_pipeline(settings, **kwargs)
        memo.unlink()
        run_pipeline(settings, **kwargs)

        assert memo.exists()


# ---------------------------------------------------------------------------
# 부분 실패 허용: 한 레코딩의 추출 실패가 배치 전체를 막지 않고, 다음 배치에서
# 자동 재시도되어 회복된다.
# ---------------------------------------------------------------------------


class TestPartialFailure:
    def test_one_failing_recording_does_not_block_others_and_is_retried(
        self, settings: Settings
    ):
        _write_audio(settings.ingest_dir, "20250115_090000_good.m4a")
        _write_audio(settings.ingest_dir, "20250115_091000_bad.m4a")

        transcribe_fn = _make_transcribe_fn(
            {
                "20250115_090000_good": "정상 회의 내용",
                "20250115_091000_bad": "FAIL_TRIGGER 오류 유발 내용",
            }
        )
        extract_fn = _make_extract_fn(["파이프라인"], fail_marker="FAIL_TRIGGER")

        run = run_pipeline(
            settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=transcribe_fn,
            extract_fn=extract_fn,
            emerge_fn=_fake_emerge_fn,
        )

        organized = {
            r.filename for r in get_recordings(settings.db_path, Status.ORGANIZED)
        }
        assert organized == {"20250115_090000_good.m4a"}

        failed = get_recordings(settings.db_path, Status.EXTRACTED_FAILED)
        assert [r.filename for r in failed] == ["20250115_091000_bad.m4a"]

        assert run.success_count == 1
        assert run.fail_count == 1

        fixed_extract_fn = _make_extract_fn(["파이프라인"])
        run_pipeline(
            settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=transcribe_fn,
            extract_fn=fixed_extract_fn,
            emerge_fn=_fake_emerge_fn,
        )

        organized_after_retry = {
            r.filename for r in get_recordings(settings.db_path, Status.ORGANIZED)
        }
        assert organized_after_retry == {
            "20250115_090000_good.m4a",
            "20250115_091000_bad.m4a",
        }
        assert get_recordings(settings.db_path, Status.EXTRACTED_FAILED) == []

    def test_daily_note_after_retry_keeps_earlier_batch_items(self, settings: Settings):
        """데일리 노트는 배치 스냅샷이 아니라 그 날 organized된 전체 합산이어야
        한다. 1차 배치(good 성공 + bad 실패) 이후 2차 배치(bad 재시도 성공)가
        실행돼도 1차에서 이미 organized된 good 의 항목이 데일리 노트에서
        유실되면 안 된다.
        """
        _write_audio(settings.ingest_dir, "20250115_090000_good.m4a")
        _write_audio(settings.ingest_dir, "20250115_091000_bad.m4a")

        transcribe_fn = _make_transcribe_fn(
            {
                "20250115_090000_good": "정상 회의 내용 GOOD_MARKER",
                "20250115_091000_bad": "FAIL_TRIGGER 오류 유발 내용",
            }
        )

        def _failing_extract(chunk: str) -> list[ExtractedItem]:
            if "FAIL_TRIGGER" in chunk:
                raise RuntimeError("추출 실패 (fake)")
            return [
                ExtractedItem(
                    category="idea",
                    text="굿 아이디어",
                    topics=["파이프라인"],
                    tags=[],
                    event_at=None,
                )
            ]

        run_pipeline(
            settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=transcribe_fn,
            extract_fn=_failing_extract,
            emerge_fn=_fake_emerge_fn,
        )

        def _fixed_extract(chunk: str) -> list[ExtractedItem]:
            if "FAIL_TRIGGER" in chunk:
                return [
                    ExtractedItem(
                        category="idea",
                        text="배드 아이디어",
                        topics=["파이프라인"],
                        tags=[],
                        event_at=None,
                    )
                ]
            return [
                ExtractedItem(
                    category="idea",
                    text="굿 아이디어",
                    topics=["파이프라인"],
                    tags=[],
                    event_at=None,
                )
            ]

        run_pipeline(
            settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=transcribe_fn,
            extract_fn=_fixed_extract,
            emerge_fn=_fake_emerge_fn,
        )

        daily_text = daily_all_text(settings.obsidian_vault, "2025-01-15")
        assert "굿 아이디어" in daily_text
        assert "배드 아이디어" in daily_text


# ---------------------------------------------------------------------------
# should_run_emerge: 창발 실행 간격 판단 (EMERGE_INTERVAL_DAYS=1, 매일).
# ---------------------------------------------------------------------------


class TestShouldRunEmerge:
    def test_true_when_ideas_folder_missing(self, tmp_path: Path):
        vault = tmp_path / "vault"
        vault.mkdir()
        assert should_run_emerge(vault, "2025-02-14") is True

    def test_false_when_already_run_today(self, tmp_path: Path):
        ideas_dir = tmp_path / "vault" / IDEAS_SUBDIR
        ideas_dir.mkdir(parents=True)
        (ideas_dir / "2025-02-01_idea_1.md").write_text("x", encoding="utf-8")

        # 같은 날 이미 실행됨(경과 0일) -> 재실행 안 함.
        assert should_run_emerge(tmp_path / "vault", "2025-02-01") is False

    def test_true_when_one_day_elapsed(self, tmp_path: Path):
        ideas_dir = tmp_path / "vault" / IDEAS_SUBDIR
        ideas_dir.mkdir(parents=True)
        (ideas_dir / "2025-01-31_idea_1.md").write_text("x", encoding="utf-8")

        # 하루만 지나도 실행(간격=1).
        assert should_run_emerge(tmp_path / "vault", "2025-02-01") is True

    def test_uses_latest_of_multiple_idea_files(self, tmp_path: Path):
        ideas_dir = tmp_path / "vault" / IDEAS_SUBDIR
        ideas_dir.mkdir(parents=True)
        (ideas_dir / "2025-01-01_idea_1.md").write_text("x", encoding="utf-8")
        (ideas_dir / "2025-02-01_idea_2.md").write_text("x", encoding="utf-8")

        # 최신(2025-02-01) 기준이면 같은 날이라 False. 최초(01-01) 기준이었다면
        # True가 되므로, 이 케이스가 '최신 날짜를 사용함'을 검증한다.
        assert should_run_emerge(tmp_path / "vault", "2025-02-01") is False


# ---------------------------------------------------------------------------
# emerge 통합: force_emerge=True + 충분한 주제 수 -> 창발 노트 생성.
# ---------------------------------------------------------------------------


class TestEmergeIntegration:
    def test_force_emerge_generates_emerged_notes_with_enough_topics(
        self, settings: Settings
    ):
        notes_dir = settings.obsidian_vault / "20-notes"
        notes_dir.mkdir(parents=True)
        for slug in ("topic-a", "topic-b", "topic-c"):
            (notes_dir / f"{slug}.md").write_text(
                f"---\ntype: topic\ndate: 2025-01-01\n---\n# {slug}\n\n"
                "## 2025-01-01\n- 항목\n",
                encoding="utf-8",
            )

        def _fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
            assert set(topics) == {"topic-a", "topic-b", "topic-c"}
            return [
                {
                    "title": "새로운 통찰",
                    "insight": "세 주제가 만나는 지점",
                    "examples": ["사례1"],
                    "next_steps": ["다음 스텝1"],
                    "evidence": [{"topic": "topic-a", "mention_count": 2}],
                }
            ]

        run = run_pipeline(
            settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=_make_transcribe_fn(),
            extract_fn=_make_extract_fn([]),
            emerge_fn=_fake_emerge,
            force_emerge=True,
            today="2025-02-01",
        )

        idea_files = list((settings.obsidian_vault / IDEAS_SUBDIR).glob("*_idea_*.md"))
        assert len(idea_files) == 1
        assert "새로운 통찰" in idea_files[0].read_text(encoding="utf-8")
        assert run.run_date == "2025-02-01"

    def test_insufficient_topics_skips_emerge_without_calling_emerge_fn(
        self, settings: Settings
    ):
        def _emerge_fn_should_not_be_called(_prior_ideas, _topics):
            raise AssertionError("주제가 부족하면 emerge_fn 이 호출되면 안 된다.")

        run_pipeline(
            settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=_make_transcribe_fn(),
            extract_fn=_make_extract_fn([]),
            emerge_fn=_emerge_fn_should_not_be_called,
            force_emerge=True,
            today="2025-02-01",
        )

        # 이 테스트는 입력 녹음 없이 창발 조건만 확인한다. 전사 완료 건이 없으므로
        # 녹음별 메모도 만들지 않는다.
        assert not (settings.obsidian_vault / IDEAS_SUBDIR).exists()


# ---------------------------------------------------------------------------
# CLI 진입점: argparse(--force-emerge, --date) -> run_pipeline 위임.
# ---------------------------------------------------------------------------


class TestCli:
    @pytest.fixture(autouse=True)
    def _isolate_users(self, monkeypatch, settings: Settings):
        """실제 ~/.thinktank/users.json 을 읽지 않게 막는다.

        안 막으면 이 PC 에 설정된 진짜 사용자(와 그 사람의 실제 경로)가 테스트로
        새어 들어온다.
        """
        from thinktank.users import User

        monkeypatch.setattr(
            thinktank.main,
            "load_users",
            lambda base: [User(name="", token="t", settings=base)],
        )

    def test_main_parses_force_emerge_and_date_and_delegates(
        self, monkeypatch, settings: Settings
    ):
        captured: dict = {}

        monkeypatch.setattr(thinktank.main, "load_settings", lambda: settings)

        def _fake_run_pipeline(passed_settings, **kwargs):
            captured["settings"] = passed_settings
            captured["kwargs"] = kwargs
            return "sentinel-report"

        monkeypatch.setattr(thinktank.main, "run_pipeline", _fake_run_pipeline)

        result = main(["--force-emerge", "--date", "2025-03-01"])

        assert result == "sentinel-report"
        assert captured["settings"] is settings
        assert captured["kwargs"] == {"today": "2025-03-01", "force_emerge": True}

    def test_main_defaults_no_force_emerge_and_no_date(
        self, monkeypatch, settings: Settings
    ):
        captured: dict = {}

        monkeypatch.setattr(thinktank.main, "load_settings", lambda: settings)
        monkeypatch.setattr(
            thinktank.main,
            "run_pipeline",
            lambda passed_settings, **kwargs: captured.update(kwargs=kwargs),
        )

        main([])

        assert captured["kwargs"] == {"today": None, "force_emerge": False}


# ---------------------------------------------------------------------------
# AI_PROVIDER=claude_cli: extract_fn/emerge_fn 미주입 시 CLI 로더 경로를 탄다.
# ---------------------------------------------------------------------------


class TestAiProviderSelection:
    def test_settings_default_ai_provider_does_not_break_construction(
        self, settings: Settings
    ):
        """ai_provider 미지정 시 기존 Settings(...) 생성 코드가 깨지지 않는다."""
        assert settings.ai_provider == "api"

    def test_run_pipeline_uses_cli_extractor_and_emerger_when_provider_is_claude_cli(
        self, monkeypatch, settings: Settings
    ):
        import dataclasses

        cli_settings = dataclasses.replace(settings, ai_provider="claude_cli")

        calls: dict = {"extractor": False, "emerger": False}

        def _fake_load_cli_extractor(*args, **kwargs):
            calls["extractor"] = True

            def _extract(_text: str) -> list:
                return []

            return _extract

        def _fake_load_cli_emerger(*args, **kwargs):
            calls["emerger"] = True

            def _emerge(_topics: dict) -> list:
                return []

            return _emerge

        monkeypatch.setattr(
            thinktank.main, "load_cli_extractor", _fake_load_cli_extractor
        )
        monkeypatch.setattr(thinktank.main, "load_cli_emerger", _fake_load_cli_emerger)

        def _fail_if_called(*_args, **_kwargs):
            raise AssertionError("claude_cli provider 는 API 로더를 호출하면 안 된다.")

        monkeypatch.setattr(thinktank.main, "load_extractor", _fail_if_called)
        monkeypatch.setattr(thinktank.main, "load_emerger", _fail_if_called)

        run_pipeline(
            cli_settings,
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=_make_transcribe_fn(),
            force_emerge=True,
            today="2025-02-01",
        )

        assert calls["extractor"] is True
        assert calls["emerger"] is True


# ---------------------------------------------------------------------------
# 노트 날짜 귀속: 실행일이 아니라 녹음일(recorded_at) 기준으로 묶인다.
# ---------------------------------------------------------------------------


class TestDateAttribution:
    def _kwargs(self, text_map=None):
        return dict(
            detect_speech=_fake_detect_speech,
            cut_audio=_fake_cut_audio,
            transcribe_fn=_make_transcribe_fn(text_map),
            extract_fn=_make_extract_fn(["파이프라인"]),
            emerge_fn=_fake_emerge_fn,
        )

    def test_밀린_여러_날짜가_각_녹음일_노트로_흩어진다(self, settings: Settings):
        """통화녹음처럼 며칠치가 한꺼번에 들어와도 날짜별로 나뉘어야 한다."""
        _write_audio(settings.ingest_dir, "rec_20260713_090000.m4a")
        _write_audio(settings.ingest_dir, "rec_20260715_090000.m4a")
        _write_audio(settings.ingest_dir, "rec_20260719_090000.m4a")

        run_pipeline(settings, **self._kwargs())

        daily = settings.obsidian_vault / "10-daily"
        assert (daily / "2026-07-13.md").is_file()
        assert (daily / "2026-07-15.md").is_file()
        assert (daily / "2026-07-19.md").is_file()

    def test_실행일_노트는_만들어지지_않는다(self, settings: Settings):
        # 녹음일이 실행일과 다르면 실행일 노트가 생기면 안 된다.
        _write_audio(settings.ingest_dir, "rec_20260713_090000.m4a")

        run_pipeline(settings, **self._kwargs())

        assert not (
            settings.obsidian_vault / "10-daily" / f"{_today()}.md"
        ).exists(), "실행일로 묶이면 밀린 자료가 엉뚱한 날짜에 쌓인다"

    def test_나중에_같은_날짜가_더_들어와도_기존_항목이_남는다(
        self, settings: Settings
    ):
        """노트를 통째로 덮어쓰므로, 그 날짜 전체를 DB 에서 복원해야 한다."""
        _write_audio(settings.ingest_dir, "rec_20260713_090000.m4a")
        run_pipeline(
            settings, **self._kwargs({"rec_20260713_090000": "첫번째 FIRST"})
        )

        _write_audio(settings.ingest_dir, "rec_20260713_140000.m4a")
        run_pipeline(
            settings, **self._kwargs({"rec_20260713_140000": "두번째 SECOND"})
        )

        text = daily_all_text(settings.obsidian_vault, "2026-07-13")
        assert "rec_20260713_090000.m4a" in text, "이전 배치 항목이 사라졌다"
        assert "rec_20260713_140000.m4a" in text

    def test_주제_노트도_녹음일로_기록된다(self, settings: Settings):
        _write_audio(settings.ingest_dir, "rec_20260713_090000.m4a")

        run_pipeline(settings, **self._kwargs())

        topic = (settings.obsidian_vault / "20-notes" / "파이프라인.md").read_text(
            encoding="utf-8"
        )
        assert "## 2026-07-13" in topic
        assert f"## {_today()}" not in topic

    def test_재생성은_모든_녹음일_노트를_다시_쓴다(self, settings: Settings):
        _write_audio(settings.ingest_dir, "rec_20260713_090000.m4a")
        _write_audio(settings.ingest_dir, "rec_20260715_090000.m4a")
        run_pipeline(settings, **self._kwargs())

        daily = settings.obsidian_vault / "10-daily"
        for path in daily.glob("*.md"):
            path.unlink()

        dates = thinktank.main.rebuild_daily_notes(settings)

        assert set(dates) == {"2026-07-13", "2026-07-15"}
        assert (daily / "2026-07-13.md").is_file()
        assert (daily / "2026-07-15.md").is_file()
