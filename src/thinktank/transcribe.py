# @TASK P2-R1-T2 - STT 전사 (faster-whisper large-v3, CUDA 가속)
# @SPEC docs/planning/06-tasks.md#P2-R1-T2
# @TEST tests/test_transcribe.py
"""vad_done 상태 녹음의 VAD 처리된 오디오를 faster-whisper로 전사한다.

faster-whisper/torch/CUDA 는 수 GB급 의존성이므로 이 모듈 최상단에서는 import
하지 않는다. 실 모델 로딩(:func:`load_transcriber`)만 함수 본문 안에서 지연
import 하며, 단위 테스트는 이를 대체하는 fake 함수를 :func:`transcribe_audio`/
:func:`run_transcribe_batch` 에 주입해 faster-whisper 없이 통과한다. 실 모델
동작 검증은 P5 E2E 테스트에서 수행한다.

장시간 오디오 청크 분할은 faster-whisper 모델 내부(VAD 기반 윈도우 처리)에
위임하고, 실패 재시도는 transcribed_failed 상태 + get_retry_targets 를 통한
다음 배치 재처리로 처리한다 (04-database-design.md §1 상태 전이도).
"""

from __future__ import annotations

import logging
import os
import sys
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from thinktank.config import DEFAULT_WHISPER_MODEL
from thinktank.db import (
    Recording,
    Status,
    get_recordings,
    get_retry_targets,
    update_recording_status,
)
from thinktank.notes.archive import Segment, Transcript

logger = logging.getLogger(__name__)

_TIMESTAMP_FORMAT = "%Y-%m-%d %H:%M:%S"


@dataclass(frozen=True)
class RawSegment:
    """모델이 반환하는 세그먼트 1건 (원본 오디오 기준 초 단위 시작/종료 시각)."""

    start: float
    end: float
    text: str


TranscribeFn = Callable[[Path], list[RawSegment]]


def format_timestamp(seconds: float) -> str:
    """초 단위 타임스탬프를 "MM:SS" 문자열로 변환한다 (소수점 이하 버림).

    Args:
        seconds: 초 단위 타임스탬프.

    Returns:
        "MM:SS" 형식 문자열. 분은 60 이상이어도 시:분 변환 없이 그대로 표기한다.
    """
    total_seconds = int(seconds)
    minutes, secs = divmod(total_seconds, 60)
    return f"{minutes:02d}:{secs:02d}"


def _derive_date(recorded_at: str | None) -> str:
    """recorded_at("YYYY-MM-DD HH:MM:SS")에서 날짜 부분만 추출한다."""
    if not recorded_at:
        raise ValueError("recorded_at 이 없는 레코딩은 전사할 수 없습니다.")
    return recorded_at[:10]


def _add_cuda_dll_dirs() -> None:
    """Windows에서 nvidia pip 패키지(cuDNN/cuBLAS)의 DLL 검색 경로를 등록한다.

    CTranslate2(faster-whisper GPU)가 cublas64_12.dll / cudnn 을 찾도록 한다.
    Windows 가 아니거나 nvidia 패키지가 없으면(=CPU 전용) 아무 것도 하지 않는다.
    """
    if sys.platform != "win32":
        return
    nvidia = Path(sys.prefix) / "Lib" / "site-packages" / "nvidia"
    if not nvidia.is_dir():
        return
    for sub in nvidia.iterdir():
        bin_dir = sub / "bin"
        if bin_dir.is_dir():
            os.add_dll_directory(str(bin_dir))


def load_transcriber(
    model_size: str = DEFAULT_WHISPER_MODEL,
    device: str | None = None,
    compute_type: str | None = None,
) -> TranscribeFn:
    """faster-whisper 모델을 로드해 '오디오 경로 -> RawSegment 목록' 함수를 만든다.

    faster-whisper/torch 를 여기서만 import 하므로, 이 함수를 호출하지 않는 한
    (예: 단위 테스트에서 fake 를 주입하는 경우) 무거운 의존성이 필요 없다.

    Args:
        model_size: Whisper 모델 크기 (기본: large-v3).
        device: "cuda" 또는 "cpu". None 이면 CUDA 사용 가능 여부를 자동 감지.
        compute_type: 연산 정밀도. None 이면 device 에 맞는 기본값 사용
            (cuda -> float16, cpu -> int8).

    Returns:
        오디오 파일 경로를 받아 :class:`RawSegment` 목록을 반환하는 함수.
    """
    from faster_whisper import WhisperModel

    if device is None:
        import torch

        device = "cuda" if torch.cuda.is_available() else "cpu"
    if device != "cuda":
        logger.warning("GPU를 사용할 수 없어 CPU로 전사합니다 (느림).")
    if compute_type is None:
        compute_type = "float16" if device == "cuda" else "int8"

    if device == "cuda":
        _add_cuda_dll_dirs()
    model = WhisperModel(model_size, device=device, compute_type=compute_type)

    def _transcribe(audio_path: Path) -> list[RawSegment]:
        segments, _info = model.transcribe(str(audio_path))
        return [
            RawSegment(start=seg.start, end=seg.end, text=seg.text.strip())
            for seg in segments
        ]

    return _transcribe


def render_transcript_text(transcript: Transcript) -> str:
    """전사 결과를 `[MM:SS-MM:SS] "텍스트"` 줄 목록의 텍스트로 렌더링한다."""
    if not transcript.segments:
        return ""
    lines = [
        f'[{segment.start}-{segment.end}] "{segment.text}"'
        for segment in transcript.segments
    ]
    return "\n".join(lines) + "\n"


def save_transcript_text(transcript: Transcript, temp_dir: str | Path) -> Path:
    """전사 텍스트 파일을 temp_dir 에 저장한다 (7일 후 cleanup 대상).

    Args:
        transcript: 저장할 전사 결과.
        temp_dir: 저장할 임시 폴더 (VAD 출력 오디오와 동일한 폴더).

    Returns:
        생성된 텍스트 파일 경로.
    """
    output_dir = Path(temp_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"{Path(transcript.source_file).stem}.txt"
    output_path.write_text(render_transcript_text(transcript), encoding="utf-8")
    return output_path


def transcribe_audio(
    recording: Recording,
    audio_path: str | Path,
    db_path: str | Path,
    temp_dir: str | Path,
    transcribe_fn: TranscribeFn,
) -> Transcript:
    """녹음 하나를 전사해 Transcript를 만들고 transcribed로 전이한다.

    실패 시 transcribed_failed로 전이하고 error_msg를 기록한 뒤 예외를 다시
    발생시킨다. 실패한 레코딩은 get_retry_targets로 조회되어 다음 배치에서
    재시도된다.

    Args:
        recording: vad_done 또는 transcribed_failed 상태의 레코딩.
        audio_path: VAD 처리된 오디오 파일 경로 (temp_dir 안에 위치).
        db_path: SQLite DB 파일 경로.
        temp_dir: 전사 텍스트 파일을 저장할 임시 폴더.
        transcribe_fn: 오디오 경로 -> RawSegment 목록 함수
            (실 모델은 :func:`load_transcriber`, 테스트는 fake 주입).

    Returns:
        생성된 Transcript.

    Raises:
        Exception: transcribe_fn 이 실패했을 때 (transcribed_failed로 전이 후 재발생).
    """
    audio_path = Path(audio_path)
    try:
        raw_segments = transcribe_fn(audio_path)
        transcript = Transcript(
            source_file=Path(recording.source_path).name,
            date=_derive_date(recording.recorded_at),
            recorded_at=recording.recorded_at or "",
            file_size=recording.file_size or 0,
            segments=[
                Segment(
                    start=format_timestamp(raw.start),
                    end=format_timestamp(raw.end),
                    text=raw.text,
                )
                for raw in raw_segments
            ],
        )
        save_transcript_text(transcript, temp_dir)
    except Exception as exc:
        now = datetime.now(UTC).strftime(_TIMESTAMP_FORMAT)
        update_recording_status(
            db_path, recording.id, Status.TRANSCRIBED_FAILED, now, error_msg=str(exc)
        )
        raise

    now = datetime.now(UTC).strftime(_TIMESTAMP_FORMAT)
    update_recording_status(db_path, recording.id, Status.TRANSCRIBED, now)
    return transcript


def run_transcribe_batch(
    db_path: str | Path,
    temp_dir: str | Path,
    transcribe_fn: TranscribeFn,
) -> list[Recording]:
    """vad_done + transcribed_failed 레코딩을 전사해 transcribed로 전이한다.

    실패한 레코딩은 transcribed_failed 상태로 남아 다음 배치 호출에서 자동으로
    재시도된다. 실패는 로그로 남긴다.

    Args:
        db_path: SQLite DB 파일 경로.
        temp_dir: VAD 출력 오디오가 있고 전사 텍스트를 저장할 임시 폴더.
        transcribe_fn: 오디오 경로 -> RawSegment 목록 함수.

    Returns:
        이번 호출에서 transcribed로 전이된 Recording 목록.
    """
    candidates = list(get_recordings(db_path, Status.VAD_DONE))
    candidates += [
        r for r in get_retry_targets(db_path) if r.status == Status.TRANSCRIBED_FAILED
    ]

    processed_ids: set[int] = set()
    for recording in candidates:
        audio_path = Path(temp_dir) / Path(recording.source_path).name
        try:
            transcribe_audio(recording, audio_path, db_path, temp_dir, transcribe_fn)
        except Exception as exc:  # noqa: BLE001 - 한 건 실패가 배치 전체를 막으면 안 됨
            logger.error(
                "전사 실패 (id=%s, path=%s): %s",
                recording.id,
                recording.source_path,
                exc,
            )
            continue
        processed_ids.add(recording.id)

    return [
        recording
        for recording in get_recordings(db_path, Status.TRANSCRIBED)
        if recording.id in processed_ids
    ]
