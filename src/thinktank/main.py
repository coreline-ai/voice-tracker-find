# @TASK P5-T5.1 - 배치 오케스트레이션 (전체 파이프라인 통합)
# @SPEC docs/planning/06-tasks.md#P5-T5.1
# @TEST tests/test_main.py
"""전체 배치 파이프라인을 오케스트레이션한다.

ingest -> vad -> transcribe -> extract -> organize -> report -> log -> cleanup
순서로 각 모듈의 배치 함수를 그대로 호출하고, organize 단계(아카이브/데일리/주제
노트 생성 + extracted -> organized 전이)만 이 모듈이 새로 구현한다(현재 어느
모듈도 이 전이를 수행하지 않는다). 30일 주기 창발(emerge)도 조건부로 마지막에
실행한다.

각 단계는 상태 기반(recordings.status)으로 pending/failed 건만 처리하므로
배치를 재실행해도 안전하다(멱등). 한 레코딩의 실패가 배치 전체를 막지 않는
기존 run_*_batch 패턴을 organize 단계에도 동일하게 적용한다.

torch/faster-whisper/anthropic 등 무거운 의존성이 필요한 함수는 모두 DI로
주입받으며, 인자가 None 이면 `python -m thinktank.main` 실행 경로(또는 호출자가
직접 요청한 경우)에서만 실 모델/API 로더(load_*)로 지연 생성한다. 단위/통합
테스트는 이 인자들에 항상 fake 함수를 주입해 무거운 의존성 없이 통과한다.
"""

from __future__ import annotations

import argparse
import logging
import re
from collections import defaultdict
from datetime import UTC, datetime
from datetime import date as _date
from pathlib import Path

from thinktank.cleanup import run_cleanup
from thinktank.config import Settings, load_settings
from thinktank.db import (
    Recording,
    Status,
    get_recorded_dates,
    get_recordings,
    get_recordings_recorded_on,
    get_stats_recorded_on,
    init_db,
    update_recording_status,
)
from thinktank.emerge import EmergeFn, load_cli_emerger, load_emerger, run_emerge
from thinktank.extract import (
    ExtractFn,
    items_from_json,
    load_cli_extractor,
    load_extractor,
    run_extract_batch,
)
from thinktank.ingest import sync_ingest_folder
from thinktank.lock import AlreadyRunning, PipelineLock
from thinktank.notes.archive import Segment, Transcript, write_archive_note
from thinktank.notes.daily import write_daily_note
from thinktank.notes.emerged import IDEAS_SUBDIR, write_emerged_notes
from thinktank.notes.recording_memo import write_recording_memo
from thinktank.notes.pipeline_log import append_pipeline_log
from thinktank.report import PipelineRun, build_run_report
from thinktank.topics import merge_topics
from thinktank.transcribe import TranscribeFn, load_transcriber, run_transcribe_batch
from thinktank.users import load_users
from thinktank.vad import (
    CutAudioFn,
    DetectSpeechFn,
    load_speech_detector,
    remove_silence,
    run_vad_batch,
)

logger = logging.getLogger(__name__)

_TIMESTAMP_FORMAT = "%Y-%m-%d %H:%M:%S"

# 창발 실행 간격(일). 사용자 요청으로 매일 실행(=1)한다 — "일정·업무 정리보다
# 하루 요약·아이디어 생성에 집중"하는 제품 방향에 맞춘 설정.
EMERGE_INTERVAL_DAYS = 1

# transcribe.render_transcript_text 가 만든 `[MM:SS-MM:SS] "텍스트"` 줄 형식.
_SEGMENT_LINE_RE = re.compile(r'^\[(?P<start>[^\]]+)-(?P<end>[^\]]+)\] "(?P<text>.*)"$')
_IDEA_FILENAME_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})_idea_\d+\.md$")


def should_run_emerge(vault_path: str | Path, today: str) -> bool:
    """30-ideas/ 최신 노트 날짜 기준 EMERGE_INTERVAL_DAYS일 이상 지났거나 폴더가
    없으면 True (현재 간격=1이라 하루만 지나도 실행).

    Args:
        vault_path: Obsidian 볼트 루트 경로.
        today: 판단 기준 날짜 (YYYY-MM-DD).

    Returns:
        창발을 실행해야 하면 True.
    """
    ideas_dir = Path(vault_path).expanduser() / IDEAS_SUBDIR
    if not ideas_dir.is_dir():
        return True

    dates = [
        match.group(1)
        for path in ideas_dir.glob("*.md")
        if (match := _IDEA_FILENAME_RE.match(path.name))
    ]
    if not dates:
        return True

    elapsed = (_date.fromisoformat(today) - _date.fromisoformat(max(dates))).days
    return elapsed >= EMERGE_INTERVAL_DAYS


def _reconstruct_transcript(recording: Recording, temp_dir: str | Path) -> Transcript:
    """temp_dir 의 전사 텍스트(.txt, transcribe.save_transcript_text 산출물)와
    recordings 필드로 write_archive_note 가 필요로 하는 Transcript 를 재구성한다.
    """
    stem = Path(recording.source_path).stem
    text = (Path(temp_dir) / f"{stem}.txt").read_text(encoding="utf-8")

    segments = [
        Segment(start=match["start"], end=match["end"], text=match["text"])
        for line in text.splitlines()
        if (match := _SEGMENT_LINE_RE.match(line))
    ]
    return Transcript(
        source_file=recording.filename,
        date=(recording.recorded_at or "")[:10],
        recorded_at=recording.recorded_at or "",
        file_size=recording.file_size or 0,
        segments=segments,
    )


def _organize(settings: Settings, run_date: str) -> None:
    """extracted 레코딩을 아카이브/데일리/주제 노트로 정리하고 organized 로 전이한다.

    레코딩 한 건의 처리 실패는 해당 레코딩을 extracted 상태로 남겨(다음 배치에서
    자동 재시도) 배치 전체를 막지 않는다 (기존 run_*_batch 부분 실패 패턴과 동일).

    노트는 **녹음일(recorded_at) 기준**으로 묶는다. 실행일 기준이 아니다 — 밀려
    있던 한 주치가 한꺼번에 들어와도 각 녹음일 노트로 흩어져야 "07-15 노트를
    열면 07-15에 말한 것"이 된다. 90-archive 가 이미 녹음일 기준이라 기준도 통일된다.

    데일리 노트는 이번 배치 items만으로 쓰면 같은 날 이전 배치에서 이미
    organized된 항목이 재작성 시 유실된다(write_daily_note가 통째로 덮어씀).
    따라서 write_daily_note 호출 전 get_recordings_recorded_on 으로 그 날짜에
    녹음된 전체 레코딩의 extracted_json을 복원해 합산한 items/sources를 전달한다
    (주제 노트는 merge_topics가 기존 파일과 병합하므로 배치 items만 전달해도
    유실이 없다).
    """
    batch_by_date: dict[str, list] = defaultdict(list)
    sources_by_date: dict[str, list[str]] = defaultdict(list)

    for recording in get_recordings(settings.db_path, Status.EXTRACTED):
        try:
            transcript = _reconstruct_transcript(recording, settings.temp_dir)
            write_archive_note(transcript, settings.obsidian_vault)
            items = items_from_json(recording.extracted_json or "")
            write_recording_memo(transcript, items, settings.obsidian_vault)

            now = datetime.now(UTC).strftime(_TIMESTAMP_FORMAT)
            update_recording_status(
                settings.db_path, recording.id, Status.ORGANIZED, now
            )
        except Exception as exc:  # noqa: BLE001 - 한 건 실패가 배치 전체를 막으면 안 됨
            logger.error(
                "정리(organize) 실패 (id=%s, filename=%s): %s",
                recording.id,
                recording.filename,
                exc,
            )
            continue

        # 녹음 시각을 못 읽은 건은 실행일로 떨어뜨린다(노트에서 사라지지 않게).
        date = (recording.recorded_at or "")[:10] or run_date
        batch_by_date[date].extend(items)
        source = f"{recording.filename} ({transcript.date})"
        if source not in sources_by_date[date]:
            sources_by_date[date].append(source)

    for date in sorted(batch_by_date):
        # 주제 노트: 기존 파일과 병합되므로 이번 배치 항목만 넘긴다.
        merge_topics(
            batch_by_date[date],
            settings.obsidian_vault,
            date,
            sources=sources_by_date[date],
        )
        _write_daily_for(settings, date)


def _write_daily_for(settings: Settings, date: str) -> None:
    """그 날짜에 녹음된 전체를 복원해 데일리 노트를 다시 쓴다."""
    day_items = []
    day_sources: list[str] = []
    for recording in get_recordings_recorded_on(settings.db_path, date):
        day_items.extend(items_from_json(recording.extracted_json or ""))
        day_source = f"{recording.filename} ({(recording.recorded_at or '')[:10]})"
        if day_source not in day_sources:
            day_sources.append(day_source)

    stats = get_stats_recorded_on(settings.db_path, date)
    write_daily_note(date, day_items, stats, day_sources, settings.obsidian_vault)


def _backfill_recording_memos(settings: Settings) -> None:
    """기존 organized 녹음 중 메모가 없는 최근 전사를 한 번 보완한다.

    메모 저장 함수가 기존 파일을 덮어쓰지 않으므로, 매 배치에서 호출해도 사용자가
    수정한 메모의 mtime/content를 바꾸지 않는다. cleanup으로 전사 텍스트가 사라진
    오래된 레코딩은 경고만 남기고 다음 건을 계속 처리한다.
    """
    for recording in get_recordings(settings.db_path, Status.ORGANIZED):
        try:
            transcript = _reconstruct_transcript(recording, settings.temp_dir)
            items = items_from_json(recording.extracted_json or "")
            write_recording_memo(transcript, items, settings.obsidian_vault)
        except (OSError, ValueError) as exc:
            logger.warning(
                "녹음 메모 백필 건너뜀 (id=%s, filename=%s): %s",
                recording.id,
                recording.filename,
                exc,
            )


def rebuild_daily_notes(settings: Settings) -> list[str]:
    """DB 에 있는 모든 녹음일의 데일리 노트를 다시 쓴다.

    기준을 처리일에서 녹음일로 바꾼 뒤, 이미 만들어진 노트를 새 기준으로 맞추기
    위한 것이다. 원본 데이터는 DB 에 그대로 있으므로 언제든 다시 만들 수 있다.

    Returns:
        다시 쓴 날짜 목록.
    """
    dates = get_recorded_dates(settings.db_path)
    for date in dates:
        _write_daily_for(settings, date)
        logger.info("데일리 노트 재생성: %s", date)
    return dates


def _make_extractor(settings: Settings) -> ExtractFn:
    """settings.ai_provider 에 따라 CLI 또는 API 추출기를 생성한다."""
    if settings.ai_provider == "claude_cli":
        return load_cli_extractor()
    return load_extractor(settings.claude_api_key)


def _make_emerger(settings: Settings) -> EmergeFn:
    """settings.ai_provider 에 따라 CLI 또는 API 창발기를 생성한다."""
    if settings.ai_provider == "claude_cli":
        return load_cli_emerger()
    return load_emerger(settings.claude_api_key)


def run_pipeline(
    settings: Settings,
    *,
    detect_speech: DetectSpeechFn | None = None,
    cut_audio: CutAudioFn | None = None,
    transcribe_fn: TranscribeFn | None = None,
    extract_fn: ExtractFn | None = None,
    emerge_fn: EmergeFn | None = None,
    today: str | None = None,
    force_emerge: bool = False,
) -> PipelineRun:
    """전체 배치 파이프라인 1회를 실행한다.

    순서: ingest -> vad -> transcribe -> extract -> organize -> report -> log
    -> cleanup, 그리고 조건부 emerge. 각 단계는 상태 기반(recordings.status)
    이라 재실행해도 안전하다(멱등) - pending/failed 건만 처리되고, 이미 처리된
    건은 다시 처리되지 않는다.

    Args:
        settings: 검증된 파이프라인 설정 (config.load_settings 결과).
        detect_speech: VAD 발화 검출 함수. None 이면 vad.load_speech_detector 로
            지연 생성한다.
        cut_audio: 발화 구간만 남기는 함수. None 이면 vad.remove_silence.
        transcribe_fn: 전사 함수. None 이면 transcribe.load_transcriber 로 지연
            생성한다.
        extract_fn: LLM 추출 함수. None 이면 settings.ai_provider 에 따라
            extract.load_extractor(api) 또는 extract.load_cli_extractor
            (claude_cli) 로 지연 생성한다.
        emerge_fn: 창발 함수. None 이면 settings.ai_provider 에 따라
            emerge.load_emerger(api) 또는 emerge.load_cli_emerger(claude_cli)
            로 지연 생성한다 (창발이 실제로 실행되는 경우에만 생성되어 불필요한
            API 클라이언트 생성을 피한다).
        today: 이번 실행의 기준 날짜(YYYY-MM-DD). None 이면 실행 시작 시각(UTC)의
            날짜를 사용한다.
        force_emerge: True 이면 30일 주기와 무관하게 창발을 강제 실행한다.

    Returns:
        build_run_report 가 만든 이번 실행의 집계 결과(PipelineRun).
    """
    with PipelineLock(settings.db_path):
        return _run_pipeline_locked(
            settings,
            detect_speech=detect_speech,
            cut_audio=cut_audio,
            transcribe_fn=transcribe_fn,
            extract_fn=extract_fn,
            emerge_fn=emerge_fn,
            today=today,
            force_emerge=force_emerge,
        )


def _run_pipeline_locked(
    settings: Settings,
    *,
    detect_speech: DetectSpeechFn | None = None,
    cut_audio: CutAudioFn | None = None,
    transcribe_fn: TranscribeFn | None = None,
    extract_fn: ExtractFn | None = None,
    emerge_fn: EmergeFn | None = None,
    today: str | None = None,
    force_emerge: bool = False,
) -> PipelineRun:
    """잠금을 잡은 뒤의 실제 배치 본문 (:func:`run_pipeline` 참조)."""
    started_at = datetime.now(UTC)
    run_date = today or started_at.strftime("%Y-%m-%d")

    init_db(settings.db_path)

    sync_ingest_folder(settings.ingest_dir, settings.db_path)

    detect_speech = detect_speech or load_speech_detector(
        settings.vad_sample_rate, settings.vad_threshold
    )
    run_vad_batch(
        settings.db_path, settings.temp_dir, detect_speech, cut_audio or remove_silence
    )

    transcribe_fn = transcribe_fn or load_transcriber(
        settings.whisper_model, device=settings.whisper_device
    )
    run_transcribe_batch(settings.db_path, settings.temp_dir, transcribe_fn)

    extract_fn = extract_fn or _make_extractor(settings)
    run_extract_batch(settings.db_path, settings.temp_dir, extract_fn)

    _organize(settings, run_date)
    _backfill_recording_memos(settings)

    finished_at = datetime.now(UTC)
    run = build_run_report(settings.db_path, run_date, started_at, finished_at)
    append_pipeline_log(run, settings.obsidian_vault)

    run_cleanup(
        settings.db_path,
        settings.ingest_dir,
        settings.temp_dir,
        settings.obsidian_vault,
        settings.retention_days,
    )

    if force_emerge or should_run_emerge(settings.obsidian_vault, run_date):
        emerge_fn = emerge_fn or _make_emerger(settings)
        ideas = run_emerge(settings.obsidian_vault, emerge_fn, run_date)
        write_emerged_notes(ideas, settings.obsidian_vault)

    return run


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="thinktank 배치 파이프라인 실행")
    parser.add_argument(
        "--force-emerge",
        action="store_true",
        help="30일 주기와 무관하게 창발(emerge)을 강제 실행",
    )
    parser.add_argument(
        "--date", default=None, help="실행 기준 날짜 (YYYY-MM-DD, 기본: 오늘)"
    )
    parser.add_argument(
        "--rebuild-daily",
        action="store_true",
        help="파이프라인을 돌리지 않고, DB 의 모든 녹음일 데일리 노트만 다시 쓴다",
    )
    parser.add_argument(
        "--user",
        default=None,
        help="이 사용자만 처리 (기본: users.json 의 전원)",
    )
    parser.add_argument(
        "--normalize",
        action="store_true",
        help="파이프라인 뒤에 증분 정규화(새 주제만 기존 클러스터에 배정)를 실행",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> PipelineRun | None:
    """CLI 진입점: 사용자별로 배치 파이프라인을 1회씩 실행한다.

    users.json 이 없으면 기존처럼 단일 사용자 1회 실행이다. 여러 사용자면 각자의
    수집 폴더/볼트/DB 로 따로 돌린다 — 한 사람의 실패가 다른 사람을 막지 않는다.

    Returns:
        마지막으로 실행한 사용자의 결과 (단일 사용자면 그 사용자의 결과).
    """
    args = _parse_args(argv)
    base = load_settings()
    users = load_users(base)

    if args.user is not None:
        users = [user for user in users if user.name == args.user]
        if not users:
            raise SystemExit(f"users.json 에 없는 사용자입니다: {args.user!r}")

    last: PipelineRun | None = None
    for user in users:
        label = user.name or "(기본)"
        try:
            if args.rebuild_daily:
                dates = rebuild_daily_notes(user.settings)
                logger.info("[%s] 데일리 노트 %d일치 재생성 완료", label, len(dates))
                continue
            logger.info("[%s] 파이프라인 시작", label)
            last = run_pipeline(
                user.settings, today=args.date, force_emerge=args.force_emerge
            )
            if args.normalize:
                from thinktank.vault_incremental import (
                    _robust_cli_llm,
                    normalize_incremental,
                )

                stats = normalize_incremental(
                    user.settings.obsidian_vault, _robust_cli_llm
                )
                logger.info(
                    "[%s] 증분 정규화: 새 주제 %d개, 허브 %d개 갱신",
                    label,
                    stats["new"],
                    stats["hubs"],
                )
        except AlreadyRunning as exc:
            # 다른 프로세스가 처리 중이면 건너뛴다. 상태머신 기반이라 다음 실행이
            # 이어받으므로 잃는 것이 없다.
            logger.warning("[%s] 건너뜀 — %s", label, exc)
        except Exception:
            # 한 사용자의 실패가 나머지를 막으면 안 된다. 다음 배치에서 재시도된다.
            logger.exception("[%s] 파이프라인 실패", label)
    return last


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    main()
