"""Pure upload identity and matching-orphan decisions."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


class ReceiptFields(Protocol):
    """Minimal row shape needed for an upload replay decision."""

    def __getitem__(self, key: str) -> object:
        """Return one persisted receipt field."""


@dataclass(frozen=True)
class UploadFingerprint:
    """Content and identity fields that must agree on every replay."""

    recording_id: str
    chunk_id: str
    filename: str
    size: int
    sha256: str


def receipt_matches(row: ReceiptFields, upload: UploadFingerprint) -> bool:
    """Return whether an existing receipt represents the same upload."""
    return (
        row["recording_id"] == upload.recording_id
        and row["chunk_id"] == upload.chunk_id
        and row["filename"] == upload.filename
        and row["size"] == upload.size
        and row["sha256"] == upload.sha256
    )


def orphan_matches(
    *, existing_size: int, existing_sha256: str, upload: UploadFingerprint
) -> bool:
    """Return whether an object without a receipt may be safely adopted."""
    return existing_size == upload.size and existing_sha256 == upload.sha256
