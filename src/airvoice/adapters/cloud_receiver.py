"""Cloud persistence implementation for the existing Receiver V1 handler."""

from __future__ import annotations

import datetime as dt
import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO

from airvoice.adapters.local_receiver import LocalReceiverV1Adapter
from airvoice.adapters.postgres import PostgresDataStore
from airvoice.receiver_v1 import V1Error
from airvoice.server.object_keys import (
    content_type_for_filename,
    recording_object_key,
)
from airvoice.server.ports import (
    ReceiptRecord,
    UploadIdentity,
    UploadStore,
)
from airvoice.server.upload_domain import UploadFingerprint

_STREAM_CHUNK_SIZE = 256 * 1024


def _utc_now() -> str:
    return dt.datetime.now(dt.UTC).isoformat().replace("+00:00", "Z")


@dataclass(frozen=True)
class CloudUploadReceipt:
    record: ReceiptRecord

    def as_dict(self) -> dict[str, object]:
        identity = self.record.identity
        stored = self.record.stored
        return {
            "uploadId": identity.upload_id,
            "idempotencyKey": identity.idempotency_key,
            "recordingId": identity.recording_id,
            "chunkId": identity.chunk_id,
            "filename": identity.filename,
            "size": stored.size,
            "sha256": stored.sha256,
            "status": self.record.status,
            "receivedAt": self.record.received_at,
        }


class CloudReceiverV1Adapter:
    """Compose object storage and PostgreSQL behind the V1 compatibility port."""

    def __init__(
        self,
        *,
        uploads: UploadStore,
        data: PostgresDataStore,
    ) -> None:
        self.uploads = uploads
        self.data = data

    @staticmethod
    def ensure_upload_capacity(user: Any, length: int) -> None:
        # Cloud quota/capacity failures map from the backing SDK, not local disk.
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
        source: BinaryIO,
    ) -> tuple[CloudUploadReceipt, bool]:
        user_id = str(user.name)
        if not user_id:
            raise V1Error(
                500,
                "CLOUD_USER_ID_REQUIRED",
                "Cloud users require a stable non-empty ID",
            )
        if declared_sha256 is None:
            raise V1Error(
                400,
                "CONTENT_SHA256_REQUIRED",
                "X-Content-SHA256 is required",
            )
        extension, content_type = content_type_for_filename(filename)
        identity = UploadIdentity(
            user_id=user_id,
            upload_id=idempotency_key,
            idempotency_key=idempotency_key,
            recording_id=recording_id,
            chunk_id=chunk_id,
            filename=filename,
        )
        existing = self.data.find_receipt(identity)
        if existing is not None:
            actual_hash = self._consume_and_hash(source, length)
            if actual_hash != declared_sha256:
                raise V1Error(
                    422,
                    "HASH_MISMATCH",
                    "Uploaded content does not match SHA-256",
                )
            self._ensure_same_receipt(existing, identity, length, actual_hash)
            return CloudUploadReceipt(existing), False

        object_key = recording_object_key(
            user_id=user_id,
            recording_id=recording_id,
            chunk_id=chunk_id,
            sha256=declared_sha256,
            extension=extension,
        )
        stored = self.uploads.put_verified(
            object_key=object_key,
            source=source,
            length=length,
            sha256=declared_sha256,
            content_type=content_type,
            metadata={
                "userId": user_id,
                "uploadId": idempotency_key,
                "recordingId": recording_id,
                "chunkId": chunk_id,
                "sha256": declared_sha256,
            },
        )
        cancellation_check = getattr(source, "raise_if_cancelled", None)
        if cancellation_check is not None:
            cancellation_check()
        candidate = ReceiptRecord(
            identity=identity,
            stored=stored,
            status="stored",
            received_at=_utc_now(),
        )
        committed, created = self.data.commit_upload(candidate)
        return CloudUploadReceipt(committed), created

    def list_notes(
        self, user: Any, collected: list[tuple[str, Path]]
    ) -> list[dict[str, object]]:
        return self.data.list_notes(str(user.name))

    def get_note(self, user: Any, note_id: str) -> dict[str, object]:
        return self.data.get_note(str(user.name), note_id)

    def create_note(
        self,
        user: Any,
        *,
        folder: str,
        name: str,
        content: str,
    ) -> dict[str, object]:
        return self.data.create_note(
            str(user.name),
            folder=folder,
            name=name,
            content=content,
        )

    def update_note(
        self,
        user: Any,
        note_id: str,
        *,
        content: str,
        if_match: str | None,
    ) -> dict[str, object]:
        return self.data.update_note(
            str(user.name),
            note_id,
            content=content,
            if_match=if_match,
        )

    def archive_note(
        self,
        user: Any,
        note_id: str,
        *,
        if_match: str | None,
        archive_dir: str,
    ) -> dict[str, object]:
        return self.data.archive_note(
            str(user.name),
            note_id,
            if_match=if_match,
        )

    @staticmethod
    def apk_info(apk: Path) -> dict[str, object]:
        return LocalReceiverV1Adapter.apk_info(apk)

    @staticmethod
    def _consume_and_hash(source: BinaryIO, length: int) -> str:
        remaining = length
        digest = hashlib.sha256()
        while remaining:
            chunk = source.read(min(_STREAM_CHUNK_SIZE, remaining))
            if not chunk:
                raise V1Error(
                    400,
                    "INCOMPLETE_BODY",
                    "Upload ended before Content-Length",
                )
            digest.update(chunk)
            remaining -= len(chunk)
        return digest.hexdigest()

    @staticmethod
    def _ensure_same_receipt(
        existing: ReceiptRecord,
        identity: UploadIdentity,
        length: int,
        sha256: str,
    ) -> None:
        actual = UploadFingerprint(
            recording_id=existing.identity.recording_id,
            chunk_id=existing.identity.chunk_id,
            filename=existing.identity.filename,
            size=existing.stored.size,
            sha256=existing.stored.sha256,
        )
        expected = UploadFingerprint(
            recording_id=identity.recording_id,
            chunk_id=identity.chunk_id,
            filename=identity.filename,
            size=length,
            sha256=sha256,
        )
        if actual != expected:
            raise V1Error(
                409,
                "UPLOAD_CONFLICT",
                "Upload identity belongs to different content",
            )
