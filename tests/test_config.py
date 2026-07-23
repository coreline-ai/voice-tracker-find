# @TASK P0-T0.2 - 설정 모듈 테스트 (TDD RED)
# @SPEC docs/planning/06-tasks.md#P0-T0.2
# @TEST tests/test_config.py
from pathlib import Path

import pytest

from thinktank.config import ConfigError, Settings, load_settings

MINIMAL_ENV = {"CLAUDE_API_KEY": "sk-test-123"}


def test_load_settings_returns_settings_instance():
    """필수값만 주어져도 Settings 인스턴스를 반환한다."""
    settings = load_settings(env=MINIMAL_ENV)
    assert isinstance(settings, Settings)
    assert settings.claude_api_key == "sk-test-123"


def test_defaults_applied_for_optional_values():
    """선택 항목은 기본값이 적용된다."""
    settings = load_settings(env=MINIMAL_ENV)

    assert settings.ingest_dir == Path("~/.thinktank/inbox").expanduser()
    assert settings.obsidian_vault == Path("~/thinktank-vault").expanduser()
    assert settings.db_path == Path("~/.thinktank/db/pipeline.db").expanduser()
    assert settings.temp_dir == Path("~/.thinktank/temp").expanduser()
    assert settings.whisper_model == "large-v3"
    assert settings.vad_sample_rate == 16000
    assert settings.vad_threshold == 0.5
    assert settings.retention_days == 7


def test_env_overrides_defaults():
    """환경변수가 주어지면 기본값을 덮어쓴다."""
    env = {
        "CLAUDE_API_KEY": "sk-live-999",
        "INGEST_DIR": "~/custom/inbox",
        "OBSIDIAN_VAULT": "~/vault",
        "DB_PATH": "~/db/pipeline.db",
        "TEMP_DIR": "~/tmp",
        "WHISPER_MODEL": "medium",
        "VAD_SAMPLE_RATE": "8000",
        "VAD_THRESHOLD": "0.7",
        "RETENTION_DAYS": "30",
    }
    settings = load_settings(env=env)

    assert settings.claude_api_key == "sk-live-999"
    assert settings.ingest_dir == Path("~/custom/inbox").expanduser()
    assert settings.obsidian_vault == Path("~/vault").expanduser()
    assert settings.db_path == Path("~/db/pipeline.db").expanduser()
    assert settings.temp_dir == Path("~/tmp").expanduser()
    assert settings.whisper_model == "medium"
    assert settings.vad_sample_rate == 8000
    assert settings.vad_threshold == 0.7
    assert settings.retention_days == 30


def test_missing_required_key_raises():
    """필수 항목(CLAUDE_API_KEY) 누락 시 ConfigError."""
    with pytest.raises(ConfigError):
        load_settings(env={})


def test_empty_required_key_raises():
    """필수 항목이 빈 문자열이면 ConfigError."""
    with pytest.raises(ConfigError):
        load_settings(env={"CLAUDE_API_KEY": "   "})


def test_paths_are_absolute_and_expanded():
    """경로 항목은 ~ 확장 후 절대경로 Path로 반환된다."""
    settings = load_settings(env=MINIMAL_ENV)
    for path in (
        settings.ingest_dir,
        settings.obsidian_vault,
        settings.db_path,
        settings.temp_dir,
    ):
        assert isinstance(path, Path)
        assert path.is_absolute()
        assert "~" not in str(path)


def test_numeric_types_are_coerced():
    """숫자 항목은 올바른 타입으로 변환된다."""
    settings = load_settings(env=MINIMAL_ENV)
    assert isinstance(settings.vad_sample_rate, int)
    assert isinstance(settings.vad_threshold, float)
    assert isinstance(settings.retention_days, int)


def test_invalid_numeric_value_raises():
    """숫자 항목에 잘못된 값이 들어오면 ConfigError."""
    with pytest.raises(ConfigError):
        load_settings(env={"CLAUDE_API_KEY": "k", "RETENTION_DAYS": "abc"})


def test_settings_is_immutable():
    """Settings는 불변(frozen)이다."""
    settings = load_settings(env=MINIMAL_ENV)
    with pytest.raises((AttributeError, TypeError)):
        settings.claude_api_key = "changed"  # type: ignore[misc]


# ---------------------------------------------------------------------------
# AI_PROVIDER: 로컬 claude CLI(구독 OAuth) 백엔드 선택
# ---------------------------------------------------------------------------


def test_default_ai_provider_still_requires_claude_api_key():
    """AI_PROVIDER 미지정(기본 api) + 키 없음 -> 기존과 동일하게 ConfigError."""
    with pytest.raises(ConfigError):
        load_settings(env={})


def test_ai_provider_defaults_to_api():
    """AI_PROVIDER 미지정 시 기본값은 "api"."""
    settings = load_settings(env=MINIMAL_ENV)
    assert settings.ai_provider == "api"


def test_claude_cli_provider_does_not_require_api_key():
    """AI_PROVIDER=claude_cli 이면 CLAUDE_API_KEY 없이도 로드된다."""
    settings = load_settings(env={"AI_PROVIDER": "claude_cli"})
    assert settings.ai_provider == "claude_cli"
    assert settings.claude_api_key == ""


def test_invalid_ai_provider_raises():
    """AI_PROVIDER 에 알 수 없는 값이 오면 ConfigError."""
    with pytest.raises(ConfigError):
        load_settings(env={"AI_PROVIDER": "not-a-real-provider"})


# ---------------------------------------------------------------------------
# WHISPER_DEVICE: 받아쓰기 장치 선택 (GPU/CPU)
# ---------------------------------------------------------------------------


def test_whisper_device_defaults_to_none():
    """WHISPER_DEVICE 미지정 시 None(자동감지)."""
    settings = load_settings(env=MINIMAL_ENV)
    assert settings.whisper_device is None


def test_whisper_device_cuda():
    """WHISPER_DEVICE=cuda 이면 그대로 반영된다."""
    settings = load_settings(env={**MINIMAL_ENV, "WHISPER_DEVICE": "cuda"})
    assert settings.whisper_device == "cuda"
