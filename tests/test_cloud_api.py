"""Cloud API contract, streaming, authentication, and runtime tests."""

from __future__ import annotations

import asyncio
import hashlib
import json
import socket
import time
import uuid
from dataclasses import dataclass, replace
from typing import BinaryIO

import httpx
import pytest
from h2.config import H2Configuration
from h2.connection import H2Connection
from h2.events import DataReceived, ResponseReceived, StreamEnded
from hypercorn.asyncio import serve
from sqlalchemy.exc import TimeoutError as PoolTimeoutError
from starlette.requests import ClientDisconnect

from airvoice.cloud_api import (
    CloudApiServices,
    build_hypercorn_config,
    create_cloud_app,
)
from airvoice.cloud_admin import issue_token
from airvoice.receiver_v1 import V1Error, normalize_etag
from airvoice.server.auth import BearerTokenAuthenticator, CloudPrincipal
from airvoice.server.runtime import (
    CloudApiConfigurationError,
    CloudApiSettings,
)
from airvoice.server.security import token_digest
from airvoice.server.stream_bridge import STREAM_CHUNK_SIZE

TOKEN = "phase3-test-bearer"
USER = "user1"


class FakeAuthenticator:
    def authenticate(self, authorization: str | None) -> CloudPrincipal:
        if authorization != f"Bearer {TOKEN}":
            raise V1Error(
                401,
                "UNAUTHORIZED",
                "A valid Bearer token is required",
            )
        return CloudPrincipal(name=USER)


class FakeReadiness:
    def __init__(self) -> None:
        self.failure: BaseException | None = None

    def ping(self) -> None:
        if self.failure is not None:
            raise self.failure


@dataclass(frozen=True)
class FakeReceipt:
    payload: dict[str, object]

    def as_dict(self) -> dict[str, object]:
        return dict(self.payload)


class FakeReceiver:
    def __init__(self) -> None:
        self.uploads: dict[str, tuple[int, str, dict[str, object]]] = {}
        self.max_read_size = 0
        self.peak_bridge_bytes = 0
        self.notes: dict[str, dict[str, object]] = {}

    @staticmethod
    def ensure_upload_capacity(user, length: int) -> None:  # noqa: ANN001
        assert user.name == USER
        assert length > 0

    def receive_upload(
        self,
        *,
        user,
        filename: str,
        length: int,
        declared_sha256: str | None,
        idempotency_key: str,
        recording_id: str,
        chunk_id: str,
        source: BinaryIO,
    ) -> tuple[FakeReceipt, bool]:
        assert user.name == USER
        digest = hashlib.sha256()
        received = 0
        while received < length:
            chunk = source.read(min(STREAM_CHUNK_SIZE, length - received))
            if not chunk:
                raise V1Error(
                    400,
                    "INCOMPLETE_BODY",
                    "Upload ended before Content-Length",
                )
            self.max_read_size = max(self.max_read_size, len(chunk))
            digest.update(chunk)
            received += len(chunk)
        self.peak_bridge_bytes = int(
            getattr(source, "peak_buffered_bytes", 0)
        )
        actual_hash = digest.hexdigest()
        if actual_hash != declared_sha256:
            raise V1Error(
                422,
                "HASH_MISMATCH",
                "Uploaded content does not match SHA-256",
            )
        payload = {
            "uploadId": idempotency_key,
            "idempotencyKey": idempotency_key,
            "recordingId": recording_id,
            "chunkId": chunk_id,
            "filename": filename,
            "size": received,
            "sha256": actual_hash,
            "receivedAt": "2026-07-24T00:00:00Z",
        }
        existing = self.uploads.get(idempotency_key)
        if existing is not None:
            if existing[:2] != (received, actual_hash):
                raise V1Error(
                    409,
                    "UPLOAD_CONFLICT",
                    "Upload identity belongs to different content",
                )
            return FakeReceipt(existing[2]), False
        self.uploads[idempotency_key] = (received, actual_hash, payload)
        return FakeReceipt(payload), True

    def list_notes(self, user, collected) -> list[dict[str, object]]:  # noqa: ANN001
        assert user.name == USER
        assert collected == []
        return list(self.notes.values())

    def get_note(self, user, note_id: str) -> dict[str, object]:  # noqa: ANN001
        assert user.name == USER
        try:
            return dict(self.notes[note_id])
        except KeyError as exc:
            raise V1Error(404, "NOTE_NOT_FOUND", "Note does not exist") from exc

    def create_note(
        self,
        user,  # noqa: ANN001
        *,
        folder: str,
        name: str,
        content: str,
    ) -> dict[str, object]:
        assert user.name == USER
        note_id = str(uuid.uuid4())
        note = self._note(note_id, folder, name, content)
        self.notes[note_id] = note
        return dict(note)

    def update_note(
        self,
        user,  # noqa: ANN001
        note_id: str,
        *,
        content: str,
        if_match: str | None,
    ) -> dict[str, object]:
        assert user.name == USER
        existing = self.get_note(user, note_id)
        if normalize_etag(if_match) != existing["revision"]:
            raise V1Error(
                412,
                "REVISION_CONFLICT",
                "Note changed since it was downloaded",
            )
        note = self._note(
            note_id,
            str(existing["folder"]),
            str(existing["name"]),
            content,
        )
        self.notes[note_id] = note
        return dict(note)

    def archive_note(
        self,
        user,  # noqa: ANN001
        note_id: str,
        *,
        if_match: str | None,
        archive_dir: str,
    ) -> dict[str, object]:
        assert archive_dir == "90-archive"
        existing = self.get_note(user, note_id)
        if normalize_etag(if_match) != existing["revision"]:
            raise V1Error(
                412,
                "REVISION_CONFLICT",
                "Note changed since it was downloaded",
            )
        del self.notes[note_id]
        return {
            "id": note_id,
            "archived": True,
            "status": "archived",
            "archivedAt": "2026-07-24T00:00:00Z",
        }

    @staticmethod
    def apk_info(apk) -> dict[str, object]:  # noqa: ANN001
        raise AssertionError(apk)

    @staticmethod
    def _note(
        note_id: str,
        folder: str,
        name: str,
        content: str,
    ) -> dict[str, object]:
        return {
            "id": note_id,
            "folder": folder,
            "name": name,
            "content": content,
            "revision": hashlib.sha256(content.encode()).hexdigest(),
            "updatedAt": "2026-07-24T00:00:00Z",
        }


def _settings(*, timeout: int = 30) -> CloudApiSettings:
    return CloudApiSettings(
        database_url="postgresql+psycopg://unused",
        gcs_bucket="unused",
        token_pepper=b"p" * 32,
        request_timeout_seconds=timeout,
    )


@dataclass
class ApiHarness:
    app: object
    receiver: FakeReceiver
    readiness: FakeReadiness


@pytest.fixture
def api() -> ApiHarness:
    receiver = FakeReceiver()
    readiness = FakeReadiness()
    services = CloudApiServices(
        receiver=receiver,
        authenticator=FakeAuthenticator(),
        readiness=readiness,
    )
    return ApiHarness(
        app=create_cloud_app(services, settings=_settings()),
        receiver=receiver,
        readiness=readiness,
    )


def _request(
    app,
    method: str,
    path: str,
    *,
    headers: dict[str, str] | None = None,
    content=b"",  # noqa: ANN001
) -> httpx.Response:
    async def execute() -> httpx.Response:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://testserver",
        ) as client:
            return await client.request(
                method,
                path,
                headers=headers,
                content=content,
            )

    return asyncio.run(execute())


def _auth_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {TOKEN}"}


def _audio_headers(
    *,
    length: int,
    sha256: str,
    upload_id: str | None = None,
) -> dict[str, str]:
    chunk_id = upload_id or str(uuid.uuid4())
    return {
        **_auth_headers(),
        "Content-Type": "audio/mp4",
        "Content-Length": str(length),
        "X-Content-SHA256": sha256,
        "Idempotency-Key": chunk_id,
        "X-Recording-ID": str(uuid.uuid4()),
        "X-Chunk-ID": chunk_id,
    }


def _repeated_hash(chunk: bytes, size: int) -> str:
    digest = hashlib.sha256()
    remaining = size
    while remaining:
        part = chunk[: min(len(chunk), remaining)]
        digest.update(part)
        remaining -= len(part)
    return digest.hexdigest()


def test_health_ready_request_id_and_redacted_trace_log(
    api: ApiHarness,
    caplog: pytest.LogCaptureFixture,
) -> None:
    trace_id = "0123456789abcdef0123456789abcdef"
    caplog.set_level("INFO", logger="airvoice.cloud_api")
    response = _request(
        api.app,
        "GET",
        "/api/v1/health",
        headers={
            "X-Request-ID": "phase3-health",
            "X-Cloud-Trace-Context": f"{trace_id}/123;o=1",
        },
    )

    assert response.status_code == 200
    assert response.headers["x-request-id"] == "phase3-health"
    assert response.json() == {
        "status": "ok",
        "apiVersion": "v1",
        "requestId": "phase3-health",
    }
    event = json.loads(caplog.records[-1].message)
    assert event["requestId"] == "phase3-health"
    assert event["traceId"] == trace_id
    assert "authorization" not in caplog.text.lower()

    ready = _request(api.app, "GET", "/api/v1/ready")
    assert ready.status_code == 200
    assert ready.json()["status"] == "ready"


@pytest.mark.parametrize("size_mib", [4, 28, 40])
def test_upload_streams_cloud_run_poc_sizes_with_bounded_memory(
    api: ApiHarness,
    size_mib: int,
) -> None:
    size = size_mib * 1024 * 1024
    chunk = bytes([size_mib]) * STREAM_CHUNK_SIZE
    expected_hash = _repeated_hash(chunk, size)

    async def body():
        remaining = size
        while remaining:
            part = chunk[: min(len(chunk), remaining)]
            yield part
            remaining -= len(part)

    response = _request(
        api.app,
        "PUT",
        f"/api/v1/upload/{USER}/recording-{size_mib}.m4a",
        headers=_audio_headers(length=size, sha256=expected_hash),
        content=body(),
    )

    assert response.status_code == 201, response.text
    assert response.json()["size"] == size
    assert response.json()["sha256"] == expected_hash
    assert api.receiver.max_read_size <= STREAM_CHUNK_SIZE
    assert api.receiver.peak_bridge_bytes <= 5 * STREAM_CHUNK_SIZE


def test_upload_is_idempotent_and_rejects_hash_mismatch(api: ApiHarness) -> None:
    body = b"cloud-api-upload"
    digest = hashlib.sha256(body).hexdigest()
    upload_id = str(uuid.uuid4())
    headers = _audio_headers(
        length=len(body),
        sha256=digest,
        upload_id=upload_id,
    )

    created = _request(
        api.app,
        "PUT",
        f"/api/v1/upload/{USER}/same.m4a",
        headers=headers,
        content=body,
    )
    replay = _request(
        api.app,
        "PUT",
        f"/api/v1/upload/{USER}/same.m4a",
        headers=headers,
        content=body,
    )
    mismatch_headers = {
        **_audio_headers(
            length=len(body),
            sha256="0" * 64,
        )
    }
    mismatch = _request(
        api.app,
        "PUT",
        f"/api/v1/upload/{USER}/bad.m4a",
        headers=mismatch_headers,
        content=body,
    )

    assert created.status_code == 201
    assert created.json()["status"] == "created"
    assert replay.status_code == 200
    assert replay.json()["status"] == "already_exists"
    assert mismatch.status_code == 422
    assert mismatch.json()["error"]["code"] == "HASH_MISMATCH"


@pytest.mark.parametrize(
    ("path", "header_change", "expected_status", "expected_code"),
    [
        (
            "/api/v1/upload/other/valid.m4a",
            {},
            403,
            "USER_PATH_MISMATCH",
        ),
        (
            f"/api/v1/upload/{USER}/valid.m4a",
            {"Authorization": "Bearer wrong"},
            401,
            "UNAUTHORIZED",
        ),
        (
            f"/api/v1/upload/{USER}/valid.m4a",
            {"Content-Type": "audio/mpeg"},
            415,
            "UNSUPPORTED_MEDIA_TYPE",
        ),
        (
            f"/api/v1/upload/{USER}/CON.m4a",
            {},
            400,
            "INVALID_FILENAME",
        ),
        (
            f"/api/v1/upload/{USER}/valid.m4a",
            {"Content-Length": "2147483649"},
            413,
            "UPLOAD_TOO_LARGE",
        ),
        (
            f"/api/v1/upload/{USER}/valid.m4a",
            {"Idempotency-Key": "not-a-uuid"},
            400,
            "INVALID_IDEMPOTENCY_KEY",
        ),
    ],
)
def test_upload_rejects_bad_auth_path_mime_filename_and_headers(
    api: ApiHarness,
    path: str,
    header_change: dict[str, str],
    expected_status: int,
    expected_code: str,
) -> None:
    body = b"x"
    headers = {
        **_audio_headers(
            length=len(body),
            sha256=hashlib.sha256(body).hexdigest(),
        ),
        **header_change,
    }
    response = _request(
        api.app,
        "PUT",
        path,
        headers=headers,
        content=body,
    )

    assert response.status_code == expected_status
    assert response.json()["error"]["code"] == expected_code
    assert response.json()["requestId"] == response.headers["x-request-id"]
    if expected_status == 401:
        assert response.headers["www-authenticate"] == "Bearer"


def test_disconnected_stream_does_not_commit_upload(api: ApiHarness) -> None:
    body = b"x" * STREAM_CHUNK_SIZE
    declared_length = len(body) * 2

    async def disconnected():
        yield body
        raise ClientDisconnect()

    response = _request(
        api.app,
        "PUT",
        f"/api/v1/upload/{USER}/disconnect.m4a",
        headers=_audio_headers(
            length=declared_length,
            sha256="0" * 64,
        ),
        content=disconnected(),
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "CLIENT_DISCONNECTED"
    assert api.receiver.uploads == {}


def test_note_crud_etag_and_log_redaction(
    api: ApiHarness,
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level("INFO", logger="airvoice.cloud_api")
    secret_content = "body-must-not-appear-in-logs"
    create = _request(
        api.app,
        "POST",
        f"/api/v1/notes/{USER}",
        headers={**_auth_headers(), "Content-Type": "application/json"},
        content=json.dumps(
            {
                "folder": "30-ideas",
                "name": "phase3.md",
                "content": secret_content,
            }
        ).encode(),
    )
    assert create.status_code == 201, create.text
    note_id = create.json()["id"]
    etag = create.headers["etag"]

    listed = _request(
        api.app,
        "GET",
        f"/api/v1/notes/{USER}",
        headers=_auth_headers(),
    )
    assert listed.status_code == 200
    assert listed.json()["notes"][0]["id"] == note_id

    updated = _request(
        api.app,
        "PUT",
        f"/api/v1/notes/{USER}/{note_id}",
        headers={
            **_auth_headers(),
            "Content-Type": "application/json",
            "If-Match": etag,
        },
        content=json.dumps({"content": "updated"}).encode(),
    )
    assert updated.status_code == 200

    archived = _request(
        api.app,
        "DELETE",
        f"/api/v1/notes/{USER}/{note_id}",
        headers={
            **_auth_headers(),
            "If-Match": updated.headers["etag"],
        },
    )
    assert archived.status_code == 200
    assert archived.json()["archived"] is True
    assert TOKEN not in caplog.text
    assert secret_content not in caplog.text


def test_transient_readiness_failure_and_timeout_have_retry_envelope(
    api: ApiHarness,
) -> None:
    api.readiness.failure = PoolTimeoutError("pool unavailable")
    unavailable = _request(api.app, "GET", "/api/v1/ready")
    assert unavailable.status_code == 503
    assert unavailable.headers["retry-after"] == "5"
    assert unavailable.json()["error"]["code"] == "SERVICE_UNAVAILABLE"

    class SlowReadiness:
        @staticmethod
        def ping() -> None:
            time.sleep(0.05)

    timeout_app = create_cloud_app(
        CloudApiServices(
            receiver=api.receiver,
            authenticator=FakeAuthenticator(),
            readiness=SlowReadiness(),
        ),
        settings=replace(_settings(), request_timeout_seconds=0.01),
    )
    timeout = _request(timeout_app, "GET", "/api/v1/ready")
    assert timeout.status_code == 504
    assert timeout.headers["retry-after"] == "5"
    assert timeout.json()["error"]["code"] == "REQUEST_TIMEOUT"


def test_upload_timeout_cancels_consumer_before_commit() -> None:
    committed = False

    class SlowReceiver(FakeReceiver):
        def receive_upload(self, *, source: BinaryIO, length: int, **kwargs):
            nonlocal committed
            received = 0
            while received < length:
                chunk = source.read(length - received)
                if not chunk:
                    raise AssertionError("body unexpectedly ended")
                received += len(chunk)
            time.sleep(0.05)
            source.raise_if_cancelled()  # type: ignore[attr-defined]
            committed = True
            return super().receive_upload(
                source=source,
                length=length,
                **kwargs,
            )

    receiver = SlowReceiver()
    app = create_cloud_app(
        CloudApiServices(
            receiver=receiver,
            authenticator=FakeAuthenticator(),
            readiness=FakeReadiness(),
        ),
        settings=replace(_settings(), request_timeout_seconds=0.01),
    )
    body = b"timeout-body"
    response = _request(
        app,
        "PUT",
        f"/api/v1/upload/{USER}/timeout.m4a",
        headers=_audio_headers(
            length=len(body),
            sha256=hashlib.sha256(body).hexdigest(),
        ),
        content=body,
    )

    assert response.status_code == 504
    assert response.json()["error"]["code"] == "REQUEST_TIMEOUT"
    assert not committed
    assert receiver.uploads == {}


def test_unknown_exception_message_is_not_logged(
    api: ApiHarness,
    caplog: pytest.LogCaptureFixture,
) -> None:
    leaked = f"{TOKEN}?X-Goog-Signature=must-not-log"
    api.readiness.failure = RuntimeError(leaked)
    caplog.set_level("INFO", logger="airvoice.cloud_api")

    response = _request(api.app, "GET", "/api/v1/ready")

    assert response.status_code == 500
    assert response.json()["error"]["code"] == "INTERNAL_ERROR"
    assert leaked not in caplog.text
    assert TOKEN not in caplog.text
    assert "X-Goog-Signature" not in caplog.text


def test_authenticator_hashes_token_and_runtime_rejects_pool_overcommit() -> None:
    pepper = b"pepper-value-that-is-at-least-32-bytes"
    expected_digest = token_digest(TOKEN, pepper=pepper)

    class Lookup:
        def __init__(self) -> None:
            self.seen: str | None = None

        def resolve_token_digest(self, digest: str) -> str | None:
            self.seen = digest
            return USER if digest == expected_digest else None

    lookup = Lookup()
    authenticator = BearerTokenAuthenticator(lookup, pepper=pepper)
    assert authenticator.authenticate(f"Bearer {TOKEN}").name == USER
    assert lookup.seen == expected_digest
    assert lookup.seen != TOKEN

    with pytest.raises(CloudApiConfigurationError, match="exceeds"):
        CloudApiSettings.from_env(
            {
                "DATABASE_URL": "postgresql+psycopg://db",
                "GCS_BUCKET": "bucket",
                "TOKEN_PEPPER": "p" * 32,
                "DB_POOL_SIZE": "5",
                "DB_MAX_OVERFLOW": "3",
                "DB_CONNECTION_BUDGET": "7",
            }
        )


def test_token_issuer_returns_plaintext_once_and_stores_only_digest() -> None:
    pepper = b"p" * 32
    plaintext = "generated-opaque-token"

    class TokenData:
        def __init__(self) -> None:
            self.user_id: str | None = None
            self.digest: str | None = None

        def provision_user(self, user_id: str) -> None:
            self.user_id = user_id

        def store_token_digest(
            self,
            *,
            user_id: str,
            digest: str,
            version: int,
            expires_at,
        ) -> uuid.UUID:
            assert user_id == USER
            assert version == 2
            assert expires_at is not None
            self.digest = digest
            return uuid.UUID("11111111-1111-4111-8111-111111111111")

    data = TokenData()
    issued = issue_token(
        data,  # type: ignore[arg-type]
        user_id=USER,
        version=2,
        expires_in_days=90,
        pepper=pepper,
        token_factory=lambda _: plaintext,
    )

    assert issued.token == plaintext
    assert data.user_id == USER
    assert data.digest == token_digest(plaintext, pepper=pepper)
    assert data.digest != plaintext


def test_hypercorn_binds_cloud_run_port_without_tls() -> None:
    settings = _settings()
    config = build_hypercorn_config(settings)

    assert config.bind == ["0.0.0.0:8080"]
    assert config.certfile is None
    assert config.keyfile is None
    assert config.graceful_timeout == 8


def test_hypercorn_accepts_h2c_prior_knowledge(api: ApiHarness) -> None:
    async def execute() -> tuple[int, bytes]:
        with socket.socket() as reservation:
            try:
                reservation.bind(("127.0.0.1", 0))
            except PermissionError:
                pytest.skip("local sockets are disabled by the test sandbox")
            port = int(reservation.getsockname()[1])
        config = build_hypercorn_config(replace(_settings(), port=port))
        config.bind = [f"127.0.0.1:{port}"]
        config.errorlog = None
        shutdown = asyncio.Event()
        server = asyncio.create_task(
            serve(api.app, config, shutdown_trigger=shutdown.wait)
        )
        reader: asyncio.StreamReader | None = None
        writer: asyncio.StreamWriter | None = None
        try:
            for _ in range(100):
                try:
                    reader, writer = await asyncio.open_connection(
                        "127.0.0.1",
                        port,
                    )
                    break
                except OSError:
                    await asyncio.sleep(0.01)
            assert reader is not None
            assert writer is not None
            connection = H2Connection(
                config=H2Configuration(
                    client_side=True,
                    header_encoding="utf-8",
                )
            )
            connection.initiate_connection()
            connection.send_headers(
                1,
                [
                    (":method", "GET"),
                    (":authority", f"127.0.0.1:{port}"),
                    (":scheme", "http"),
                    (":path", "/api/v1/health"),
                ],
                end_stream=True,
            )
            writer.write(connection.data_to_send())
            await writer.drain()

            status = 0
            body = bytearray()
            complete = False
            while not complete:
                data = await asyncio.wait_for(reader.read(65535), timeout=2)
                assert data
                for event in connection.receive_data(data):
                    if isinstance(event, ResponseReceived):
                        status = int(dict(event.headers)[":status"])
                    elif isinstance(event, DataReceived):
                        body.extend(event.data)
                        connection.acknowledge_received_data(
                            event.flow_controlled_length,
                            event.stream_id,
                        )
                    elif isinstance(event, StreamEnded):
                        complete = True
                outbound = connection.data_to_send()
                if outbound:
                    writer.write(outbound)
                    await writer.drain()
            return status, bytes(body)
        finally:
            if writer is not None:
                writer.close()
                await writer.wait_closed()
            shutdown.set()
            await asyncio.wait_for(server, timeout=2)

    status, body = asyncio.run(execute())
    assert status == 200
    assert json.loads(body)["status"] == "ok"
