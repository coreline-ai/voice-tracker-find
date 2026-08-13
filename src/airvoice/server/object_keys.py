"""Deterministic Cloud Storage keys and upload media validation."""

from __future__ import annotations

import re
import uuid
from pathlib import Path
from urllib.parse import quote

from airvoice.server.contracts import UPLOAD_MEDIA_TYPES

_SHA256 = re.compile(r"^[0-9a-f]{64}$")


def canonical_uuid(value: str, *, field: str) -> str:
    try:
        return str(uuid.UUID(value))
    except ValueError as exc:
        raise ValueError(f"{field} must be a UUID") from exc


def content_type_for_filename(filename: str) -> tuple[str, str]:
    """Return ``(extension, media_type)`` for an allowed recording filename."""
    extension = Path(filename).suffix.lower()
    content_type = UPLOAD_MEDIA_TYPES.get(extension)
    if content_type is None:
        raise ValueError("unsupported audio extension")
    return extension, content_type


def recording_object_key(
    *,
    user_id: str,
    recording_id: str,
    chunk_id: str,
    sha256: str,
    extension: str,
) -> str:
    """Build a user-scoped immutable key without embedding a client filename."""
    normalized_hash = sha256.lower()
    if not _SHA256.fullmatch(normalized_hash):
        raise ValueError("sha256 must be 64 lowercase hexadecimal characters")
    normalized_extension = extension.lower()
    if normalized_extension not in UPLOAD_MEDIA_TYPES:
        raise ValueError("unsupported audio extension")
    if not user_id or len(user_id) > 128:
        raise ValueError("user_id must contain 1..128 characters")
    user_component = quote(user_id, safe="-._~")
    recording = canonical_uuid(recording_id, field="recording_id")
    chunk = canonical_uuid(chunk_id, field="chunk_id")
    return (
        f"users/{user_component}/recordings/{recording}/{chunk}/"
        f"{normalized_hash}{normalized_extension}"
    )
