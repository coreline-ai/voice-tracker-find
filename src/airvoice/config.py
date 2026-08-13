# @TASK P0-T0.2 - 설정 모듈 (dotenv 로딩 + 필수/선택 검증 + 기본값)
# @SPEC docs/planning/06-tasks.md#P0-T0.2
# @TEST tests/test_config.py
"""환경변수 기반 파이프라인 설정 로딩.

`.env` 파일 또는 프로세스 환경에서 설정을 읽어 검증된 :class:`Settings`
인스턴스를 만든다. 필수 항목이 없거나 숫자 항목이 잘못되면
:class:`ConfigError` 를 발생시킨다.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

# 선택 항목 기본값 (필수/선택 구분: CLAUDE_API_KEY 만 필수)
DEFAULT_INGEST_DIR = "~/.airvoice/inbox"
DEFAULT_OBSIDIAN_VAULT = "~/ai-r-voice-vault"
DEFAULT_DB_PATH = "~/.airvoice/db/pipeline.db"
DEFAULT_TEMP_DIR = "~/.airvoice/temp"
DEFAULT_WHISPER_MODEL = "large-v3"
DEFAULT_VAD_SAMPLE_RATE = 16000
DEFAULT_VAD_THRESHOLD = 0.5
DEFAULT_RETENTION_DAYS = 7
DEFAULT_RECEIVER_PORT = 8765

# AI 백엔드 선택: "api"(Claude API 키 사용) | "claude_cli"(로컬 claude 로그인 사용)
DEFAULT_AI_PROVIDER = "api"
_VALID_AI_PROVIDERS = frozenset({"api", "claude_cli"})


class ConfigError(Exception):
    """설정이 유효하지 않을 때 발생하는 예외."""


@dataclass(frozen=True)
class Settings:
    """검증된 파이프라인 설정값 묶음 (불변)."""

    claude_api_key: str
    ingest_dir: Path
    obsidian_vault: Path
    db_path: Path
    temp_dir: Path
    whisper_model: str
    vad_sample_rate: int
    vad_threshold: float
    retention_days: int
    ai_provider: str = DEFAULT_AI_PROVIDER
    whisper_device: str | None = None  # None=자동감지, "cuda", "cpu"
    # LAN 수신기(모드1). 토큰이 비면 수신기는 뜨지 않는다(파이프라인은 무관).
    receiver_token: str = ""
    receiver_port: int = DEFAULT_RECEIVER_PORT
    # 인증서+개인키 PEM 경로. 비면 평문 HTTP(앱이 아직 피닝을 모를 때의 기본).
    receiver_cert: str = ""
    # 폰이 내려받을 APK 경로. 비면 /apk 가 404.
    receiver_apk: str = ""


def _get(env: Mapping[str, str], key: str, default: str) -> str:
    """환경변수를 읽되 값이 없거나 공백이면 기본값을 사용."""
    value = env.get(key)
    if value is None or not value.strip():
        return default
    return value.strip()


def _resolve_path(env: Mapping[str, str], key: str, default: str) -> Path:
    """경로 항목을 ~ 확장하여 절대경로 Path로 변환."""
    return Path(_get(env, key, default)).expanduser()


def _resolve_int(env: Mapping[str, str], key: str, default: int) -> int:
    raw = env.get(key)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw.strip())
    except ValueError as exc:
        raise ConfigError(f"{key} 는 정수여야 합니다: {raw!r}") from exc


def _resolve_float(env: Mapping[str, str], key: str, default: float) -> float:
    raw = env.get(key)
    if raw is None or not raw.strip():
        return default
    try:
        return float(raw.strip())
    except ValueError as exc:
        raise ConfigError(f"{key} 는 실수여야 합니다: {raw!r}") from exc


def load_settings(env: Mapping[str, str] | None = None) -> Settings:
    """설정을 로드하고 검증한다.

    Args:
        env: 사용할 환경변수 매핑. ``None`` 이면 ``.env`` 를 로드한 뒤
            프로세스 환경(``os.environ``)을 사용한다.

    Returns:
        검증된 :class:`Settings` 인스턴스.

    Raises:
        ConfigError: 필수 항목(``AI_PROVIDER`` 가 ``api`` 일 때의
            ``CLAUDE_API_KEY``)이 없거나, ``AI_PROVIDER`` 값이 유효하지
            않거나, 숫자 항목이 잘못된 형식일 때.
    """
    if env is None:
        load_dotenv()
        env = os.environ

    ai_provider = _get(env, "AI_PROVIDER", DEFAULT_AI_PROVIDER)
    if ai_provider not in _VALID_AI_PROVIDERS:
        raise ConfigError(
            f"AI_PROVIDER 는 {sorted(_VALID_AI_PROVIDERS)} 중 하나여야 합니다: "
            f"{ai_provider!r}"
        )

    claude_api_key = env.get("CLAUDE_API_KEY", "")
    if ai_provider == "api" and not claude_api_key.strip():
        raise ConfigError("CLAUDE_API_KEY 는 필수 환경변수입니다.")

    whisper_device_raw = env.get("WHISPER_DEVICE")
    whisper_device = (
        whisper_device_raw.strip()
        if whisper_device_raw and whisper_device_raw.strip()
        else None
    )

    return Settings(
        claude_api_key=claude_api_key.strip(),
        ingest_dir=_resolve_path(env, "INGEST_DIR", DEFAULT_INGEST_DIR),
        obsidian_vault=_resolve_path(env, "OBSIDIAN_VAULT", DEFAULT_OBSIDIAN_VAULT),
        db_path=_resolve_path(env, "DB_PATH", DEFAULT_DB_PATH),
        temp_dir=_resolve_path(env, "TEMP_DIR", DEFAULT_TEMP_DIR),
        whisper_model=_get(env, "WHISPER_MODEL", DEFAULT_WHISPER_MODEL),
        vad_sample_rate=_resolve_int(env, "VAD_SAMPLE_RATE", DEFAULT_VAD_SAMPLE_RATE),
        vad_threshold=_resolve_float(env, "VAD_THRESHOLD", DEFAULT_VAD_THRESHOLD),
        retention_days=_resolve_int(env, "RETENTION_DAYS", DEFAULT_RETENTION_DAYS),
        ai_provider=ai_provider,
        whisper_device=whisper_device,
        receiver_token=_get(env, "RECEIVER_TOKEN", ""),
        receiver_port=_resolve_int(env, "RECEIVER_PORT", DEFAULT_RECEIVER_PORT),
        receiver_cert=_get(env, "RECEIVER_CERT", ""),
        receiver_apk=_get(env, "RECEIVER_APK", ""),
    )
