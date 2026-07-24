"""Conformance checks for the injectable Receiver V1 persistence boundary."""

from __future__ import annotations

import hashlib
import json
import threading
import urllib.error
import urllib.request
import uuid
from dataclasses import replace
from pathlib import Path
from typing import Any

import pytest

from thinktank.adapters.local_receiver import LocalReceiverV1Adapter, UploadReceipt
from thinktank.config import Settings
from thinktank.receiver import create_server
from thinktank.receiver_v1 import V1Error
from thinktank.server.ports import ReceiverV1Persistence
from thinktank.server.upload_domain import (
    UploadFingerprint,
    orphan_matches,
    receipt_matches,
)
from thinktank.users import User


class FakeCloudPersistence:
    """In-memory shape used to prove the handler has no local DB dependency."""

    def __init__(self) -> None:
        self.receipt: UploadReceipt | None = None
        self.notes: dict[str, dict[str, object]] = {}

    def ensure_upload_capacity(self, user: Any, length: int) -> None:
        return None

    def receive_upload(
        self,
        *,
        user: Any,
        filename: str,
        length: int,
        declared_sha256: str | None,
        idempotency_key: str,
        recording_id: str,
        chunk_id: str,
        source,
    ) -> tuple[UploadReceipt, bool]:
        body = source.read(length)
        actual_hash = hashlib.sha256(body).hexdigest()
        assert declared_sha256 == actual_hash
        if self.receipt is not None:
            if (
                self.receipt.recording_id != recording_id
                or self.receipt.chunk_id != chunk_id
                or self.receipt.filename != filename
                or self.receipt.size != length
                or self.receipt.sha256 != actual_hash
            ):
                raise V1Error(
                    409,
                    "UPLOAD_CONFLICT",
                    "Upload identity belongs to different content",
                )
            return self.receipt, False
        self.receipt = UploadReceipt(
            upload_id=idempotency_key,
            idempotency_key=idempotency_key,
            recording_id=recording_id,
            chunk_id=chunk_id,
            filename=filename,
            size=length,
            sha256=actual_hash,
            status="stored",
            received_at="2026-07-24T00:00:00Z",
        )
        return self.receipt, True

    def list_notes(self, user: Any, collected: list) -> list[dict[str, object]]:
        return list(self.notes.values())

    def get_note(self, user: Any, note_id: str) -> dict[str, object]:
        return self.notes[note_id]

    def create_note(
        self, user: Any, *, folder: str, name: str, content: str
    ) -> dict[str, object]:
        note_id = str(uuid.uuid4())
        note = self._note(note_id, folder, name, content)
        self.notes[note_id] = note
        return note

    def update_note(
        self,
        user: Any,
        note_id: str,
        *,
        content: str,
        if_match: str | None,
    ) -> dict[str, object]:
        note = self.notes[note_id]
        expected = (if_match or "").strip('"')
        if expected != note["revision"]:
            raise V1Error(412, "REVISION_CONFLICT", "Note revision changed")
        updated = self._note(
            note_id,
            str(note["folder"]),
            str(note["name"]),
            content,
        )
        self.notes[note_id] = updated
        return updated

    def archive_note(
        self,
        user: Any,
        note_id: str,
        *,
        if_match: str | None,
        archive_dir: str,
    ) -> dict[str, object]:
        note = self.notes[note_id]
        expected = (if_match or "").strip('"')
        if expected != note["revision"]:
            raise V1Error(412, "REVISION_CONFLICT", "Note revision changed")
        self.notes.pop(note_id)
        return {
            "id": note_id,
            "archived": True,
            "status": "archived",
            "archivedAt": "2026-07-24T00:00:00Z",
        }

    def apk_info(self, apk: Path) -> dict[str, object]:
        raise NotImplementedError

    @staticmethod
    def _note(
        note_id: str, folder: str, name: str, content: str
    ) -> dict[str, object]:
        return {
            "id": note_id,
            "folder": folder,
            "name": name,
            "content": content,
            "revision": hashlib.sha256(content.encode()).hexdigest(),
            "updatedAt": "2026-07-24T00:00:00Z",
        }


def _user(tmp_path: Path) -> User:
    ingest = tmp_path / "ingest"
    vault = tmp_path / "vault"
    ingest.mkdir()
    vault.mkdir()
    settings = Settings(
        claude_api_key="test-key",
        ingest_dir=ingest,
        obsidian_vault=vault,
        db_path=tmp_path / "pipeline.sqlite3",
        temp_dir=tmp_path / "temp",
        whisper_model="large-v3",
        vad_sample_rate=16000,
        vad_threshold=0.5,
        retention_days=7,
    )
    return User(name="", token="port-token", settings=replace(settings))


def _upload(url: str, body: bytes, identity: str) -> tuple[int, dict[str, object]]:
    filename = "port-test.m4a"
    request = urllib.request.Request(
        f"{url}/api/v1/upload/user1/{filename}",
        data=body,
        method="PUT",
        headers={
            "Authorization": "Bearer port-token",
            "Content-Length": str(len(body)),
            "X-Content-SHA256": hashlib.sha256(body).hexdigest(),
            "Idempotency-Key": identity,
            "X-Recording-ID": "11111111-1111-4111-8111-111111111111",
            "X-Chunk-ID": "22222222-2222-4222-8222-222222222222",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:  # noqa: S310
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read())


def test_local_adapter_implements_receiver_persistence_port(tmp_path: Path) -> None:
    adapter = LocalReceiverV1Adapter(tmp_path / "receiver.sqlite3", [])
    assert isinstance(adapter, ReceiverV1Persistence)


def test_http_handler_accepts_injected_cloud_shaped_persistence(tmp_path: Path) -> None:
    persistence = FakeCloudPersistence()
    assert isinstance(persistence, ReceiverV1Persistence)
    server = create_server(
        users=[_user(tmp_path)],
        host="127.0.0.1",
        port=0,
        v1_persistence=persistence,
    )
    server.auto_process = False
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address
    base_url = f"http://{host}:{port}"
    identity = str(uuid.uuid4())
    try:
        created_status, created = _upload(base_url, b"audio", identity)
        replay_status, replay = _upload(base_url, b"audio", identity)
        conflict_status, conflict = _upload(base_url, b"different", identity)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)

    assert created_status == 201
    assert created["status"] == "created"
    assert replay_status == 200
    assert replay["status"] == "already_exists"
    assert conflict_status == 409
    assert conflict["error"]["code"] == "UPLOAD_CONFLICT"
    assert persistence.receipt is not None


def test_fake_cloud_note_revision_conformance() -> None:
    persistence = FakeCloudPersistence()
    note = persistence.create_note(
        None,
        folder="30-ideas",
        name="idea.md",
        content="first",
    )
    stale_revision = str(note["revision"])

    updated = persistence.update_note(
        None,
        str(note["id"]),
        content="second",
        if_match=f'"{stale_revision}"',
    )
    assert updated["revision"] != stale_revision

    with pytest.raises(V1Error, match="revision") as raised:
        persistence.update_note(
            None,
            str(note["id"]),
            content="third",
            if_match=f'"{stale_revision}"',
        )
    assert raised.value.status == 412


def test_upload_domain_matches_receipts_and_orphans() -> None:
    upload = UploadFingerprint(
        recording_id="recording",
        chunk_id="chunk",
        filename="audio.m4a",
        size=5,
        sha256="a" * 64,
    )
    row = {
        "recording_id": "recording",
        "chunk_id": "chunk",
        "filename": "audio.m4a",
        "size": 5,
        "sha256": "a" * 64,
    }

    assert receipt_matches(row, upload)
    assert orphan_matches(
        existing_size=5,
        existing_sha256="a" * 64,
        upload=upload,
    )
    assert not orphan_matches(
        existing_size=6,
        existing_sha256="a" * 64,
        upload=upload,
    )
    assert not receipt_matches({**row, "chunk_id": "other"}, upload)


def test_injected_persistence_cannot_be_mixed_with_local_state_db(
    tmp_path: Path,
) -> None:
    persistence = FakeCloudPersistence()
    try:
        create_server(
            users=[_user(tmp_path)],
            host="127.0.0.1",
            port=0,
            state_db=tmp_path / "receiver.sqlite3",
            v1_persistence=persistence,
        )
    except ValueError as exc:
        assert "함께 지정" in str(exc)
    else:
        raise AssertionError("ambiguous persistence configuration was accepted")


def test_injected_cloud_persistence_skips_local_ingest_and_subprocess(
    tmp_path: Path,
) -> None:
    user = _user(tmp_path)
    user.settings.ingest_dir.rmdir()
    server = create_server(
        users=[user],
        host="127.0.0.1",
        port=0,
        v1_persistence=FakeCloudPersistence(),
    )
    try:
        assert not user.settings.ingest_dir.exists()
        assert not server.auto_process
    finally:
        server.server_close()
