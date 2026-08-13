#!/usr/bin/env python
# @HARNESS Level 2 - 실물 이음새(smoke) 검증
# @SPEC 테스트 전략: mock으로만 검증된 실물 어댑터 4개를 실제 입력으로 1회씩 실행
"""mock으로만 검증된 실물 어댑터를 실제 입력 하나로 순서대로 실행하는 스모크 하네스.

단위/통합 테스트(368개)는 전부 fake 주입이라 torch/whisper/Claude/실오디오를 한
번도 실행하지 않는다. 이 스크립트가 그 간극을 메운다. 4개 어댑터
(Silero VAD / faster-whisper / Claude 추출 / Claude 창발)를 **실제 파이프라인과
동일한 경로·포맷 규칙으로** 이어 실행해, 각 출력이 계약과 맞는지 + 모듈 간
이음새(VAD 출력→전사 입력, 전사 .txt→organize 재파싱)가 실제로 연결되는지 본다.

동작은 전부 임시 샌드박스(temp/·vault/)에서만 이뤄지며 사용자의 실제 볼트·DB는
건드리지 않는다.

사용법:
    python scripts/smoke_real.py path/to/clip.m4a       # 4단계 전부
    python scripts/smoke_real.py                        # 오디오 없이 Claude 단계만
    python scripts/smoke_real.py clip.m4a --keep        # 산출물 임시폴더 보존
    python scripts/smoke_real.py clip.m4a --model claude-haiku-4-5-20251001

전제:
    오디오 단계: uv pip install -e ".[audio]"   (silero-vad, pydub, faster-whisper)
    Claude 단계: uv pip install -e ".[llm]" + CLAUDE_API_KEY (.env 또는 환경변수)
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import shutil
import sys
import tempfile
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

# 이 스크립트는 editable 설치 여부와 무관하게 동작해야 하므로 src/ 를 경로에 추가.
_SRC = Path(__file__).resolve().parents[1] / "src"
if _SRC.is_dir() and str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from dotenv import load_dotenv  # noqa: E402  (경로 세팅 후 import)

from airvoice.config import (  # noqa: E402
    DEFAULT_VAD_SAMPLE_RATE,
    DEFAULT_VAD_THRESHOLD,
    DEFAULT_WHISPER_MODEL,
)
from airvoice.extract import ExtractedItem  # noqa: E402  (anthropic 미포함, 안전)

# 오디오 없이 3단계(추출)를 검증할 때 쓰는 canned 전사문.
# render_transcript_text 와 동일한 `[MM:SS-MM:SS] "텍스트"` 형식 — 실제 파이프라인이
# extract_fn 에 넘기는 텍스트와 같은 모양이라 프롬프트 충실도가 유지된다.
_SAMPLE_TRANSCRIPT = (
    '[00:00-00:12] "오늘 카페에서 새 제품 아이디어를 정리했다. '
    '음성 노트를 자동으로 옵시디언에 쌓는 파이프라인을 만들자."\n'
    '[00:12-00:20] "내일 오후 3시에 디자인 리뷰 미팅이 잡혔다."\n'
    '[00:20-00:35] "성능이 제일 중요하다는 피드백을 받았다. '
    '전사 속도를 꼭 개선해야 한다."\n'
)

# 4단계(창발) 검증용 canned 항목 — 서로 연결될 여지가 있는 4개 주제를 만든다
# (제품-방향 / 자동화 / 사용자-리서치 / 디자인 → min_topics=3 충족).
_EMERGE_ITEMS = [
    ExtractedItem("idea", "음성 노트를 자동으로 구조화하는 파이프라인",
                  ["제품 방향", "자동화"], ["#product"], None),
    ExtractedItem("idea", "사용자는 손 안 대는 완전 자동화를 원한다",
                  ["사용자 리서치", "자동화"], ["#research"], None),
    ExtractedItem("idea", "정보를 한 화면에 모아 보는 구조가 필요하다",
                  ["디자인", "제품 방향"], ["#design"], None),
]

_TODAY = datetime.now().strftime("%Y-%m-%d")


@dataclass
class StageResult:
    """단계 하나의 실행 결과."""

    name: str
    status: str  # "PASS" | "FAIL" | "SKIP" | "WARN"
    detail: str
    payload: object = field(default=None)  # 다음 단계로 넘길 값 (경로/텍스트 등)


def _has(module: str) -> bool:
    return importlib.util.find_spec(module) is not None


def _missing(module: str, extra: str) -> str:
    return f'{module} 미설치 → `uv pip install -e ".[{extra}]"` 후 재시도'


# --------------------------------------------------------------------------- #
# Stage 1: Silero VAD (load_speech_detector + remove_silence)
# --------------------------------------------------------------------------- #
def stage_vad(
    audio_path: Path,
    work_dir: Path,
    sample_rate: int = DEFAULT_VAD_SAMPLE_RATE,
    threshold: float = DEFAULT_VAD_THRESHOLD,
) -> StageResult:
    """실물 Silero VAD로 발화 구간을 검출하고 무음을 제거한다.

    실제 파이프라인(process_vad)과 동일하게 결과를 ``temp/{원본이름}`` 에 써서,
    2단계 전사가 읽는 경로 규칙과 실제로 맞물리는지까지 검증한다.
    """
    for mod in ("silero_vad", "pydub"):
        if not _has(mod):
            return StageResult("1. VAD", "SKIP", _missing(mod, "audio"))
    try:
        from airvoice.vad import load_speech_detector, remove_silence

        detect = load_speech_detector(sample_rate, threshold)
        segments = detect(audio_path)
        if not segments:
            return StageResult(
                "1. VAD", "FAIL", "발화 구간 0개 — 완전 무음이거나 임계값 문제"
            )
        for seg in segments:
            if not (0 <= seg.start < seg.end):
                return StageResult(
                    "1. VAD", "FAIL", f"세그먼트 시각 계약 위반: {seg}"
                )

        # 실제 파이프라인의 경로 규칙(temp_dir/원본이름)을 그대로 재현.
        temp_dir = work_dir / "temp"
        out_path = temp_dir / audio_path.name
        try:
            remove_silence(audio_path, out_path, segments)
        except Exception as exc:  # noqa: BLE001
            return StageResult(
                "1. VAD", "FAIL",
                f"무음 제거/내보내기 실패 ({audio_path.suffix}): "
                f"{type(exc).__name__}: {exc}  "
                f"(ffmpeg/코덱 확인, 또는 원본을 .wav로 시도)",
            )
        if not out_path.exists() or out_path.stat().st_size == 0:
            return StageResult("1. VAD", "FAIL", "정제 오디오가 비어 있음")

        speech = sum(s.end - s.start for s in segments)
        detail = (
            f"발화 {len(segments)}구간 / 총 {speech:.1f}초 → "
            f"{out_path.name} ({out_path.stat().st_size // 1024}KB)"
        )
        return StageResult("1. VAD", "PASS", detail, payload=out_path)
    except ImportError as exc:
        return StageResult("1. VAD", "SKIP", f"의존성 로드 실패: {exc}")
    except Exception as exc:  # noqa: BLE001
        return StageResult("1. VAD", "FAIL", f"{type(exc).__name__}: {exc}")


# --------------------------------------------------------------------------- #
# Stage 2: faster-whisper (load_transcriber) + 전사 .txt 왕복 파싱 검증
# --------------------------------------------------------------------------- #
def stage_transcribe(audio_path: Path, work_dir: Path,
                     model_size: str = DEFAULT_WHISPER_MODEL) -> StageResult:
    """실물 faster-whisper로 전사하고, 전사문이 organize 재파싱까지 살아남는지 본다.

    가장 취약한 이음새: render_transcript_text 가 만든 `[MM:SS-MM:SS] "텍스트"` 줄을
    main._SEGMENT_LINE_RE 가 되읽는다. 실제 한국어 전사문에 따옴표/대괄호가 섞여도
    왕복이 무손실인지 확인한다.
    """
    if not _has("faster_whisper"):
        return StageResult("2. STT", "SKIP", _missing("faster_whisper", "audio"))
    try:
        from airvoice.main import _SEGMENT_LINE_RE
        from airvoice.notes.archive import Segment, Transcript
        from airvoice.transcribe import (
            format_timestamp,
            load_transcriber,
            render_transcript_text,
            save_transcript_text,
        )

        device = "CPU(느림)"
        if _has("torch"):
            import torch

            device = "CUDA" if torch.cuda.is_available() else "CPU(느림)"

        transcribe = load_transcriber(model_size)
        raw = transcribe(audio_path)
        if not raw:
            return StageResult("2. STT", "FAIL", "전사 세그먼트 0개")
        if not any(r.text.strip() for r in raw):
            return StageResult("2. STT", "FAIL", "모든 세그먼트 텍스트가 비어 있음")

        transcript = Transcript(
            source_file=audio_path.name,
            date=_TODAY,
            recorded_at=f"{_TODAY} 00:00:00",
            file_size=audio_path.stat().st_size,
            segments=[
                Segment(format_timestamp(r.start), format_timestamp(r.end), r.text)
                for r in raw
            ],
        )
        # 실제 파이프라인이 쓰는 경로/함수로 .txt 저장 후 organize 정규식으로 되읽기.
        txt_path = save_transcript_text(transcript, work_dir / "temp")
        reparsed: list[str] = []
        broken: list[str] = []
        for line in txt_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            match = _SEGMENT_LINE_RE.match(line)
            if match is None:
                broken.append(line)
            else:
                reparsed.append(match["text"])
        if broken:
            return StageResult(
                "2. STT", "FAIL",
                f"전사문 왕복 파싱 실패 {len(broken)}줄 — 예: {broken[0]!r} "
                "(main._SEGMENT_LINE_RE 가 실제 전사문을 못 읽음)",
            )
        original = [s.text for s in transcript.segments]
        if reparsed != original:
            return StageResult(
                "2. STT", "FAIL",
                "왕복 후 텍스트 불일치 — organize 아카이브 노트가 원문과 달라짐",
            )

        rendered = render_transcript_text(transcript)
        preview = rendered.strip().splitlines()[0][:60]
        detail = (
            f"[{device}] {model_size} → {len(raw)}세그먼트, "
            f"왕복 무손실 · {preview}…"
        )
        return StageResult("2. STT", "PASS", detail, payload=rendered)
    except ImportError as exc:
        return StageResult("2. STT", "SKIP", f"의존성 로드 실패: {exc}")
    except Exception as exc:  # noqa: BLE001
        return StageResult("2. STT", "FAIL", f"{type(exc).__name__}: {exc}")


# --------------------------------------------------------------------------- #
# Stage 3: AI 추출 (claude CLI 또는 Claude API)
# --------------------------------------------------------------------------- #
def _resolve_backend(choice: str, api_key: str | None) -> str | None:
    """auto → 키 있으면 api / claude 명령 있으면 claude_cli / 둘 다 없으면 None."""
    if choice != "auto":
        return choice
    if api_key:
        return "api"
    if shutil.which("claude"):
        return "claude_cli"
    return None


def stage_extract(transcript_text: str, api_key: str | None,
                  model: str | None = None, backend: str = "auto") -> StageResult:
    """실물 AI(claude CLI 또는 Claude API)로 항목을 추출하고 계약을 검증한다."""
    resolved = _resolve_backend(backend, api_key)
    if resolved is None:
        return StageResult("3. 추출", "SKIP", "AI 백엔드 없음 (claude 로그인/키 필요)")
    try:
        from airvoice.extract import _VALID_CATEGORIES

        if resolved == "claude_cli":
            if shutil.which("claude") is None:
                return StageResult("3. 추출", "SKIP", "claude 명령 없음 (PATH 확인)")
            from airvoice.extract import load_cli_extractor

            extract = load_cli_extractor(model=model)
            label = "claude CLI"
        else:
            if not _has("anthropic"):
                return StageResult("3. 추출", "SKIP", _missing("anthropic", "llm"))
            if not api_key:
                return StageResult("3. 추출", "SKIP", "CLAUDE_API_KEY 없음")
            from airvoice.extract import load_extractor

            extract = (
                load_extractor(api_key, model=model)
                if model else load_extractor(api_key)
            )
            label = "API"

        items = extract(transcript_text)  # 실패 시(JSON 파싱 불가) 예외 → FAIL
        bad = [it.category for it in items if it.category not in _VALID_CATEGORIES]
        if bad:
            return StageResult("3. 추출", "FAIL", f"허용 밖 카테고리: {bad}")
        cats = ", ".join(sorted({it.category for it in items})) or "(없음)"
        status = "PASS" if items else "WARN"
        note = "" if items else " — 0개 추출(짧은 입력이면 정상일 수 있음)"
        detail = f"[{label}] {len(items)}개 추출 [{cats}]{note}"
        if items:
            detail += f' · 예: "{items[0].text[:40]}"'
        return StageResult("3. 추출", status, detail, payload=items)
    except Exception as exc:  # noqa: BLE001
        return StageResult(
            "3. 추출", "FAIL",
            f"{type(exc).__name__}: {exc}  "
            "(실 응답이 방어 파싱을 통과 못함 → 프롬프트/로그인 점검)",
        )


# --------------------------------------------------------------------------- #
# Stage 4: Claude 창발 (load_emerger + run_emerge)
# --------------------------------------------------------------------------- #
def stage_emerge(work_dir: Path, api_key: str | None,
                 model: str | None = None, backend: str = "auto") -> StageResult:
    """canned 주제 노트 4개로 실물 창발을 돌리고, 산출물 계약을 검증한다."""
    resolved = _resolve_backend(backend, api_key)
    if resolved is None:
        return StageResult("4. 창발", "SKIP", "AI 백엔드 없음")
    try:
        from airvoice.emerge import run_emerge
        from airvoice.topics import merge_topics

        if resolved == "claude_cli":
            if shutil.which("claude") is None:
                return StageResult("4. 창발", "SKIP", "claude 명령 없음 (PATH 확인)")
            from airvoice.emerge import load_cli_emerger

            emerge = load_cli_emerger(model=model)
            label = "claude CLI"
        else:
            if not _has("anthropic"):
                return StageResult("4. 창발", "SKIP", _missing("anthropic", "llm"))
            if not api_key:
                return StageResult("4. 창발", "SKIP", "CLAUDE_API_KEY 없음")
            from airvoice.emerge import load_emerger

            emerge = (
                load_emerger(api_key, model=model)
                if model else load_emerger(api_key)
            )
            label = "API"

        vault = work_dir / "vault"
        # 실물 merge_topics 로 20-notes/*.md 를 만들어 scan_topic_notes 경로까지 검증.
        merge_topics(_EMERGE_ITEMS, vault, _TODAY, sources=[f"smoke.m4a ({_TODAY})"])
        ideas = run_emerge(vault, emerge, _TODAY, min_topics=3)  # 실패 시 예외 → FAIL

        if not ideas:
            return StageResult(
                "4. 창발", "WARN",
                f"[{label}] 0개 생성 — LLM이 조합을 못 찾음(응답 형식은 정상)",
            )
        first = ideas[0]
        if not first.title.strip():
            return StageResult("4. 창발", "FAIL", "아이디어 제목이 비어 있음")
        ev = (
            ", ".join(f"{e.topic}:{e.mention_count}" for e in first.evidence)
            or "(없음)"
        )
        detail = f'[{label}] {len(ideas)}개 · "{first.title[:40]}" · 근거[{ev}]'
        return StageResult("4. 창발", "PASS", detail, payload=ideas)
    except Exception as exc:  # noqa: BLE001
        return StageResult(
            "4. 창발", "FAIL",
            f"{type(exc).__name__}: {exc}  "
            "(실 응답이 방어 파싱을 통과 못함 → 프롬프트/로그인 점검)",
        )


# --------------------------------------------------------------------------- #
# 실행/리포트
# --------------------------------------------------------------------------- #
_MARK = {"PASS": "✓", "FAIL": "✗", "SKIP": "⊘", "WARN": "△"}


def _print(result: StageResult) -> None:
    mark = _MARK.get(result.status, "?")
    print(f"  {mark} [{result.status:4}] {result.name}: {result.detail}")


def run_all(audio_path: Path | None, work_dir: Path, api_key: str | None,
            model: str | None, ai_backend: str = "auto",
            whisper_model: str | None = None) -> list[StageResult]:
    results: list[StageResult] = []

    print("\n[1/4] Silero VAD …")
    if audio_path is None:
        r1 = StageResult("1. VAD", "SKIP", "오디오 파일 미제공")
    else:
        r1 = stage_vad(audio_path, work_dir)
    _print(r1)
    results.append(r1)

    print("[2/4] faster-whisper 전사 … (모델 로딩에 수십 초 걸릴 수 있음)")
    stt_input = r1.payload if r1.status == "PASS" else audio_path
    if stt_input is None:
        r2 = StageResult("2. STT", "SKIP", "전사할 오디오 없음")
    else:
        if r1.status != "PASS" and audio_path is not None:
            print("      (VAD 미적용 — 원본 오디오로 직접 전사)")
        r2 = stage_transcribe(
            Path(stt_input), work_dir, whisper_model or DEFAULT_WHISPER_MODEL
        )
    _print(r2)
    results.append(r2)

    print("[3/4] AI 추출 …")
    transcript_text = r2.payload if r2.status == "PASS" else _SAMPLE_TRANSCRIPT
    if r2.status != "PASS":
        print("      (실 전사문 없음 — 내장 샘플 전사문으로 추출 검증)")
    r3 = stage_extract(transcript_text, api_key, model, ai_backend)
    _print(r3)
    results.append(r3)

    print("[4/4] AI 창발 …")
    r4 = stage_emerge(work_dir, api_key, model, ai_backend)
    _print(r4)
    results.append(r4)

    return results


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")  # 콘솔 한글 깨짐 방지

    parser = argparse.ArgumentParser(
        description="Level 2 실물 이음새 스모크 하네스",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "audio", nargs="?", help="테스트용 오디오 파일 (없으면 Claude 단계만)"
    )
    parser.add_argument(
        "--model", default=None, help="AI 모델 ID override (예: 더 싼 모델로 먼저)"
    )
    parser.add_argument(
        "--ai", choices=["auto", "api", "claude_cli"], default="auto",
        help="AI 백엔드: auto(키 있으면 API, 없으면 claude 로그인) / api / claude_cli",
    )
    parser.add_argument(
        "--whisper-model", default=None,
        help="받아쓰기 모델 override (예: tiny/base/small — CPU 빠른 테스트용)",
    )
    parser.add_argument(
        "--keep", action="store_true", help="임시 산출물 폴더를 삭제하지 않음"
    )
    args = parser.parse_args(argv)

    audio_path: Path | None = None
    if args.audio:
        audio_path = Path(args.audio).expanduser()
        if not audio_path.is_file():
            print(f"오디오 파일을 찾을 수 없습니다: {audio_path}", file=sys.stderr)
            return 2

    load_dotenv()
    api_key = os.environ.get("CLAUDE_API_KEY", "").strip() or None

    work_dir = Path(tempfile.mkdtemp(prefix="airvoice-smoke-"))
    print("=" * 68)
    print("AI R Voice Level 2 실물 이음새 스모크")
    resolved_ai = _resolve_backend(args.ai, api_key)
    print(f"  오디오 : {audio_path or '(없음 — AI 단계만)'}")
    print(f"  AI     : {resolved_ai or '없음 (키/claude 로그인 필요)'}"
          + (f" / 모델: {args.model}" if args.model else ""))
    print(f"  샌드박스: {work_dir}  (실제 볼트·DB 미접촉)")
    print("=" * 68)

    try:
        results = run_all(
            audio_path, work_dir, api_key, args.model, args.ai, args.whisper_model
        )
    finally:
        if args.keep:
            print(f"\n산출물 보존: {work_dir}")
        else:
            shutil.rmtree(work_dir, ignore_errors=True)

    fails = [r for r in results if r.status == "FAIL"]
    passes = [r for r in results if r.status == "PASS"]
    skips = [r for r in results if r.status == "SKIP"]
    warns = [r for r in results if r.status == "WARN"]

    print("\n" + "-" * 68)
    print(f"결과: {len(passes)} PASS · {len(fails)} FAIL · "
          f"{len(warns)} WARN · {len(skips)} SKIP")
    if fails:
        print("→ 실패한 이음새가 있습니다. 위 상세 확인 (mock↔실물 계약 불일치 지점).")
    elif not passes:
        print("→ 실물 단계가 하나도 안 돌았습니다. 의존성/오디오/API키를 확인하세요.")
    else:
        print("→ 실행된 실물 단계가 모두 계약을 통과했습니다.")
    print("-" * 68)

    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
