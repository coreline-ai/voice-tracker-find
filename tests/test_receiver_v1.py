"""Receiver v1 HTTP contract tests.

These tests intentionally exercise the real ``ThreadingHTTPServer`` instead of
calling handler methods directly.  The legacy endpoints remain covered here
because the existing APK and the new client must be able to use one receiver
during the migration window.
"""

from __future__ import annotations

import hashlib
import http.client
import json
import os
import shutil
import socket
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections.abc import Iterator, Mapping
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from dataclasses import dataclass, replace
from email.message import Message
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, urlsplit

import pytest

from thinktank.config import Settings
from thinktank.receiver import create_server
from thinktank.receiver_v1 import STALE_UPLOAD_TEMP_SECONDS
from thinktank.users import User


TOKEN = "v1-contract-token"
USER = "user1"


@dataclass(frozen=True)
class Response:
    status: int
    body: bytes
    headers: Mapping[str, str]

    def json(self) -> object:
        return json.loads(self.body.decode("utf-8"))


@dataclass(frozen=True)
class Receiver:
    base_url: str
    ingest_dir: Path
    vault: Path


def _make_user(ingest_dir: Path, vault: Path) -> User:
    settings = Settings(
        claude_api_key="test-key",
        ingest_dir=ingest_dir,
        obsidian_vault=vault,
        db_path=ingest_dir.parent / "pipeline.db",
        temp_dir=ingest_dir.parent / "temp",
        whisper_model="large-v3",
        vad_sample_rate=16000,
        vad_threshold=0.5,
        retention_days=7,
    )
    # An empty configured name is the existing single-user compatibility mode.
    return User(name="", token=TOKEN, settings=replace(settings))


def _headers(message: Message) -> dict[str, str]:
    return {key: value for key, value in message.items()}


def _request(
    url: str,
    *,
    method: str = "GET",
    data: bytes | None = None,
    token: str | None = TOKEN,
    headers: Mapping[str, str] | None = None,
) -> Response:
    request = urllib.request.Request(url, data=data, method=method)  # noqa: S310
    if token is not None:
        request.add_header("Authorization", f"Bearer {token}")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    if data is not None and "Content-Length" not in (headers or {}):
        request.add_header("Content-Length", str(len(data)))
    try:
        with urllib.request.urlopen(request, timeout=10) as response:  # noqa: S310
            return Response(
                response.status,
                response.read(),
                _headers(response.headers),
            )
    except urllib.error.HTTPError as exc:
        return Response(exc.code, exc.read(), _headers(exc.headers))


def _json_request(
    url: str,
    payload: Mapping[str, object],
    *,
    method: str,
    headers: Mapping[str, str] | None = None,
) -> Response:
    merged_headers = {"Content-Type": "application/json"}
    merged_headers.update(headers or {})
    return _request(
        url,
        method=method,
        data=json.dumps(payload, ensure_ascii=False).encode(),
        headers=merged_headers,
    )


def _raw_request(
    receiver: Receiver,
    path: str,
    *,
    method: str,
    headers: Mapping[str, str],
    body: bytes = b"",
    close_write: bool = False,
) -> Response:
    """Issue deliberately malformed HTTP bodies that urllib normalizes away."""
    parsed = urlsplit(receiver.base_url)
    assert parsed.hostname and parsed.port
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=5)
    connection.putrequest(method, path)
    for key, value in headers.items():
        connection.putheader(key, value)
    connection.endheaders()
    if body:
        connection.send(body)
    if close_write:
        assert connection.sock is not None
        connection.sock.shutdown(socket.SHUT_WR)
    try:
        response = connection.getresponse()
        return Response(response.status, response.read(), _headers(response.headers))
    finally:
        connection.close()


def _v1_upload_url(receiver: Receiver, filename: str) -> str:
    return f"{receiver.base_url}/api/v1/upload/{USER}/{quote(filename, safe='')}"


def _upload(
    receiver: Receiver,
    filename: str,
    body: bytes,
    *,
    declared_hash: str | None = None,
    token: str | None = TOKEN,
    idempotency_key: str | None = None,
    recording_id: str | None = None,
    chunk_id: str | None = None,
) -> Response:
    stable_recording_id = recording_id or str(
        uuid.uuid5(uuid.NAMESPACE_URL, f"thinktank-recording:{filename}")
    )
    stable_chunk_id = chunk_id or str(
        uuid.uuid5(uuid.NAMESPACE_URL, f"thinktank-chunk:{filename}")
    )
    return _request(
        _v1_upload_url(receiver, filename),
        method="PUT",
        data=body,
        token=token,
        headers={
            "X-Content-SHA256": declared_hash or hashlib.sha256(body).hexdigest(),
            "Idempotency-Key": idempotency_key or stable_chunk_id,
            "X-Recording-ID": stable_recording_id,
            "X-Chunk-ID": stable_chunk_id,
        },
    )


def _v1_notes_url(receiver: Receiver) -> str:
    return f"{receiver.base_url}/api/v1/notes/{USER}"


def _note_items(payload: object) -> list[dict[str, object]]:
    """Accept only the two list envelope shapes used during contract review."""
    if isinstance(payload, list):
        items = payload
    elif isinstance(payload, dict):
        items = payload.get("notes")
    else:
        items = None
    assert isinstance(items, list), payload
    assert all(isinstance(item, dict) for item in items)
    return items


def _entity(payload: object, key: str) -> dict[str, object]:
    assert isinstance(payload, dict), payload
    value = payload.get(key)
    # The reviewed wire contract is flat.  Accept the early envelope shape too
    # so the semantic concurrency/idempotency checks remain useful during the
    # short migration window.
    if isinstance(value, dict):
        return value
    return payload


def _assert_json_response(response: Response) -> object:
    content_type = response.headers.get("Content-Type", "")
    assert content_type.startswith("application/json"), response.body
    return response.json()


def _assert_structured_error(response: Response, code: str | None = None) -> None:
    payload = _assert_json_response(response)
    assert isinstance(payload, dict)
    error = payload.get("error")
    assert isinstance(error, dict)
    assert isinstance(error.get("code"), str) and error["code"]
    assert isinstance(error.get("message"), str) and error["message"]
    assert isinstance(payload.get("requestId"), str) and payload["requestId"]
    assert response.headers.get("X-Request-ID") == payload["requestId"]
    if code is not None:
        assert error["code"] == code


@contextmanager
def _running_receiver(
    ingest_dir: Path,
    vault: Path,
    *,
    apk_path: Path | None = None,
) -> Iterator[Receiver]:
    ingest_dir.mkdir(parents=True, exist_ok=True)
    vault.mkdir(parents=True, exist_ok=True)
    server: ThreadingHTTPServer = create_server(
        users=[_make_user(ingest_dir, vault)],
        host="127.0.0.1",
        port=0,
        apk_path=apk_path,
    )
    # Contract tests must not launch the real processing pipeline.
    server.auto_process = False
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address[0], server.server_address[1]
    try:
        yield Receiver(f"http://{host}:{port}", ingest_dir, vault)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


@pytest.fixture
def receiver(tmp_path: Path) -> Iterator[Receiver]:
    ingest_dir = tmp_path / "inbox"
    vault = tmp_path / "vault"
    for folder in ("1 wiki", "10-daily", "30-ideas"):
        (vault / folder).mkdir(parents=True, exist_ok=True)
    (vault / "30-ideas" / "existing.md").write_text(
        "# Existing\n\noriginal",
        encoding="utf-8",
    )
    with _running_receiver(ingest_dir, vault) as running:
        yield running


def test_v1_upload_returns_hash_length_and_idempotent_status(
    receiver: Receiver,
) -> None:
    body = b"contract-audio"
    expected_hash = hashlib.sha256(body).hexdigest()

    created = _upload(receiver, "rec_20260723_uuid.m4a", body)
    assert created.status == 201
    created_payload = _entity(_assert_json_response(created), "upload")
    assert created_payload["filename"] == "rec_20260723_uuid.m4a"
    assert created_payload["recordingId"]
    assert created_payload["chunkId"]
    assert created_payload["size"] == len(body)
    assert created_payload["sha256"] == expected_hash
    assert (receiver.ingest_dir / "rec_20260723_uuid.m4a").read_bytes() == body

    replay = _upload(receiver, "rec_20260723_uuid.m4a", body)
    assert replay.status == 200
    replay_payload = _entity(_assert_json_response(replay), "upload")
    assert replay_payload["size"] == len(body)
    assert replay_payload["sha256"] == expected_hash

    conflict = _upload(receiver, "rec_20260723_uuid.m4a", b"different-content")
    assert conflict.status == 409
    _assert_structured_error(conflict)
    assert (receiver.ingest_dir / "rec_20260723_uuid.m4a").read_bytes() == body

    mismatch = _upload(
        receiver,
        "rec_hash_mismatch.m4a",
        b"actual-content",
        declared_hash="0" * 64,
    )
    assert mismatch.status == 422
    _assert_structured_error(mismatch, "HASH_MISMATCH")
    assert not (receiver.ingest_dir / "rec_hash_mismatch.m4a").exists()


@pytest.mark.parametrize(
    ("missing_header", "expected_code"),
    [
        ("X-Content-SHA256", "CONTENT_SHA256_REQUIRED"),
        ("Idempotency-Key", "IDEMPOTENCY_KEY_REQUIRED"),
        ("X-Recording-ID", "RECORDING_ID_REQUIRED"),
        ("X-Chunk-ID", "CHUNK_ID_REQUIRED"),
    ],
)
def test_v1_upload_requires_compose_identity_headers(
    receiver: Receiver,
    missing_header: str,
    expected_code: str,
) -> None:
    body = b"strict-contract"
    headers = {
        "X-Content-SHA256": hashlib.sha256(body).hexdigest(),
        "Idempotency-Key": "33333333-3333-4333-8333-333333333333",
        "X-Recording-ID": "11111111-1111-4111-8111-111111111111",
        "X-Chunk-ID": "22222222-2222-4222-8222-222222222222",
    }
    headers.pop(missing_header)

    response = _request(
        _v1_upload_url(receiver, f"missing-{missing_header.lower()}.m4a"),
        method="PUT",
        data=body,
        headers=headers,
    )

    assert response.status == 400
    _assert_structured_error(response, expected_code)


def test_v1_upload_rejects_empty_audio(receiver: Receiver) -> None:
    response = _request(
        _v1_upload_url(receiver, "empty.m4a"),
        method="PUT",
        data=b"",
        headers={
            "X-Content-SHA256": hashlib.sha256(b"").hexdigest(),
            "Idempotency-Key": "33333333-3333-4333-8333-333333333333",
            "X-Recording-ID": "11111111-1111-4111-8111-111111111111",
            "X-Chunk-ID": "22222222-2222-4222-8222-222222222222",
        },
    )

    assert response.status == 400
    _assert_structured_error(response, "EMPTY_UPLOAD")
    assert not (receiver.ingest_dir / "empty.m4a").exists()


@pytest.mark.parametrize(
    ("content_length", "body", "close_write", "expected_status", "expected_code"),
    [
        (None, b"", False, 411, "CONTENT_LENGTH_REQUIRED"),
        ("not-a-number", b"", False, 400, "INVALID_CONTENT_LENGTH"),
        (str(2 * 1024 * 1024 * 1024 + 1), b"", False, 413, "UPLOAD_TOO_LARGE"),
        ("12", b"short", True, 400, "INCOMPLETE_BODY"),
    ],
)
def test_v1_upload_rejects_invalid_or_incomplete_content_lengths(
    receiver: Receiver,
    content_length: str | None,
    body: bytes,
    close_write: bool,
    expected_status: int,
    expected_code: str,
) -> None:
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "X-Content-SHA256": hashlib.sha256(body).hexdigest(),
        "Idempotency-Key": "33333333-3333-4333-8333-333333333333",
        "X-Recording-ID": "11111111-1111-4111-8111-111111111111",
        "X-Chunk-ID": "22222222-2222-4222-8222-222222222222",
    }
    if content_length is not None:
        headers["Content-Length"] = content_length
    response = _raw_request(
        receiver,
        f"/api/v1/upload/{USER}/invalid-length.m4a",
        method="PUT",
        headers=headers,
        body=body,
        close_write=close_write,
    )

    assert response.status == expected_status
    _assert_structured_error(response, expected_code)
    assert not (receiver.ingest_dir / "invalid-length.m4a").exists()


def test_v1_upload_returns_structured_507_before_writing(
    receiver: Receiver,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "thinktank.adapters.local_receiver.has_room_for", lambda *_: False
    )
    response = _upload(receiver, "no-space.m4a", b"audio")
    assert response.status == 507
    _assert_structured_error(response, "INSUFFICIENT_STORAGE")
    assert not (receiver.ingest_dir / "no-space.m4a").exists()


def test_v1_upload_receipt_survives_ingest_move_and_server_restart(
    tmp_path: Path,
) -> None:
    ingest_dir = tmp_path / "inbox"
    vault = tmp_path / "vault"
    (vault / "10-daily").mkdir(parents=True)
    filename = "rec_persisted_receipt.m4a"
    body = b"move-me-after-receipt"

    with _running_receiver(ingest_dir, vault) as first:
        first_response = _upload(first, filename, body)
        assert first_response.status == 201

    processed = tmp_path / "processed"
    processed.mkdir()
    shutil.move(str(ingest_dir / filename), processed / filename)

    with _running_receiver(ingest_dir, vault) as restarted:
        replay = _upload(restarted, filename, body)
        assert replay.status == 200
        assert not (ingest_dir / filename).exists()

        conflict = _upload(restarted, filename, b"same-name-new-content")
        assert conflict.status == 409
        _assert_structured_error(conflict)
        assert not (ingest_dir / filename).exists()


def test_v1_parallel_identical_upload_has_one_creator_and_no_corruption(
    receiver: Receiver,
) -> None:
    body = b"parallel-audio-" * 4096

    def upload_once(_: int) -> Response:
        return _upload(receiver, "rec_parallel.m4a", body)

    with ThreadPoolExecutor(max_workers=8) as executor:
        responses = list(executor.map(upload_once, range(20)))

    statuses = [response.status for response in responses]
    assert statuses.count(201) == 1
    assert set(statuses) == {200, 201}
    assert (receiver.ingest_dir / "rec_parallel.m4a").read_bytes() == body
    expected_hash = hashlib.sha256(body).hexdigest()
    for response in responses:
        payload = _entity(_assert_json_response(response), "upload")
        assert payload["size"] == len(body)
        assert payload["sha256"] == expected_hash
    assert not list(receiver.ingest_dir.glob("*.part"))
    assert not list(receiver.ingest_dir.glob(".thinktank.*"))


def test_v1_reused_recording_chunk_with_new_key_is_a_structured_conflict(
    receiver: Receiver,
) -> None:
    """The receipt's chunk unique index must never leak an SQLite 500."""
    recording_id = "11111111-1111-4111-8111-111111111111"
    chunk_id = "22222222-2222-4222-8222-222222222222"
    created = _upload(
        receiver,
        "first.m4a",
        b"first-content",
        idempotency_key="33333333-3333-4333-8333-333333333333",
        recording_id=recording_id,
        chunk_id=chunk_id,
    )
    assert created.status == 201

    duplicate_chunk = _upload(
        receiver,
        "second.m4a",
        b"different-content",
        idempotency_key="44444444-4444-4444-8444-444444444444",
        recording_id=recording_id,
        chunk_id=chunk_id,
    )
    assert duplicate_chunk.status == 409
    _assert_structured_error(duplicate_chunk, "UPLOAD_CONFLICT")
    assert not (receiver.ingest_dir / "second.m4a").exists()


def test_v1_startup_cleans_only_stale_request_temp_files(tmp_path: Path) -> None:
    ingest_dir = tmp_path / "inbox"
    vault = tmp_path / "vault"
    ingest_dir.mkdir()
    vault.mkdir()
    stale = ingest_dir / ".thinktank-v1.interrupted.part"
    recent = ingest_dir / ".thinktank-v1.active.part"
    final_recording = ingest_dir / "recording.m4a"
    stale.write_bytes(b"interrupted")
    recent.write_bytes(b"still-relevant")
    final_recording.write_bytes(b"final")
    now = time.time()
    os.utime(
        stale,
        (now - STALE_UPLOAD_TEMP_SECONDS - 1, now - STALE_UPLOAD_TEMP_SECONDS - 1),
    )

    with _running_receiver(ingest_dir, vault):
        assert not stale.exists()
        assert recent.read_bytes() == b"still-relevant"
        assert final_recording.read_bytes() == b"final"


def test_v1_notes_have_stable_id_revision_and_updated_at_across_restart(
    tmp_path: Path,
) -> None:
    ingest_dir = tmp_path / "inbox"
    vault = tmp_path / "vault"
    (vault / "30-ideas").mkdir(parents=True)
    (vault / "30-ideas" / "stable.md").write_text("# Stable", encoding="utf-8")

    with _running_receiver(ingest_dir, vault) as first:
        first_items = _note_items(_request(_v1_notes_url(first)).json())
        assert len(first_items) == 1
        first_note = first_items[0]
        for field in ("id", "name", "folder", "content", "revision", "updatedAt"):
            assert first_note.get(field) not in (None, "")

    with _running_receiver(ingest_dir, vault) as restarted:
        second_items = _note_items(_request(_v1_notes_url(restarted)).json())
        assert len(second_items) == 1
        second_note = second_items[0]
        assert second_note["id"] == first_note["id"]
        assert second_note["revision"] == first_note["revision"]


def test_v1_notes_expose_archive_contents_to_mobile(receiver: Receiver) -> None:
    (receiver.vault / "90-archive").mkdir(parents=True)
    (receiver.vault / "90-archive" / "recording.md").write_text(
        "---\ntype: archive\ndate: 2026-07-24\n---\n# 전사 원본\n\n[00:00-00:01]\n확인용 전사",
        encoding="utf-8",
    )
    (receiver.vault / "90-archive" / "archived-note.md").write_text(
        "# 보관한 노트\n\n사용자가 보관한 일반 노트",
        encoding="utf-8",
    )

    notes = _note_items(_request(_v1_notes_url(receiver)).json())

    archive = next(note for note in notes if note["name"] == "recording.md")
    assert archive["folder"] == "90-archive"
    assert archive["content"] == (
        "---\ntype: archive\ndate: 2026-07-24\n---\n# 전사 원본\n\n[00:00-00:01]\n확인용 전사"
    )
    archived_note = next(note for note in notes if note["name"] == "archived-note.md")
    assert archived_note["folder"] == "90-archive"
    assert archived_note["content"] == "# 보관한 노트\n\n사용자가 보관한 일반 노트"


def test_v1_notes_keep_same_name_in_separate_folders_as_distinct_notes(
    receiver: Receiver,
) -> None:
    for folder, content in (("1 wiki", "wiki copy"), ("30-ideas", "idea copy")):
        (receiver.vault / folder / "same-name.md").write_text(content, encoding="utf-8")

    notes = _note_items(_request(_v1_notes_url(receiver)).json())
    matches = [note for note in notes if note["name"] == "same-name.md"]
    assert {(note["folder"], note["content"]) for note in matches} == {
        ("1 wiki", "wiki copy"),
        ("30-ideas", "idea copy"),
    }
    assert len({note["id"] for note in matches}) == 2


def test_v1_note_create_if_match_conflict_and_etag(receiver: Receiver) -> None:
    created = _json_request(
        _v1_notes_url(receiver),
        {
            "name": "created.md",
            "folder": "30-ideas",
            "content": "# Created\n\nfirst",
        },
        method="POST",
    )
    assert created.status == 201
    created_note = _entity(_assert_json_response(created), "note")
    note_id = created_note["id"]
    assert created_note["name"] == "created.md"
    assert created_note["folder"] == "30-ideas"
    first_revision = str(created_note["revision"])
    first_etag = created.headers.get("ETag")
    assert first_etag
    assert first_etag.strip('"') == first_revision

    note_url = f"{_v1_notes_url(receiver)}/{quote(str(note_id), safe='')}"
    missing_precondition = _json_request(
        note_url,
        {"content": "# Created\n\nwithout precondition"},
        method="PUT",
    )
    assert missing_precondition.status == 428
    _assert_structured_error(missing_precondition)

    updated = _json_request(
        note_url,
        {"content": "# Created\n\nupdated"},
        method="PUT",
        headers={"If-Match": first_etag},
    )
    assert updated.status == 200
    updated_note = _entity(_assert_json_response(updated), "note")
    assert updated_note["id"] == note_id
    assert str(updated_note["revision"]) != first_revision
    next_etag = updated.headers.get("ETag")
    assert next_etag and next_etag != first_etag
    assert next_etag.strip('"') == str(updated_note["revision"])

    stale = _json_request(
        note_url,
        {"content": "# Created\n\nmust not win"},
        method="PUT",
        headers={"If-Match": first_etag},
    )
    assert stale.status == 412
    _assert_structured_error(stale)
    assert (receiver.vault / "30-ideas" / "created.md").read_text(
        encoding="utf-8"
    ) == "# Created\n\nupdated"


def test_v1_concurrent_note_updates_allow_exactly_one_revision_winner(
    receiver: Receiver,
) -> None:
    created = _json_request(
        _v1_notes_url(receiver),
        {"name": "race.md", "folder": "30-ideas", "content": "base"},
        method="POST",
    )
    assert created.status == 201
    note = _entity(_assert_json_response(created), "note")
    note_url = f"{_v1_notes_url(receiver)}/{quote(str(note['id']), safe='')}"
    etag = f'"{note["revision"]}"'

    def update(content: str) -> Response:
        return _json_request(
            note_url,
            {"content": content},
            method="PUT",
            headers={"If-Match": etag},
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        responses = list(executor.map(update, ("first contender", "second contender")))

    assert sorted(response.status for response in responses) == [200, 412]
    for response in responses:
        if response.status == 412:
            _assert_structured_error(response, "REVISION_CONFLICT")
    stored = (receiver.vault / "30-ideas" / "race.md").read_text(encoding="utf-8")
    assert stored in {"first contender", "second contender"}


def test_v1_rejects_note_symlink_after_its_identity_exists(receiver: Receiver) -> None:
    note_path = receiver.vault / "30-ideas" / "existing.md"
    notes = _note_items(_request(_v1_notes_url(receiver)).json())
    note = next(item for item in notes if item["name"] == "existing.md")
    external = receiver.vault.parent / "outside.md"
    external.write_text("not exposed", encoding="utf-8")
    note_path.unlink()
    try:
        note_path.symlink_to(external)
    except OSError as exc:
        pytest.skip(f"symbolic links are unavailable in this environment: {exc}")

    response = _request(
        f"{_v1_notes_url(receiver)}/{quote(str(note['id']), safe='')}",
    )
    assert response.status == 400
    _assert_structured_error(response, "UNSAFE_NOTE_PATH")


def test_v1_note_archive_delete_is_idempotent(receiver: Receiver) -> None:
    items = _note_items(_request(_v1_notes_url(receiver)).json())
    note = next(item for item in items if item["name"] == "existing.md")
    note_url = f"{_v1_notes_url(receiver)}/{quote(str(note['id']), safe='')}"
    etag = f'"{note["revision"]}"'

    first = _request(
        note_url,
        method="DELETE",
        headers={"If-Match": etag},
    )
    assert first.status == 200
    first_payload = _entity(_assert_json_response(first), "note")
    assert first_payload.get("status") == "archived"
    assert first_payload.get("archivedAt")
    archived_files = list((receiver.vault / "90-archive").glob("existing*.md"))
    assert len(archived_files) == 1

    replay = _request(
        note_url,
        method="DELETE",
        headers={"If-Match": etag},
    )
    assert replay.status == 200
    replay_payload = _entity(_assert_json_response(replay), "note")
    assert replay_payload.get("status") == "archived"
    assert replay_payload.get("archivedAt") == first_payload.get("archivedAt")
    assert list((receiver.vault / "90-archive").glob("existing*.md")) == archived_files


def test_v1_note_archive_rejects_an_already_archived_source(receiver: Receiver) -> None:
    archive = receiver.vault / "90-archive"
    archive.mkdir(parents=True, exist_ok=True)
    original = archive / "transcript.md"
    original.write_text(
        "---\ntype: archive\nsource_file: recording.m4a\n---\n# 전사 원본",
        encoding="utf-8",
    )

    items = _note_items(_request(_v1_notes_url(receiver)).json())
    note = next(item for item in items if item["name"] == "transcript.md")
    response = _request(
        f"{_v1_notes_url(receiver)}/{quote(str(note['id']), safe='')}",
        method="DELETE",
        headers={"If-Match": f'"{note["revision"]}"'},
    )

    assert response.status == 409
    _assert_structured_error(response, "ARCHIVE_READ_ONLY")
    assert original.is_file()
    assert not (archive / "transcript_2.md").exists()


def test_v1_apk_info_and_download_are_authenticated_and_hashed(tmp_path: Path) -> None:
    ingest_dir = tmp_path / "inbox"
    vault = tmp_path / "vault"
    apk = tmp_path / "thinktank-next.apk"
    apk_bytes = b"not-a-real-apk-but-an-exact-byte-contract-fixture"
    apk.write_bytes(apk_bytes)
    apk.with_suffix(".version.json").write_text(
        json.dumps({"versionCode": 7, "versionName": "1.0.7", "releaseNotes": "QA"}),
        encoding="utf-8",
    )

    with _running_receiver(ingest_dir, vault, apk_path=apk) as running:
        info = _request(f"{running.base_url}/api/v1/apk/info")
        assert info.status == 200
        payload = _assert_json_response(info)
        assert isinstance(payload, dict)
        assert payload["versionCode"] == 7
        assert payload["versionName"] == "1.0.7"
        assert payload["sha256"] == hashlib.sha256(apk_bytes).hexdigest()
        assert payload["size"] == len(apk_bytes)
        assert payload["downloadUrl"] == "/api/v1/apk"

        download = _request(f"{running.base_url}/api/v1/apk")
        assert download.status == 200
        assert download.body == apk_bytes
        assert download.headers["Content-Type"].startswith(
            "application/vnd.android.package-archive"
        )

        unauthorized = _request(
            f"{running.base_url}/api/v1/apk/info",
            token=None,
        )
        assert unauthorized.status == 401
        _assert_structured_error(unauthorized, "UNAUTHORIZED")


def test_v1_and_legacy_contracts_work_on_the_same_server(receiver: Receiver) -> None:
    legacy_audio = b"legacy-apk-audio"
    legacy_upload = _request(
        f"{receiver.base_url}/upload/{USER}/legacy.m4a",
        method="PUT",
        data=legacy_audio,
    )
    assert legacy_upload.status == 201

    v1_upload = _upload(receiver, "v1.m4a", b"v1-client-audio")
    assert v1_upload.status == 201

    legacy_notes = _request(f"{receiver.base_url}/notes/{USER}")
    assert legacy_notes.status == 200
    assert any(note["name"] == "existing.md" for note in legacy_notes.json())

    legacy_update = _request(
        f"{receiver.base_url}/notes/{USER}/existing.md",
        method="PUT",
        data=b"# Updated by legacy APK",
        headers={"Content-Type": "text/markdown; charset=utf-8"},
    )
    assert legacy_update.status == 200

    v1_items = _note_items(_request(_v1_notes_url(receiver)).json())
    v1_note = next(item for item in v1_items if item["name"] == "existing.md")
    assert v1_note["content"] == "# Updated by legacy APK"
    assert v1_note["id"]
    assert v1_note["revision"]


@pytest.mark.parametrize(
    ("request_factory", "expected_status"),
    [
        (
            lambda receiver: _request(
                _v1_upload_url(receiver, "unauthorized.m4a"),
                method="PUT",
                data=b"x",
                token=None,
                headers={"X-Content-SHA256": hashlib.sha256(b"x").hexdigest()},
            ),
            401,
        ),
        (
            lambda receiver: _request(
                f"{receiver.base_url}/api/v1/upload/{USER}/bad%3Aname.m4a",
                method="PUT",
                data=b"x",
                headers={"X-Content-SHA256": hashlib.sha256(b"x").hexdigest()},
            ),
            400,
        ),
        (
            lambda receiver: _json_request(
                _v1_notes_url(receiver),
                {
                    "name": "../escape.md",
                    "folder": "30-ideas",
                    "content": "unsafe",
                },
                method="POST",
            ),
            400,
        ),
    ],
)
def test_v1_errors_are_structured_json(
    receiver: Receiver,
    request_factory,
    expected_status: int,
) -> None:
    response = request_factory(receiver)
    assert response.status == expected_status
    _assert_structured_error(response)
