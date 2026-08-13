# @TASK PB-T1 - 다중 사용자 분리
# @SPEC docs/HANDOFF.md#4-K
# @TEST tests/test_users.py
from __future__ import annotations

import json
from pathlib import Path

import pytest

from airvoice.config import ConfigError, Settings
from airvoice.users import is_multi_user, load_users


@pytest.fixture
def base(tmp_path: Path) -> Settings:
    return Settings(
        claude_api_key="key",
        ingest_dir=tmp_path / "inbox",
        obsidian_vault=tmp_path / "vault",
        db_path=tmp_path / "pipeline.db",
        temp_dir=tmp_path / "temp",
        whisper_model="large-v3",
        vad_sample_rate=16000,
        vad_threshold=0.5,
        retention_days=7,
        receiver_token="base-token",
    )


def _write_users(tmp_path: Path, entries: list[dict]) -> Path:
    path = tmp_path / "users.json"
    path.write_text(json.dumps(entries, ensure_ascii=False), encoding="utf-8")
    return path


def test_파일이_없으면_기존_단일_사용자로_동작한다(base: Settings) -> None:
    users = load_users(base, users_file="/존재하지/않는/users.json")

    assert len(users) == 1
    assert users[0].token == "base-token"
    assert users[0].settings.ingest_dir == base.ingest_dir
    assert not is_multi_user(users), "단일 사용자면 경로의 {user} 를 검사하지 않는다"


def test_사용자별로_경로가_완전히_갈린다(base: Settings, tmp_path: Path) -> None:
    path = _write_users(
        tmp_path,
        [
            {"name": "alice", "token": "a"},
            {"name": "bob", "token": "b"},
        ],
    )

    alice, bob = load_users(base, users_file=path)

    assert is_multi_user([alice, bob])
    for field in ("ingest_dir", "obsidian_vault", "db_path", "temp_dir"):
        a = getattr(alice.settings, field)
        b = getattr(bob.settings, field)
        assert a != b, f"{field} 가 겹치면 두 사람 자료가 섞인다"


def test_경로를_명시하면_그대로_쓴다(base: Settings, tmp_path: Path) -> None:
    path = _write_users(
        tmp_path,
        [
            {
                "name": "alice",
                "token": "a",
                "ingest_dir": "D:/airvoice",
                "vault": "~/ai-r-voice-vault",
            }
        ],
    )

    [alice] = load_users(base, users_file=path)

    assert alice.settings.ingest_dir == Path("D:/airvoice")
    assert alice.settings.obsidian_vault == Path("~/ai-r-voice-vault").expanduser()


def test_AI_설정은_기본값을_물려받는다(base: Settings, tmp_path: Path) -> None:
    path = _write_users(tmp_path, [{"name": "alice", "token": "a"}])

    [alice] = load_users(base, users_file=path)

    assert alice.settings.whisper_model == base.whisper_model
    assert alice.settings.ai_provider == base.ai_provider


def test_토큰이_겹치면_거부한다(base: Settings, tmp_path: Path) -> None:
    # 같은 토큰이면 한 사람 토큰으로 다른 사람 자료에 접근하게 된다.
    path = _write_users(
        tmp_path,
        [{"name": "alice", "token": "same"}, {"name": "bob", "token": "same"}],
    )

    with pytest.raises(ConfigError, match="토큰이 중복"):
        load_users(base, users_file=path)


def test_이름이_겹치면_거부한다(base: Settings, tmp_path: Path) -> None:
    path = _write_users(
        tmp_path, [{"name": "alice", "token": "a"}, {"name": "alice", "token": "b"}]
    )

    with pytest.raises(ConfigError, match="이름이 중복"):
        load_users(base, users_file=path)


def test_토큰이_없으면_거부한다(base: Settings, tmp_path: Path) -> None:
    path = _write_users(tmp_path, [{"name": "alice"}])

    with pytest.raises(ConfigError, match="token"):
        load_users(base, users_file=path)


@pytest.mark.parametrize("name", ["../evil", "a/b", "a\\b", ".."])
def test_이름에_경로_문자를_쓸_수_없다(
    base: Settings, tmp_path: Path, name: str
) -> None:
    # 이름이 폴더 경로가 되므로 탈출을 막아야 한다.
    path = _write_users(tmp_path, [{"name": name, "token": "a"}])

    with pytest.raises(ConfigError, match="경로 문자"):
        load_users(base, users_file=path)


def test_형식이_잘못되면_거부한다(base: Settings, tmp_path: Path) -> None:
    path = tmp_path / "users.json"
    path.write_text("{ 이건 배열이 아님 }", encoding="utf-8")

    with pytest.raises(ConfigError):
        load_users(base, users_file=path)
