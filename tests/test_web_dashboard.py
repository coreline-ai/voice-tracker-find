from __future__ import annotations

import json
import threading
import urllib.error
import urllib.request
from dataclasses import replace
from pathlib import Path

import pytest

from airvoice.config import Settings
from airvoice.receiver import create_server
from airvoice.users import User


TOKEN = "dashboard-test-token"


def _make_user(tmp_path: Path) -> User:
    settings = Settings(
        claude_api_key="test-key",
        ingest_dir=tmp_path / "inbox",
        obsidian_vault=tmp_path / "vault",
        db_path=tmp_path / "receiver.sqlite3",
        temp_dir=tmp_path / "temp",
        whisper_model="large-v3",
        vad_sample_rate=16000,
        vad_threshold=0.5,
        retention_days=7,
    )
    return User(name="", token=TOKEN, settings=replace(settings))


def _get(url: str, token: str | None = None) -> tuple[int, bytes, dict[str, str]]:
    request = urllib.request.Request(url, method="GET")  # noqa: S310
    if token is not None:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=5) as response:  # noqa: S310
            return response.status, response.read(), dict(response.headers)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read(), dict(exc.headers)


@pytest.fixture
def dashboard_server(tmp_path: Path):
    user = _make_user(tmp_path)
    (user.settings.ingest_dir).mkdir(parents=True)
    (user.settings.obsidian_vault / "30-ideas").mkdir(parents=True)
    (user.settings.ingest_dir / "recording.m4a").write_bytes(b"audio")
    (user.settings.ingest_dir / "ignored.txt").write_bytes(b"not audio")
    (user.settings.obsidian_vault / "30-ideas" / "idea.md").write_text(
        "# Idea\n", encoding="utf-8"
    )
    server = create_server(users=[user], host="127.0.0.1", port=0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base_url = f"http://127.0.0.1:{server.server_address[1]}"
    try:
        yield base_url
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_dashboard_static_page_is_public(dashboard_server: str) -> None:
    status, body, headers = _get(f"{dashboard_server}/dashboard")

    assert status == 200
    assert b"AI R Voice" in body
    assert headers["Content-Security-Policy"].startswith("default-src 'self'")


def test_dashboard_favicon_is_publicly_served(dashboard_server: str) -> None:
    status, body, headers = _get(f"{dashboard_server}/dashboard/favicon.svg")

    assert status == 200
    assert body.startswith(b"<svg")
    assert headers["Content-Type"] == "image/svg+xml"


def test_dashboard_migrates_legacy_session_token_without_exposing_it(
    dashboard_server: str,
) -> None:
    status, script, _ = _get(f"{dashboard_server}/dashboard/token-storage.js")

    assert status == 200
    assert b"airvoice-receiver-dashboard-token" in script
    assert b"storage.removeItem(LEGACY_TOKEN_KEY)" in script
    assert b"localStorage" not in script


def test_dashboard_explains_action_and_hides_inactive_errors(dashboard_server: str) -> None:
    page_status, page_body, _ = _get(f"{dashboard_server}/dashboard")
    css_status, css_body, _ = _get(f"{dashboard_server}/dashboard/styles.css")

    assert page_status == 200
    assert b"05 / ACTION" in page_body
    assert "권장 조치".encode() in page_body
    assert "노트 내용 보기".encode() in page_body
    assert "M4A 듣기".encode() in page_body
    assert css_status == 200
    assert b".error-banner[hidden] { display: none; }" in css_body


def test_dashboard_summary_requires_bearer_token(dashboard_server: str) -> None:
    status, body, _ = _get(f"{dashboard_server}/api/v1/dashboard/summary")

    assert status == 401
    assert json.loads(body)["error"]["code"] == "UNAUTHORIZED"


def test_dashboard_note_view_is_authenticated_and_user_scoped(
    dashboard_server: str,
) -> None:
    status, body, headers = _get(
        f"{dashboard_server}/api/v1/dashboard/notes/30-ideas/idea.md", token=TOKEN
    )

    assert status == 200
    payload = json.loads(body)
    assert payload["folder"] == "30-ideas"
    assert payload["name"] == "idea.md"
    assert payload["content"] == "# Idea\n"
    assert headers["Cache-Control"] == "no-store"

    denied_status, denied_body, _ = _get(
        f"{dashboard_server}/api/v1/dashboard/notes/30-ideas/idea.md"
    )
    assert denied_status == 401
    assert json.loads(denied_body)["error"]["code"] == "UNAUTHORIZED"


def test_dashboard_note_view_rejects_unexposed_folders_and_path_traversal(
    dashboard_server: str,
) -> None:
    folder_status, folder_body, _ = _get(
        f"{dashboard_server}/api/v1/dashboard/notes/20-notes/idea.md", token=TOKEN
    )
    traversal_status, traversal_body, _ = _get(
        f"{dashboard_server}/api/v1/dashboard/notes/30-ideas/%2E%2E%2Fidea.md",
        token=TOKEN,
    )

    assert folder_status == 400
    assert json.loads(folder_body)["error"]["code"] == "DASHBOARD_FOLDER_INVALID"
    assert traversal_status == 400
    assert json.loads(traversal_body)["error"]["code"] == "DASHBOARD_NOTE_INVALID"


def test_dashboard_m4a_playback_is_authenticated_and_no_store(
    dashboard_server: str,
) -> None:
    status, body, headers = _get(
        f"{dashboard_server}/api/v1/dashboard/audio/recording.m4a", token=TOKEN
    )

    assert status == 200
    assert body == b"audio"
    assert headers["Content-Type"] == "audio/mp4"
    assert headers["Cache-Control"] == "no-store"
    assert headers["X-Content-Type-Options"] == "nosniff"

    invalid_status, invalid_body, _ = _get(
        f"{dashboard_server}/api/v1/dashboard/audio/recording.wav", token=TOKEN
    )
    assert invalid_status == 400
    assert json.loads(invalid_body)["error"]["code"] == "DASHBOARD_AUDIO_INVALID"


def test_dashboard_summary_is_user_scoped_and_contains_operational_facts(
    dashboard_server: str,
) -> None:
    status, body, headers = _get(
        f"{dashboard_server}/api/v1/dashboard/summary", token=TOKEN
    )
    payload = json.loads(body)

    assert status == 200
    assert payload["status"] == "ok"
    assert payload["queue"]["count"] == 1
    assert payload["queue"]["items"][0]["name"] == "recording.m4a"
    assert payload["notes"]["count"] == 1
    assert payload["notes"]["items"][0]["name"] == "idea.md"
    assert payload["user"]["inboxLabel"] == "inbox"
    assert "path" not in json.dumps(payload)
    assert headers["Cache-Control"] == "no-store"


def test_dashboard_keeps_existing_public_health_endpoint(dashboard_server: str) -> None:
    status, body, _ = _get(f"{dashboard_server}/api/v1/health")

    assert status == 200
    assert json.loads(body)["status"] == "ok"
