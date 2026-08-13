"""PostgreSQL receipt/outbox and note repositories."""

from __future__ import annotations

import hashlib
import uuid
from datetime import UTC, datetime
from pathlib import Path

import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.engine import Engine, RowMapping
from sqlalchemy.exc import IntegrityError

from airvoice.receiver_v1 import V1Error, normalize_etag
from airvoice.server.ports import (
    OutboxRecord,
    ReceiptRecord,
    StoredUpload,
    UploadIdentity,
)
from airvoice.server.schema import (
    api_tokens,
    note_events,
    notes,
    outbox_events,
    processing_jobs,
    upload_receipts,
    users,
)
from airvoice.server.upload_domain import UploadFingerprint

_JOB_NAMESPACE = uuid.UUID("6ca9d88e-f493-40cc-8334-8d0c790fa430")
_OUTBOX_NAMESPACE = uuid.UUID("9ae0ee7b-8dc6-40fa-8ec4-d76239225e90")


def _as_utc(value: datetime) -> str:
    normalized = value if value.tzinfo is not None else value.replace(tzinfo=UTC)
    return normalized.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _parse_utc(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def _revision(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


class PostgresDataStore:
    """Data access with transaction boundaries required by cloud ingestion."""

    def __init__(self, engine: Engine) -> None:
        self.engine = engine

    def provision_user(self, user_id: str) -> None:
        statement = (
            pg_insert(users)
            .values(user_id=user_id, status="active")
            .on_conflict_do_nothing(index_elements=[users.c.user_id])
        )
        with self.engine.begin() as connection:
            connection.execute(statement)

    def store_token_digest(
        self,
        *,
        user_id: str,
        digest: str,
        version: int,
        expires_at: datetime | None = None,
    ) -> uuid.UUID:
        token_id = uuid.uuid4()
        with self.engine.begin() as connection:
            connection.execute(
                api_tokens.insert().values(
                    token_id=token_id,
                    user_id=user_id,
                    token_digest=digest,
                    token_version=version,
                    expires_at=expires_at,
                )
            )
        return token_id

    def resolve_token_digest(self, digest: str) -> str | None:
        """Resolve one active bearer digest and record its last use."""
        now = datetime.now(UTC)
        statement = (
            api_tokens.update()
            .where(
                api_tokens.c.token_digest == digest,
                api_tokens.c.revoked_at.is_(None),
                sa.or_(
                    api_tokens.c.expires_at.is_(None),
                    api_tokens.c.expires_at > now,
                ),
                users.c.status == "active",
                users.c.user_id == api_tokens.c.user_id,
            )
            .values(last_used_at=now)
            .returning(api_tokens.c.user_id)
        )
        with self.engine.begin() as connection:
            user_id = connection.scalar(statement)
        return str(user_id) if user_id is not None else None

    def revoke_token(self, token_id: str) -> bool:
        """Revoke one token once; plaintext bearer material is never needed."""
        try:
            identifier = uuid.UUID(token_id)
        except ValueError as exc:
            raise ValueError("token_id must be a UUID") from exc
        with self.engine.begin() as connection:
            result = connection.execute(
                api_tokens.update()
                .where(
                    api_tokens.c.token_id == identifier,
                    api_tokens.c.revoked_at.is_(None),
                )
                .values(revoked_at=sa.func.now())
            )
        return result.rowcount == 1

    def ping(self) -> None:
        """Verify that the configured database can serve a checkout."""
        with self.engine.connect() as connection:
            connection.execute(sa.select(sa.literal(1))).scalar_one()

    def find_receipt(self, identity: UploadIdentity) -> ReceiptRecord | None:
        with self.engine.connect() as connection:
            row = connection.execute(self._receipt_query(identity)).mappings().first()
        return self._receipt(row) if row is not None else None

    def object_is_referenced(self, object_key: str, generation: str) -> bool:
        with self.engine.connect() as connection:
            return (
                connection.scalar(
                    sa.select(sa.literal(True)).where(
                        sa.exists().where(
                            upload_receipts.c.object_key == object_key,
                            upload_receipts.c.object_generation == generation,
                        )
                    )
                )
                is True
            )

    def commit_upload(self, receipt: ReceiptRecord) -> tuple[ReceiptRecord, bool]:
        """Commit receipt, processing job, and outbox event atomically."""
        try:
            with self.engine.begin() as connection:
                existing = connection.execute(
                    self._receipt_query(receipt.identity)
                ).mappings().first()
                if existing is not None:
                    return self._resolve_existing(existing, receipt), False
                self._insert_upload_transaction(connection, receipt)
            return receipt, True
        except IntegrityError:
            existing = self.find_receipt(receipt.identity)
            if existing is None:
                raise
            return self._resolve_existing_record(existing, receipt), False

    @staticmethod
    def _receipt_query(identity: UploadIdentity) -> sa.Select:
        return sa.select(upload_receipts).where(
            upload_receipts.c.user_id == identity.user_id,
            sa.or_(
                upload_receipts.c.upload_id == uuid.UUID(identity.upload_id),
                upload_receipts.c.idempotency_key
                == uuid.UUID(identity.idempotency_key),
                upload_receipts.c.filename == identity.filename,
                sa.and_(
                    upload_receipts.c.recording_id
                    == uuid.UUID(identity.recording_id),
                    upload_receipts.c.chunk_id == uuid.UUID(identity.chunk_id),
                ),
            ),
        )

    @staticmethod
    def _insert_upload_transaction(connection, receipt: ReceiptRecord) -> None:  # noqa: ANN001
        identity = receipt.identity
        stored = receipt.stored
        upload_id = uuid.UUID(identity.upload_id)
        extension = Path(identity.filename).suffix.lower()
        connection.execute(
            upload_receipts.insert().values(
                upload_id=upload_id,
                user_id=identity.user_id,
                idempotency_key=uuid.UUID(identity.idempotency_key),
                recording_id=uuid.UUID(identity.recording_id),
                chunk_id=uuid.UUID(identity.chunk_id),
                filename=identity.filename,
                extension=extension,
                content_type=stored.content_type,
                size=stored.size,
                sha256=stored.sha256,
                object_key=stored.object_key,
                object_generation=stored.generation,
                status=receipt.status,
                received_at=_parse_utc(receipt.received_at),
            )
        )
        job_id = uuid.uuid5(_JOB_NAMESPACE, str(upload_id))
        connection.execute(
            processing_jobs.insert().values(
                job_id=job_id,
                receipt_id=upload_id,
                status="queued",
            )
        )
        event_id = uuid.uuid5(_OUTBOX_NAMESPACE, str(upload_id))
        connection.execute(
            outbox_events.insert().values(
                event_id=event_id,
                event_key=f"upload.received:{upload_id}",
                aggregate_type="upload_receipt",
                aggregate_id=upload_id,
                event_type="upload.received",
                payload={
                    "uploadId": str(upload_id),
                    "userId": identity.user_id,
                    "objectKey": stored.object_key,
                    "generation": stored.generation,
                },
            )
        )

    @staticmethod
    def _resolve_existing(
        row: RowMapping, candidate: ReceiptRecord
    ) -> ReceiptRecord:
        existing = PostgresDataStore._receipt(row)
        return PostgresDataStore._resolve_existing_record(existing, candidate)

    @staticmethod
    def _resolve_existing_record(
        existing: ReceiptRecord, candidate: ReceiptRecord
    ) -> ReceiptRecord:
        expected = UploadFingerprint(
            recording_id=candidate.identity.recording_id,
            chunk_id=candidate.identity.chunk_id,
            filename=candidate.identity.filename,
            size=candidate.stored.size,
            sha256=candidate.stored.sha256,
        )
        actual = UploadFingerprint(
            recording_id=existing.identity.recording_id,
            chunk_id=existing.identity.chunk_id,
            filename=existing.identity.filename,
            size=existing.stored.size,
            sha256=existing.stored.sha256,
        )
        if actual != expected:
            raise V1Error(
                409,
                "UPLOAD_CONFLICT",
                "Upload identity belongs to different content",
            )
        return existing

    @staticmethod
    def _receipt(row: RowMapping) -> ReceiptRecord:
        return ReceiptRecord(
            identity=UploadIdentity(
                user_id=str(row["user_id"]),
                upload_id=str(row["upload_id"]),
                idempotency_key=str(row["idempotency_key"]),
                recording_id=str(row["recording_id"]),
                chunk_id=str(row["chunk_id"]),
                filename=str(row["filename"]),
            ),
            stored=StoredUpload(
                object_key=str(row["object_key"]),
                generation=str(row["object_generation"]),
                size=int(row["size"]),
                sha256=str(row["sha256"]),
                content_type=str(row["content_type"]),
            ),
            status=str(row["status"]),
            received_at=_as_utc(row["received_at"]),
        )

    def list_notes(self, user_id: str) -> list[dict[str, object]]:
        statement = (
            sa.select(notes)
            .where(notes.c.user_id == user_id, notes.c.archived_at.is_(None))
            .order_by(notes.c.updated_at.desc(), notes.c.note_id)
        )
        with self.engine.connect() as connection:
            rows = connection.execute(statement).mappings().all()
        return [self._note(row) for row in rows]

    def list_unpublished_outbox(self, *, limit: int = 100) -> list[OutboxRecord]:
        if limit < 1 or limit > 1000:
            raise ValueError("outbox limit must be between 1 and 1000")
        statement = (
            sa.select(outbox_events)
            .where(outbox_events.c.published_at.is_(None))
            .order_by(outbox_events.c.created_at, outbox_events.c.event_id)
            .limit(limit)
        )
        with self.engine.connect() as connection:
            rows = connection.execute(statement).mappings().all()
        return [
            OutboxRecord(
                event_id=str(row["event_id"]),
                event_key=str(row["event_key"]),
                event_type=str(row["event_type"]),
                payload=dict(row["payload"]),
            )
            for row in rows
        ]

    def mark_outbox_published(self, event_id: str) -> None:
        with self.engine.begin() as connection:
            result = connection.execute(
                outbox_events.update()
                .where(
                    outbox_events.c.event_id == uuid.UUID(event_id),
                    outbox_events.c.published_at.is_(None),
                )
                .values(
                    published_at=sa.func.now(),
                    publish_attempt=outbox_events.c.publish_attempt + 1,
                    last_error=None,
                )
            )
        if result.rowcount == 0:
            existing = self._outbox_exists(event_id)
            if not existing:
                raise KeyError(event_id)

    def mark_outbox_failure(self, event_id: str, error: str) -> None:
        with self.engine.begin() as connection:
            result = connection.execute(
                outbox_events.update()
                .where(
                    outbox_events.c.event_id == uuid.UUID(event_id),
                    outbox_events.c.published_at.is_(None),
                )
                .values(
                    publish_attempt=outbox_events.c.publish_attempt + 1,
                    last_error=error[:512],
                )
            )
        if result.rowcount == 0 and not self._outbox_exists(event_id):
            raise KeyError(event_id)

    def _outbox_exists(self, event_id: str) -> bool:
        with self.engine.connect() as connection:
            return (
                connection.scalar(
                    sa.select(sa.literal(True)).where(
                        sa.exists().where(
                            outbox_events.c.event_id == uuid.UUID(event_id)
                        )
                    )
                )
                is True
            )

    def get_note(self, user_id: str, note_id: str) -> dict[str, object]:
        with self.engine.connect() as connection:
            row = connection.execute(
                sa.select(notes).where(
                    notes.c.user_id == user_id,
                    notes.c.note_id == uuid.UUID(note_id),
                    notes.c.archived_at.is_(None),
                )
            ).mappings().first()
        if row is None:
            raise V1Error(404, "NOTE_NOT_FOUND", "Note does not exist")
        return self._note(row)

    def create_note(
        self,
        user_id: str,
        *,
        folder: str,
        name: str,
        content: str,
    ) -> dict[str, object]:
        note_id = uuid.uuid4()
        revision = _revision(content)
        try:
            with self.engine.begin() as connection:
                row = connection.execute(
                    notes.insert()
                    .values(
                        note_id=note_id,
                        user_id=user_id,
                        folder=folder,
                        name=name,
                        content=content,
                        revision=revision,
                    )
                    .returning(notes)
                ).mappings().one()
                self._insert_note_event(connection, row, "created")
        except IntegrityError as exc:
            raise V1Error(
                409,
                "NOTE_CONFLICT",
                "A note with this name exists",
            ) from exc
        return self._note(row)

    def update_note(
        self,
        user_id: str,
        note_id: str,
        *,
        content: str,
        if_match: str | None,
    ) -> dict[str, object]:
        expected = normalize_etag(if_match)
        if expected is None:
            raise V1Error(428, "PRECONDITION_REQUIRED", "If-Match is required")
        identifier = uuid.UUID(note_id)
        conditions = [
            notes.c.user_id == user_id,
            notes.c.note_id == identifier,
            notes.c.archived_at.is_(None),
        ]
        if expected != "*":
            conditions.append(notes.c.revision == expected)
        statement = (
            notes.update()
            .where(*conditions)
            .values(
                content=content,
                revision=_revision(content),
                updated_at=sa.func.now(),
            )
            .returning(notes)
        )
        with self.engine.begin() as connection:
            row = connection.execute(statement).mappings().first()
            if row is None:
                self._raise_note_write_failure(connection, user_id, identifier)
            assert row is not None
            self._insert_note_event(connection, row, "updated")
        return self._note(row)

    def archive_note(
        self,
        user_id: str,
        note_id: str,
        *,
        if_match: str | None,
    ) -> dict[str, object]:
        expected = normalize_etag(if_match)
        if expected is None:
            raise V1Error(428, "PRECONDITION_REQUIRED", "If-Match is required")
        identifier = uuid.UUID(note_id)
        conditions = [
            notes.c.user_id == user_id,
            notes.c.note_id == identifier,
            notes.c.archived_at.is_(None),
        ]
        if expected != "*":
            conditions.append(notes.c.revision == expected)
        statement = (
            notes.update()
            .where(*conditions)
            .values(archived_at=sa.func.now(), updated_at=sa.func.now())
            .returning(notes)
        )
        with self.engine.begin() as connection:
            row = connection.execute(statement).mappings().first()
            if row is None:
                existing = connection.execute(
                    sa.select(notes).where(
                        notes.c.user_id == user_id,
                        notes.c.note_id == identifier,
                    )
                ).mappings().first()
                if existing is None:
                    raise V1Error(404, "NOTE_NOT_FOUND", "Note does not exist")
                if existing["archived_at"] is None:
                    raise V1Error(
                        412,
                        "REVISION_CONFLICT",
                        "Note changed since it was downloaded",
                    )
                return self._archived(existing)
            self._insert_note_event(connection, row, "archived")
        return self._archived(row)

    @staticmethod
    def _raise_note_write_failure(
        connection,
        user_id: str,
        note_id: uuid.UUID,
    ) -> None:  # noqa: ANN001
        existing = connection.execute(
            sa.select(notes.c.note_id).where(
                notes.c.user_id == user_id,
                notes.c.note_id == note_id,
                notes.c.archived_at.is_(None),
            )
        ).first()
        if existing is None:
            raise V1Error(404, "NOTE_NOT_FOUND", "Note does not exist")
        raise V1Error(
            412,
            "REVISION_CONFLICT",
            "Note changed since it was downloaded",
        )

    @staticmethod
    def _insert_note_event(
        connection, row: RowMapping, event_type: str  # noqa: ANN001
    ) -> None:
        connection.execute(
            note_events.insert().values(
                event_id=uuid.uuid4(),
                note_id=row["note_id"],
                user_id=row["user_id"],
                event_type=event_type,
                revision=row["revision"],
            )
        )

    @staticmethod
    def _note(row: RowMapping) -> dict[str, object]:
        return {
            "id": str(row["note_id"]),
            "folder": str(row["folder"]),
            "name": str(row["name"]),
            "content": str(row["content"]),
            "revision": str(row["revision"]),
            "updatedAt": _as_utc(row["updated_at"]),
        }

    @staticmethod
    def _archived(row: RowMapping) -> dict[str, object]:
        return {
            "id": str(row["note_id"]),
            "archived": True,
            "status": "archived",
            "archivedAt": _as_utc(row["archived_at"]),
        }
