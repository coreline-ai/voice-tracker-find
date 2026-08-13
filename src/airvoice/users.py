# @TASK PB-T1 - 다중 사용자 분리 (수집/볼트/DB 를 사용자별로)
# @SPEC docs/HANDOFF.md#4-K
# @TEST tests/test_users.py
"""사용자별 설정 로딩.

한 대의 PC 로 여러 사람의 녹음을 처리할 때, 사용자마다 수집 폴더·볼트·DB·임시
폴더를 완전히 나눈다. 섞이면 노트와 주제가 합쳐지고, 무엇보다 파일명이 겹칠 때
한쪽 녹음이 조용히 사라진다(앱이 파일명을 시각만으로 만든다).

``~/.airvoice/users.json`` 이 있으면 다중 사용자 모드, 없으면 기존 ``.env``
단일 사용자 모드로 동작한다. 단일 사용자 모드에서는 경로의 ``{user}`` 를 그대로
무시해 기존 설정이 계속 동작한다.

users.json 형식::

    [
      {"name": "user1", "token": "...", "ingest_dir": "D:/airvoice",
       "vault": "~/ai-r-voice-vault", "db_path": "~/.airvoice/db/pipeline.db",
       "temp_dir": "~/.airvoice/temp"},
      {"name": "user2", "token": "..."}
    ]

``name`` 과 ``token`` 만 필수다. 경로를 생략하면
``~/.airvoice/users/{name}/`` 아래로 자동 배치한다.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, replace
from pathlib import Path

from airvoice.config import ConfigError, Settings

USERS_FILE = "~/.airvoice/users.json"

# 경로를 생략한 사용자의 기본 배치 위치.
_USER_BASE = "~/.airvoice/users"


@dataclass(frozen=True)
class User:
    """사용자 1명과 그 사람 전용 파이프라인 설정."""

    name: str
    token: str
    settings: Settings


def _user_defaults(name: str, base: Settings) -> dict[str, Path]:
    """경로를 생략했을 때 쓸 사용자 전용 경로."""
    root = Path(_USER_BASE).expanduser() / name
    return {
        "ingest_dir": root / "inbox",
        "obsidian_vault": root / "vault",
        "db_path": root / "pipeline.db",
        "temp_dir": root / "temp",
    }


def _build(entry: dict, base: Settings) -> User:
    name = str(entry.get("name", "")).strip()
    token = str(entry.get("token", "")).strip()
    if not name:
        raise ConfigError("users.json 의 각 항목에는 name 이 필요합니다.")
    if not token:
        raise ConfigError(f"사용자 {name!r} 에 token 이 없습니다.")
    if "/" in name or "\\" in name or name in {".", ".."}:
        raise ConfigError(f"사용자 이름에 경로 문자를 쓸 수 없습니다: {name!r}")

    defaults = _user_defaults(name, base)
    paths = {
        "ingest_dir": entry.get("ingest_dir"),
        "obsidian_vault": entry.get("vault"),
        "db_path": entry.get("db_path"),
        "temp_dir": entry.get("temp_dir"),
    }
    resolved = {
        field: (Path(value).expanduser() if value else defaults[field])
        for field, value in paths.items()
    }
    return User(name=name, token=token, settings=replace(base, **resolved))


def load_users(base: Settings, users_file: str | Path | None = None) -> list[User]:
    """사용자 목록을 로드한다.

    Args:
        base: ``.env`` 에서 읽은 기본 설정. 사용자별 경로만 덮어쓰고 나머지
            (AI 백엔드, whisper 설정 등)는 그대로 물려받는다.
        users_file: users.json 경로. None 이면 기본 위치.

    Returns:
        사용자 목록. users.json 이 없으면 base 설정을 쓰는 단일 사용자 1명
        (이름은 빈 문자열 = 경로의 {user} 를 검사하지 않는다는 표시).

    Raises:
        ConfigError: users.json 형식이 잘못됐거나 토큰이 중복될 때.
    """
    path = Path(users_file or USERS_FILE).expanduser()
    if not path.is_file():
        return [User(name="", token=base.receiver_token, settings=base)]

    try:
        entries = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ConfigError(f"users.json 을 읽을 수 없습니다: {exc}") from exc

    if not isinstance(entries, list) or not entries:
        raise ConfigError("users.json 은 사용자 항목이 담긴 배열이어야 합니다.")

    users = [_build(entry, base) for entry in entries]

    names = [user.name for user in users]
    if len(set(names)) != len(names):
        raise ConfigError(f"사용자 이름이 중복됩니다: {names}")

    # 토큰이 겹치면 한 사람의 토큰으로 다른 사람 자료에 접근하게 된다.
    tokens = [user.token for user in users]
    if len(set(tokens)) != len(tokens):
        raise ConfigError("사용자 토큰이 중복됩니다. 각자 다른 토큰을 쓰세요.")

    return users


def is_multi_user(users: list[User]) -> bool:
    """다중 사용자 모드인지 (경로의 {user} 를 검사해야 하는지)."""
    return not (len(users) == 1 and users[0].name == "")
