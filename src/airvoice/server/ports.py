"""Persistence boundaries for Receiver V1.

The narrow ``ReceiverV1Persistence`` protocol is the compatibility boundary
used by the current HTTP handler.  The four smaller ports describe the cloud
implementation seams and keep object storage, SQL metadata, notes, and job
dispatch independently replaceable.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO, Mapping, Protocol, runtime_checkable


@dataclass(frozen=True)
class StoredUpload:
    """Immutable object metadata returned after a verified upload."""

    object_key: str
    generation: str
    size: int
    sha256: str
    content_type: str


@dataclass(frozen=True)
class UploadIdentity:
    """User-scoped identity used for idempotency and conflict checks."""

    user_id: str
    upload_id: str
    idempotency_key: str
    recording_id: str
    chunk_id: str
    filename: str


@dataclass(frozen=True)
class ReceiptRecord:
    """Persistence-neutral upload receipt representation."""

    identity: UploadIdentity
    stored: StoredUpload
    status: str
    received_at: str


@dataclass(frozen=True)
class OutboxRecord:
    """A durable event awaiting deterministic external publication."""

    event_id: str
    event_key: str
    event_type: str
    payload: Mapping[str, object]


class UploadStore(Protocol):
    """Immutable binary object storage."""

    def put_verified(
        self,
        *,
        object_key: str,
        source: BinaryIO,
        length: int,
        sha256: str,
        content_type: str,
        metadata: Mapping[str, str],
    ) -> StoredUpload:
        """Store one object without overwriting an existing generation."""

    def inspect(self, object_key: str) -> StoredUpload | None:
        """Return immutable object metadata when the key exists."""


class ReceiptRepository(Protocol):
    """Transactional upload identity and receipt ledger."""

    def find(self, identity: UploadIdentity) -> ReceiptRecord | None:
        """Find a receipt by any unique user-scoped upload identity."""

    def create(self, receipt: ReceiptRecord) -> ReceiptRecord:
        """Insert a receipt or raise a domain conflict."""


class NoteRepository(Protocol):
    """Stable note IDs, revisions, and archive tombstones."""

    def list(self, user_id: str) -> list[dict[str, object]]:
        """List active notes exposed to the mobile client."""

    def get(self, user_id: str, note_id: str) -> dict[str, object]:
        """Read one active note."""

    def create(
        self, user_id: str, *, folder: str, name: str, content: str
    ) -> dict[str, object]:
        """Create one note with a stable ID and content revision."""

    def update(
        self, user_id: str, note_id: str, *, content: str, if_match: str | None
    ) -> dict[str, object]:
        """Update one note only when its revision matches."""

    def archive(
        self, user_id: str, note_id: str, *, if_match: str | None
    ) -> dict[str, object]:
        """Archive one note and preserve its tombstone."""


class JobOutbox(Protocol):
    """Transactional processing-job publication boundary."""

    def enqueue_upload(self, receipt: ReceiptRecord) -> str:
        """Record a deterministic processing event and return its event ID."""


class UploadReceiptView(Protocol):
    """Receipt shape required by the HTTP response mapper."""

    def as_dict(self) -> dict[str, object]:
        """Return the V1 wire-compatible receipt fields."""


@runtime_checkable
class ReceiverV1Persistence(Protocol):
    """Aggregate compatibility port consumed by the existing HTTP handler."""

    def ensure_upload_capacity(self, user: Any, length: int) -> None:
        """Raise a domain error when the backing upload store has no capacity."""

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
    ) -> tuple[UploadReceiptView, bool]:
        """Verify and commit an upload, returning ``(receipt, created)``."""

    def list_notes(
        self, user: Any, collected: list[tuple[str, Path]]
    ) -> list[dict[str, object]]:
        """Return notes visible to the mobile client."""

    def get_note(self, user: Any, note_id: str) -> dict[str, object]:
        """Return one note."""

    def create_note(
        self, user: Any, *, folder: str, name: str, content: str
    ) -> dict[str, object]:
        """Create one note."""

    def update_note(
        self,
        user: Any,
        note_id: str,
        *,
        content: str,
        if_match: str | None,
    ) -> dict[str, object]:
        """Update one note using optimistic concurrency."""

    def archive_note(
        self,
        user: Any,
        note_id: str,
        *,
        if_match: str | None,
        archive_dir: str,
    ) -> dict[str, object]:
        """Archive one note."""

    def apk_info(self, apk: Path) -> dict[str, object]:
        """Return structured APK metadata."""
