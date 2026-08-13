"""Integration tests for the PostgreSQL receipt/outbox and note repository."""

from __future__ import annotations

import hashlib
import os
import uuid
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from threading import Lock
from pathlib import Path

import pytest

pytest.importorskip("alembic")
pytest.importorskip("sqlalchemy")

import sqlalchemy as sa
from alembic import command
from alembic.config import Config
from sqlalchemy.exc import IntegrityError

from airvoice.adapters.cloud_receiver import CloudReceiverV1Adapter
from airvoice.adapters.postgres import (
    _OUTBOX_NAMESPACE,
    PostgresDataStore,
)
from airvoice.receiver_v1 import V1Error
from airvoice.server.ports import ReceiptRecord, StoredUpload, UploadIdentity
from airvoice.server.object_keys import recording_object_key
from airvoice.server.schema import (
    note_events,
    outbox_events,
    processing_jobs,
    upload_receipts,
)
from airvoice.server.security import token_digest

pytestmark = pytest.mark.postgres
ROOT = Path(__file__).resolve().parents[1]


class MemoryUploadStore:
    def __init__(self) -> None:
        self.objects: dict[str, StoredUpload] = {}
        self.lock = Lock()

    def inspect(self, object_key: str) -> StoredUpload | None:
        with self.lock:
            return self.objects.get(object_key)

    def put_verified(
        self,
        *,
        object_key: str,
        source,
        length: int,
        sha256: str,
        content_type: str,
        metadata,
    ) -> StoredUpload:
        body = source.read(length)
        if len(body) != length:
            raise V1Error(400, "INCOMPLETE_BODY", "short body")
        actual = hashlib.sha256(body).hexdigest()
        if actual != sha256:
            raise V1Error(422, "HASH_MISMATCH", "bad hash")
        with self.lock:
            existing = self.objects.get(object_key)
            if existing is not None:
                return existing
            stored = StoredUpload(
                object_key=object_key,
                generation="1",
                size=length,
                sha256=sha256,
                content_type=content_type,
            )
            self.objects[object_key] = stored
            return stored


class FailOnceDataStore:
    def __init__(self, delegate: PostgresDataStore) -> None:
        self.delegate = delegate
        self.failed = False

    def find_receipt(self, identity: UploadIdentity) -> ReceiptRecord | None:
        return self.delegate.find_receipt(identity)

    def commit_upload(self, receipt: ReceiptRecord):
        if not self.failed:
            self.failed = True
            raise RuntimeError("injected DB failure")
        return self.delegate.commit_upload(receipt)


@pytest.fixture
def postgres_engine():
    database_url = os.environ.get("AIRVOICE_TEST_POSTGRES_URL", "").strip()
    if not database_url:
        pytest.skip("AIRVOICE_TEST_POSTGRES_URL is not configured")
    config = Config(str(ROOT / "alembic.ini"))
    config.set_main_option("sqlalchemy.url", database_url)
    command.downgrade(config, "base")
    command.upgrade(config, "head")
    engine = sa.create_engine(database_url, pool_size=20, max_overflow=20)
    try:
        yield engine
    finally:
        engine.dispose()


def _receipt(
    *,
    user_id: str = "user-a",
    upload_id: str | None = None,
    recording_id: str = "11111111-1111-4111-8111-111111111111",
    chunk_id: str = "22222222-2222-4222-8222-222222222222",
    filename: str = "recording.m4a",
    sha256: str = "a" * 64,
) -> ReceiptRecord:
    identity = upload_id or "33333333-3333-4333-8333-333333333333"
    return ReceiptRecord(
        identity=UploadIdentity(
            user_id=user_id,
            upload_id=identity,
            idempotency_key=identity,
            recording_id=recording_id,
            chunk_id=chunk_id,
            filename=filename,
        ),
        stored=StoredUpload(
            object_key=(
                f"users/{user_id}/recordings/{recording_id}/{chunk_id}/"
                f"{sha256}.m4a"
            ),
            generation="1",
            size=5,
            sha256=sha256,
            content_type="audio/mp4",
        ),
        status="stored",
        received_at="2026-07-24T00:00:00Z",
    )


def test_migration_upgrade_downgrade_upgrade_round_trip(postgres_engine) -> None:
    database_url = postgres_engine.url.render_as_string(hide_password=False)
    config = Config(str(ROOT / "alembic.ini"))
    config.set_main_option("sqlalchemy.url", database_url)
    command.downgrade(config, "base")
    assert not {
        "users",
        "upload_receipts",
        "notes",
        "outbox_events",
    }.intersection(sa.inspect(postgres_engine).get_table_names())
    command.upgrade(config, "head")

    inspector = sa.inspect(postgres_engine)
    assert set(inspector.get_table_names()) >= {
        "users",
        "api_tokens",
        "upload_receipts",
        "processing_jobs",
        "outbox_events",
        "notes",
        "note_events",
        "alembic_version",
    }


def test_parallel_upload_commits_one_receipt_job_and_outbox(
    postgres_engine,
) -> None:
    data = PostgresDataStore(postgres_engine)
    data.provision_user("user-a")
    receipt = _receipt()

    with ThreadPoolExecutor(max_workers=20) as executor:
        results = list(executor.map(lambda _: data.commit_upload(receipt), range(20)))

    assert sum(created for _, created in results) == 1
    with postgres_engine.connect() as connection:
        assert connection.scalar(sa.select(sa.func.count()).select_from(upload_receipts)) == 1
        assert connection.scalar(sa.select(sa.func.count()).select_from(processing_jobs)) == 1
        assert connection.scalar(sa.select(sa.func.count()).select_from(outbox_events)) == 1


def test_same_identity_with_different_hash_is_conflict(postgres_engine) -> None:
    data = PostgresDataStore(postgres_engine)
    data.provision_user("user-a")
    data.commit_upload(_receipt())

    with pytest.raises(V1Error) as raised:
        data.commit_upload(_receipt(sha256="b" * 64))

    assert raised.value.status == 409
    assert raised.value.code == "UPLOAD_CONFLICT"


def test_outbox_failure_rolls_back_receipt_and_job(postgres_engine) -> None:
    data = PostgresDataStore(postgres_engine)
    data.provision_user("user-a")
    receipt = _receipt()
    upload_id = uuid.UUID(receipt.identity.upload_id)
    event_id = uuid.uuid5(_OUTBOX_NAMESPACE, str(upload_id))
    with postgres_engine.begin() as connection:
        connection.execute(
            outbox_events.insert().values(
                event_id=event_id,
                event_key=f"upload.received:{upload_id}",
                aggregate_type="test",
                aggregate_id=upload_id,
                event_type="test.collision",
                payload={},
            )
        )

    with pytest.raises(IntegrityError):
        data.commit_upload(receipt)

    with postgres_engine.connect() as connection:
        assert connection.scalar(sa.select(sa.func.count()).select_from(upload_receipts)) == 0
        assert connection.scalar(sa.select(sa.func.count()).select_from(processing_jobs)) == 0
        assert connection.scalar(sa.select(sa.func.count()).select_from(outbox_events)) == 1


def test_concurrent_note_revision_has_one_winner(postgres_engine) -> None:
    data = PostgresDataStore(postgres_engine)
    data.provision_user("user-a")
    note = data.create_note(
        "user-a",
        folder="30-ideas",
        name="concurrent.md",
        content="initial",
    )
    etag = f'"{note["revision"]}"'

    def update(index: int):
        try:
            return data.update_note(
                "user-a",
                str(note["id"]),
                content=f"winner-{index}",
                if_match=etag,
            )
        except V1Error as exc:
            return exc

    with ThreadPoolExecutor(max_workers=20) as executor:
        outcomes = list(executor.map(update, range(20)))

    winners = [result for result in outcomes if isinstance(result, dict)]
    conflicts = [
        result
        for result in outcomes
        if isinstance(result, V1Error) and result.status == 412
    ]
    assert len(winners) == 1
    assert len(conflicts) == 19
    with postgres_engine.connect() as connection:
        assert connection.scalar(sa.select(sa.func.count()).select_from(note_events)) == 2


def test_archive_tombstone_allows_reusing_active_folder_name(postgres_engine) -> None:
    data = PostgresDataStore(postgres_engine)
    data.provision_user("user-a")
    original = data.create_note(
        "user-a",
        folder="30-ideas",
        name="reusable.md",
        content="first",
    )

    archived = data.archive_note(
        "user-a",
        str(original["id"]),
        if_match=f'"{original["revision"]}"',
    )
    replay = data.archive_note(
        "user-a",
        str(original["id"]),
        if_match=f'"{original["revision"]}"',
    )
    replacement = data.create_note(
        "user-a",
        folder="30-ideas",
        name="reusable.md",
        content="second",
    )

    assert archived["status"] == "archived"
    assert replay["archivedAt"] == archived["archivedAt"]
    assert replacement["id"] != original["id"]


def test_user_scope_and_token_digest_are_isolated(postgres_engine) -> None:
    data = PostgresDataStore(postgres_engine)
    data.provision_user("user-a")
    data.provision_user("user-b")
    note = data.create_note(
        "user-a",
        folder="30-ideas",
        name="private.md",
        content="private",
    )
    digest = token_digest("plain-secret", pepper=b"p" * 32)
    token_id = data.store_token_digest(
        user_id="user-a",
        digest=digest,
        version=1,
    )
    receipt = _receipt(user_id="user-a")
    data.commit_upload(receipt)
    user_b_identity = UploadIdentity(
        user_id="user-b",
        upload_id=receipt.identity.upload_id,
        idempotency_key=receipt.identity.idempotency_key,
        recording_id=receipt.identity.recording_id,
        chunk_id=receipt.identity.chunk_id,
        filename=receipt.identity.filename,
    )

    with pytest.raises(V1Error) as raised:
        data.get_note("user-b", str(note["id"]))

    assert raised.value.status == 404
    assert data.find_receipt(user_b_identity) is None
    assert data.object_is_referenced(
        receipt.stored.object_key,
        receipt.stored.generation,
    )
    assert not data.object_is_referenced(
        receipt.stored.object_key,
        "different-generation",
    )
    object_key_a = receipt.stored.object_key
    object_key_b = recording_object_key(
        user_id="user-b",
        recording_id=receipt.identity.recording_id,
        chunk_id=receipt.identity.chunk_id,
        sha256=receipt.stored.sha256,
        extension=".m4a",
    )
    assert object_key_a != object_key_b
    with postgres_engine.connect() as connection:
        stored = connection.scalar(sa.text("SELECT token_digest FROM api_tokens"))
    assert stored == digest
    assert stored != "plain-secret"
    assert data.resolve_token_digest(digest) == "user-a"
    assert data.resolve_token_digest("0" * 64) is None
    with postgres_engine.connect() as connection:
        last_used_at = connection.scalar(
            sa.text(
                "SELECT last_used_at FROM api_tokens WHERE token_digest = :digest"
            ),
            {"digest": digest},
        )
    assert last_used_at is not None
    assert data.revoke_token(str(token_id))
    assert not data.revoke_token(str(token_id))
    assert data.resolve_token_digest(digest) is None


def test_object_success_db_failure_retry_adopts_orphan(postgres_engine) -> None:
    data = PostgresDataStore(postgres_engine)
    data.provision_user("user-a")
    uploads = MemoryUploadStore()
    fail_once = FailOnceDataStore(data)
    adapter = CloudReceiverV1Adapter(uploads=uploads, data=fail_once)
    user = type("CloudUser", (), {"name": "user-a"})()
    body = b"audio"
    sha256 = hashlib.sha256(body).hexdigest()
    arguments = {
        "user": user,
        "filename": "recording.m4a",
        "length": len(body),
        "declared_sha256": sha256,
        "idempotency_key": "33333333-3333-4333-8333-333333333333",
        "recording_id": "11111111-1111-4111-8111-111111111111",
        "chunk_id": "22222222-2222-4222-8222-222222222222",
    }

    with pytest.raises(RuntimeError, match="injected DB failure"):
        adapter.receive_upload(source=BytesIO(body), **arguments)

    assert len(uploads.objects) == 1
    with postgres_engine.connect() as connection:
        assert connection.scalar(sa.select(sa.func.count()).select_from(upload_receipts)) == 0

    recovered = CloudReceiverV1Adapter(uploads=uploads, data=data)
    receipt, created = recovered.receive_upload(source=BytesIO(body), **arguments)

    assert created
    assert receipt.record.stored.sha256 == sha256
    assert len(uploads.objects) == 1
    with postgres_engine.connect() as connection:
        assert connection.scalar(sa.select(sa.func.count()).select_from(upload_receipts)) == 1
        assert connection.scalar(sa.select(sa.func.count()).select_from(outbox_events)) == 1


def test_outbox_failure_and_publish_state_are_durable(postgres_engine) -> None:
    data = PostgresDataStore(postgres_engine)
    data.provision_user("user-a")
    data.commit_upload(_receipt())
    event = data.list_unpublished_outbox().pop()

    data.mark_outbox_failure(event.event_id, "temporary task outage")
    retry = data.list_unpublished_outbox().pop()
    assert retry.event_id == event.event_id

    data.mark_outbox_published(event.event_id)
    data.mark_outbox_published(event.event_id)
    assert data.list_unpublished_outbox() == []
    with postgres_engine.connect() as connection:
        row = connection.execute(
            sa.select(
                outbox_events.c.publish_attempt,
                outbox_events.c.published_at,
                outbox_events.c.last_error,
            )
        ).one()
    assert row.publish_attempt == 2
    assert row.published_at is not None
    assert row.last_error is None


def test_parallel_cloud_upload_has_one_object_receipt_job_and_outbox(
    postgres_engine,
) -> None:
    data = PostgresDataStore(postgres_engine)
    data.provision_user("user-a")
    uploads = MemoryUploadStore()
    adapter = CloudReceiverV1Adapter(uploads=uploads, data=data)
    user = type("CloudUser", (), {"name": "user-a"})()
    body = b"audio"
    arguments = {
        "user": user,
        "filename": "parallel.m4a",
        "length": len(body),
        "declared_sha256": hashlib.sha256(body).hexdigest(),
        "idempotency_key": "77777777-7777-4777-8777-777777777777",
        "recording_id": "88888888-8888-4888-8888-888888888888",
        "chunk_id": "99999999-9999-4999-8999-999999999999",
    }

    with ThreadPoolExecutor(max_workers=20) as executor:
        outcomes = list(
            executor.map(
                lambda _: adapter.receive_upload(
                    source=BytesIO(body),
                    **arguments,
                ),
                range(20),
            )
        )

    assert sum(created for _, created in outcomes) == 1
    assert len(uploads.objects) == 1
    with postgres_engine.connect() as connection:
        assert connection.scalar(sa.select(sa.func.count()).select_from(upload_receipts)) == 1
        assert connection.scalar(sa.select(sa.func.count()).select_from(processing_jobs)) == 1
        assert connection.scalar(sa.select(sa.func.count()).select_from(outbox_events)) == 1
