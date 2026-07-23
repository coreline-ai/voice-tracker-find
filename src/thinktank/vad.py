# @TASK P2-R1-T1 - VAD 처리 (Silero VAD 무음 제거)
# @SPEC docs/planning/06-tasks.md#P2-R1-T1
# @TEST tests/test_vad.py
"""synced 상태 녹음 파일에서 무음 구간을 제거한다 (Silero VAD).

torch/silero-vad/pydub 는 수 GB급 의존성이므로 이 모듈 최상단에서는 import
하지 않는다. 실 모델 로딩(:func:`load_speech_detector`)과 실 오디오 편집
(:func:`remove_silence`)은 함수 본문 안에서만 지연 import 하며, 단위 테스트는
이 두 함수를 대체하는 fake 함수를 :func:`process_vad`/:func:`run_vad_batch` 에
주입해 torch 없이 통과한다. 실 모델 동작 검증은 P5 E2E 테스트에서 수행한다.

발화가 없는 무음 레코딩은 no_speech(종료) 상태로 전이되어 재시도되지 않는다
(cleanup 이 유예 기간 후 원본을 폐기한다). 반면 처리 오류(디코드 실패 등)가 난
레코딩은 상태를 바꾸지 않고 synced 로 남겨 다음 배치에서 자동으로 재시도된다.
"""

from __future__ import annotations

import logging
import shutil
import subprocess
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from thinktank.config import DEFAULT_VAD_SAMPLE_RATE, DEFAULT_VAD_THRESHOLD
from thinktank.db import Recording, Status, get_recordings, update_recording_status

logger = logging.getLogger(__name__)

_TIMESTAMP_FORMAT = "%Y-%m-%d %H:%M:%S"

# 하루 3~8시간 녹음을 한 번에 메모리에 올리지 않고 청크 단위로 VAD를
# 수행하기 위한 기본 청크 길이(초).
VAD_CHUNK_SECONDS = 600  # 10분


@dataclass(frozen=True)
class VadSegment:
    """발화 구간 하나 (원본 오디오 기준 초 단위 시작/종료 시각)."""

    start: float
    end: float


Segments = list[VadSegment]
DetectSpeechFn = Callable[[Path], Segments]
CutAudioFn = Callable[[Path, Path, Segments], None]


def iter_time_chunks(
    duration_seconds: float, chunk_seconds: float = VAD_CHUNK_SECONDS
) -> list[tuple[float, float]]:
    """총 길이를 chunk_seconds 단위 구간으로 나눈다 (마지막 구간은 남은 길이만).

    Args:
        duration_seconds: 오디오 전체 길이(초).
        chunk_seconds: 청크 하나의 길이(초).

    Returns:
        (시작, 종료) 튜플 목록. duration_seconds 가 0 이하이면 빈 목록.
    """
    if duration_seconds <= 0:
        return []

    chunks: list[tuple[float, float]] = []
    start = 0.0
    while start < duration_seconds:
        end = min(start + chunk_seconds, duration_seconds)
        chunks.append((start, end))
        start = end
    return chunks


def _decode_audio(audio_path: Path, sample_rate: int):
    """ffmpeg 로 오디오를 mono float32 파형 텐서로 디코드한다.

    silero 의 read_audio 는 torchaudio 백엔드를 쓰는데, torchaudio 2.9+ 는
    별도 torchcodec(+FFmpeg 공유 라이브러리)을 요구해 Windows 정적 ffmpeg 환경에서
    깨진다. 대신 (이미 설치된) ffmpeg 실행 파일로 직접 디코드해 그 의존성을 없앤다.
    openai-whisper 의 load_audio 와 동일한 접근.
    """
    import numpy as np
    import torch

    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise RuntimeError("ffmpeg 명령을 찾을 수 없습니다 (오디오 디코드에 필요).")
    proc = subprocess.run(
        [
            ffmpeg,
            "-nostdin",
            "-i",
            str(audio_path),
            "-f",
            "f32le",
            "-ac",
            "1",
            "-ar",
            str(sample_rate),
            "-",
        ],
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"ffmpeg 디코드 실패: {proc.stderr.decode('utf-8', 'ignore')[-300:]}"
        )
    audio = np.frombuffer(proc.stdout, dtype="<f4").copy()
    return torch.from_numpy(audio)


def load_speech_detector(
    sample_rate: int = DEFAULT_VAD_SAMPLE_RATE,
    threshold: float = DEFAULT_VAD_THRESHOLD,
    chunk_seconds: float = VAD_CHUNK_SECONDS,
) -> DetectSpeechFn:
    """Silero VAD 모델을 로드해 '오디오 경로 -> 발화 구간 목록' 함수를 만든다.

    torch/silero-vad 를 여기서만 import 하므로, 이 함수를 호출하지 않는 한
    (예: 단위 테스트에서 fake 를 주입하는 경우) 무거운 의존성이 필요 없다.
    오디오 디코드는 torchaudio 대신 ffmpeg(:func:`_decode_audio`)로 수행한다.

    Args:
        sample_rate: VAD 입력 샘플링 레이트 (Hz).
        threshold: 발화로 판단할 확률 임계값.
        chunk_seconds: 긴 녹음을 나눠 처리할 청크 길이(초).

    Returns:
        오디오 파일 경로를 받아 :class:`VadSegment` 목록을 반환하는 함수.
    """
    from silero_vad import get_speech_timestamps, load_silero_vad

    model = load_silero_vad()

    def _detect(audio_path: Path) -> Segments:
        wav = _decode_audio(Path(audio_path), sample_rate)
        duration = len(wav) / sample_rate

        segments: Segments = []
        for chunk_start, chunk_end in iter_time_chunks(duration, chunk_seconds):
            start_sample = int(chunk_start * sample_rate)
            end_sample = int(chunk_end * sample_rate)
            chunk_timestamps = get_speech_timestamps(
                wav[start_sample:end_sample],
                model,
                sampling_rate=sample_rate,
                threshold=threshold,
                return_seconds=True,
            )
            segments.extend(
                VadSegment(start=chunk_start + ts["start"], end=chunk_start + ts["end"])
                for ts in chunk_timestamps
            )
        return segments

    return _detect


def remove_silence(audio_path: Path, output_path: Path, segments: Segments) -> None:
    """발화 구간만 이어붙여 output_path 에 저장한다.

    pydub(및 ffmpeg)이 필요하므로 실제 호출될 때만 import 한다.

    Args:
        audio_path: 원본 오디오 파일 경로.
        output_path: 결과를 저장할 경로 (상위 폴더가 없으면 생성).
        segments: 남길 발화 구간 목록 (초 단위).
    """
    from pydub import AudioSegment

    audio = AudioSegment.from_file(audio_path)
    output = AudioSegment.empty()
    for segment in segments:
        start_ms = int(segment.start * 1000)
        end_ms = int(segment.end * 1000)
        output += audio[start_ms:end_ms]

    output_path.parent.mkdir(parents=True, exist_ok=True)
    # ffmpeg 는 m4a/mp4 muxer 이름을 "ipod" 로 쓴다 (".m4a" -> "m4a" 는 인식 못함).
    suffix = output_path.suffix.lstrip(".").lower()
    export_format = {"m4a": "ipod", "m4b": "ipod", "mp4": "ipod"}.get(
        suffix, suffix or "wav"
    )
    output.export(output_path, format=export_format)


def process_vad(
    recording_id: int,
    audio_path: str | Path,
    db_path: str | Path,
    temp_dir: str | Path,
    detect_speech: DetectSpeechFn,
    cut_audio: CutAudioFn = remove_silence,
) -> Segments:
    """녹음 하나에 VAD를 적용해 무음을 제거하고 vad_done으로 전이한다.

    Args:
        recording_id: 처리할 레코딩 id (recordings.id).
        audio_path: synced 상태 원본 오디오 파일 경로.
        db_path: SQLite DB 파일 경로.
        temp_dir: 정제된 오디오를 저장할 임시 폴더.
        detect_speech: 오디오 경로 -> 발화 구간 목록 함수
            (실 모델은 :func:`load_speech_detector`, 테스트는 fake 주입).
        cut_audio: 발화 구간만 남긴 오디오를 생성하는 함수
            (기본: :func:`remove_silence`).

    Returns:
        검출된 발화 구간 목록. 발화가 하나도 없으면 빈 목록을 반환하고 레코딩을
        no_speech(종료) 상태로 전이한다 — 예외를 던지지 않는다.

    Raises:
        Exception: detect_speech/cut_audio 처리 오류(디코드 실패 등)는 그대로
            전파되어 호출자가 synced 로 남겨 다음 배치에서 재시도한다.
    """
    audio_path = Path(audio_path)
    segments = detect_speech(audio_path)
    if not segments:
        # 발화 없음은 오류가 아니라 정상적인 종료다(무음 청크). no_speech 로 전이해
        # 다음 배치에서 재시도되지 않게 하고(좀비 실패 방지), cleanup 이 유예 기간 후
        # 원본을 폐기하도록 둔다. 디코드 오류 등 실제 예외는 위 detect_speech 에서
        # 던져져 이 지점에 도달하지 않는다(호출자가 synced 로 남겨 재시도).
        now = datetime.now(UTC).strftime(_TIMESTAMP_FORMAT)
        update_recording_status(db_path, recording_id, Status.NO_SPEECH, now)
        logger.info("발화 없음 → no_speech (id=%s, path=%s)", recording_id, audio_path)
        return segments

    output_path = Path(temp_dir) / audio_path.name
    cut_audio(audio_path, output_path, segments)

    now = datetime.now(UTC).strftime(_TIMESTAMP_FORMAT)
    update_recording_status(db_path, recording_id, Status.VAD_DONE, now)
    return segments


def run_vad_batch(
    db_path: str | Path,
    temp_dir: str | Path,
    detect_speech: DetectSpeechFn,
    cut_audio: CutAudioFn = remove_silence,
) -> list[Recording]:
    """synced 상태의 모든 레코딩에 VAD를 적용하고 vad_done으로 전이한다.

    발화가 없는 무음 레코딩은 no_speech(종료)로 전이되어 재시도되지 않는다.
    처리 오류(디코드 실패 등)가 난 레코딩만 synced 로 남아 다음 배치에서
    자동으로 재시도되며, 오류는 로그로 남긴다.

    Args:
        db_path: SQLite DB 파일 경로.
        temp_dir: 정제된 오디오를 저장할 임시 폴더.
        detect_speech: 오디오 경로 -> 발화 구간 목록 함수.
        cut_audio: 발화 구간만 남긴 오디오를 생성하는 함수
            (기본: :func:`remove_silence`).

    Returns:
        이번 호출에서 vad_done으로 전이된 Recording 목록.
    """
    processed_ids: set[int] = set()
    for recording in get_recordings(db_path, Status.SYNCED):
        try:
            process_vad(
                recording.id,
                recording.source_path,
                db_path,
                temp_dir,
                detect_speech,
                cut_audio,
            )
        except Exception as exc:  # noqa: BLE001 - 한 건 실패가 배치 전체를 막으면 안 됨
            logger.error(
                "VAD 처리 실패 (id=%s, path=%s): %s",
                recording.id,
                recording.source_path,
                exc,
            )
            continue
        processed_ids.add(recording.id)

    return [
        recording
        for recording in get_recordings(db_path, Status.VAD_DONE)
        if recording.id in processed_ids
    ]
