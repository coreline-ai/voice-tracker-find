#!/usr/bin/env python3
"""Generate five deterministic 1–3 minute Korean TTS WAV fixtures on macOS."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Fixture:
    file_name: str
    target_seconds: int
    keywords: tuple[str, ...]
    sentences: tuple[str, ...]


FIXTURES = (
    Fixture(
        "tts_01_release.wav",
        65,
        ("출시", "테스트", "품질"),
        (
            "모바일 앱 출시 회의에서는 안정적인 배포 일정과 품질 기준을 먼저 확인했습니다.",
            "개발팀은 주요 화면의 기능 테스트와 데이터 보존 검사를 완료하기로 했습니다.",
            "디자인팀은 글자 크기와 버튼 간격이 실제 휴대전화에서도 편안한지 점검합니다.",
            "품질 담당자는 녹음 시작과 종료, 파일 복구, 장시간 처리 과정을 반복해서 검증합니다.",
            "출시 후보 버전은 오류가 없는 경우에만 다음 단계로 이동합니다.",
            "문제가 발견되면 원인을 기록하고 수정한 뒤 같은 조건으로 다시 테스트합니다.",
            "사용자 데이터와 설치된 모델은 앱 업데이트 과정에서도 그대로 유지되어야 합니다.",
            "회의 결론은 빠른 출시보다 재현 가능한 품질 증거를 우선한다는 것입니다.",
        ),
    ),
    Fixture(
        "tts_02_customer.wav",
        90,
        ("고객", "배송", "환불"),
        (
            "고객 상담 개선 회의에서는 배송 지연 안내와 환불 절차를 쉽게 설명하는 방법을 논의했습니다.",
            "상담 직원은 고객의 질문을 끝까지 듣고 주문 상태를 정확하게 확인해야 합니다.",
            "배송이 늦어지는 경우 예상 도착 시점과 지연 사유를 함께 안내합니다.",
            "상품이 손상되었거나 설명과 다른 경우에는 사진 확인 후 교환이나 환불을 신속하게 처리합니다.",
            "반복 문의가 많은 내용은 도움말 문서와 앱 알림에 미리 반영합니다.",
            "고객이 같은 설명을 여러 번 하지 않도록 이전 상담 기록을 담당자가 확인합니다.",
            "개인정보는 상담 목적에 필요한 범위에서만 사용하고 불필요한 내용은 저장하지 않습니다.",
            "운영팀은 배송과 환불 처리 시간을 매주 비교해 개선 효과를 확인합니다.",
        ),
    ),
    Fixture(
        "tts_03_backup.wav",
        115,
        ("데이터", "백업", "복구"),
        (
            "데이터 보호 계획은 원본 보존과 안전한 백업, 검증 가능한 복구 절차로 구성됩니다.",
            "녹음 파일은 작성 중인 임시 파일과 완료된 원본 파일을 구분해서 관리합니다.",
            "앱이 갑자기 종료되더라도 완료된 데이터와 처리 체크포인트를 삭제하지 않습니다.",
            "백업을 만들 때에는 파일 크기와 해시 값을 함께 기록해 손상 여부를 확인합니다.",
            "복구 테스트는 정상 상황뿐 아니라 저장공간 부족과 전원 종료 상황도 포함합니다.",
            "데이터베이스 변경은 이전 버전의 기록을 유지하는 마이그레이션 테스트를 통과해야 합니다.",
            "사용자가 삭제를 선택하지 않은 원본과 모델 파일은 자동으로 정리하지 않습니다.",
            "운영 담당자는 백업 생성 시간과 복구 성공 여부를 보고서에 남깁니다.",
        ),
    ),
    Fixture(
        "tts_04_content.wav",
        145,
        ("상품", "영상", "콘텐츠"),
        (
            "쇼핑 콘텐츠 운영팀은 고객이 실제로 궁금해하는 상품 정보를 짧은 영상으로 전달합니다.",
            "첫 장면에서는 상품의 핵심 특징을 보여주고 불필요한 인사말은 줄입니다.",
            "영상 중간에는 사용 방법과 크기, 재질, 관리 시 주의할 점을 구체적으로 설명합니다.",
            "과장된 표현 대신 촬영 조건과 실제 사용 결과를 분명하게 구분합니다.",
            "콘텐츠 제목은 상품 이름과 가장 중요한 장점을 짧고 정확하게 담아야 합니다.",
            "운영팀은 시청 유지 시간과 상품 페이지 이동 비율을 함께 확인합니다.",
            "반응이 낮은 영상은 첫 장면과 설명 순서를 바꾸어 다시 제작합니다.",
            "고객 질문에서 반복되는 내용은 다음 콘텐츠 기획에 우선적으로 반영합니다.",
        ),
    ),
    Fixture(
        "tts_05_ondevice.wav",
        175,
        ("음성", "모델", "기기"),
        (
            "온디바이스 인공지능 기능은 음성 파일과 전사 원문을 기기 안에서 처리하는 것을 목표로 합니다.",
            "완료된 녹음은 로컬 음성 인식 모델을 사용해 전체 구간을 순서대로 텍스트로 변환합니다.",
            "요약 모델은 전사 원문에 있는 근거만 사용해 제목과 핵심 문장을 생성해야 합니다.",
            "입력에 없는 숫자나 고유명사가 나오면 결과를 저장하지 않고 다시 확인합니다.",
            "모델 파일은 설치 전에 크기와 해시 값을 검증하고 앱 업데이트 후에도 유지합니다.",
            "장시간 처리는 구간별 체크포인트를 기록해 중단된 위치부터 이어서 실행합니다.",
            "기기 온도와 배터리, 메모리 사용량이 높아지면 작업을 일시 정지할 수 있어야 합니다.",
            "최종 화면에는 사용한 음성 인식 방식과 요약 모델을 명확하게 표시합니다.",
        ),
    ),
)


def run(*args: str) -> None:
    subprocess.run(args, check=True)


def duration(path: Path) -> float:
    completed = subprocess.run(
        (
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(path),
        ),
        check=True,
        capture_output=True,
        text=True,
    )
    return float(completed.stdout.strip())


def create_source_text(fixture: Fixture) -> str:
    estimated_round_seconds = 32
    rounds = max(2, math.ceil(fixture.target_seconds / estimated_round_seconds))
    paragraphs = []
    for round_index in range(rounds):
        prefix = ("먼저", "이어서", "또한", "마지막으로")[round_index % 4]
        paragraphs.append(prefix + " " + " ".join(fixture.sentences))
    return "\n".join(paragraphs)


def atempo_filter(factor: float) -> str:
    filters: list[str] = []
    remaining = factor
    while remaining > 2.0:
        filters.append("atempo=2.0")
        remaining /= 2.0
    while remaining < 0.5:
        filters.append("atempo=0.5")
        remaining /= 0.5
    filters.append(f"atempo={remaining:.8f}")
    return ",".join(filters)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--voice", default="Yuna")
    parser.add_argument("--rate", default="175")
    args = parser.parse_args()

    for command in ("say", "ffmpeg", "ffprobe"):
        if shutil.which(command) is None:
            raise SystemExit(f"required command not found: {command}")

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    results = []
    for fixture in FIXTURES:
        text = create_source_text(fixture)
        stem = Path(fixture.file_name).stem
        text_path = output / f"{stem}.txt"
        raw_path = output / f"{stem}.aiff"
        wav_path = output / fixture.file_name
        text_path.write_text(text, encoding="utf-8")
        run("say", "-v", args.voice, "-r", args.rate, "-f", str(text_path), "-o", str(raw_path))
        source_seconds = duration(raw_path)
        tempo = source_seconds / fixture.target_seconds
        run(
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(raw_path),
            "-af",
            f"{atempo_filter(tempo)},aresample=16000",
            "-ac",
            "1",
            "-c:a",
            "pcm_s16le",
            "-t",
            str(fixture.target_seconds),
            str(wav_path),
        )
        actual_seconds = duration(wav_path)
        results.append(
            {
                "fileName": fixture.file_name,
                "targetSeconds": fixture.target_seconds,
                "actualSeconds": actual_seconds,
                "bytes": wav_path.stat().st_size,
                "sha256": hashlib.sha256(wav_path.read_bytes()).hexdigest(),
                "keywords": fixture.keywords,
                "sourceTextChars": len(text),
                "sourceTextSha256": hashlib.sha256(text.encode()).hexdigest(),
                "voice": args.voice,
                "rate": int(args.rate),
            },
        )
        raw_path.unlink(missing_ok=True)

    (output / "manifest.json").write_text(
        json.dumps({"fixtures": results}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(json.dumps({"output": str(output), "fixtures": results}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
