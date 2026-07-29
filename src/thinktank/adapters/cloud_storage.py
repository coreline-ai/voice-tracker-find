"""Google Cloud Storage adapter for immutable verified recording objects."""

from __future__ import annotations

import hashlib
import io
import uuid
import datetime as dt
from contextlib import suppress
from typing import Any, BinaryIO, Mapping

from google.api_core.exceptions import NotFound, PreconditionFailed

from thinktank.receiver_v1 import V1Error
from thinktank.server.orphan_sweeper import ObjectCandidate
from thinktank.server.ports import StoredUpload

UPLOAD_BUFFER_SIZE = 256 * 1024


class _HashingReader(io.RawIOBase):
    def __init__(self, source: BinaryIO) -> None:
        self.source = source
        self.digest = hashlib.sha256()
        self.bytes_read = 0

    def readable(self) -> bool:
        return True

    def read(self, size: int = -1) -> bytes:
        chunk = self.source.read(size)
        if chunk:
            self.digest.update(chunk)
            self.bytes_read += len(chunk)
        return chunk

    def tell(self) -> int:
        return self.bytes_read

    @property
    def sha256(self) -> str:
        return self.digest.hexdigest()


class GcsUploadStore:
    """Upload through a staging object, then atomically promote by generation."""

    def __init__(self, bucket: Any) -> None:
        self.bucket = bucket

    def inspect(self, object_key: str) -> StoredUpload | None:
        blob = self.bucket.get_blob(object_key)
        if blob is None:
            return None
        return self._stored(blob)

    def iter_candidates(self, prefix: str) -> list[ObjectCandidate]:
        candidates = []
        for blob in self.bucket.list_blobs(prefix=prefix):
            if blob.generation is None or blob.updated is None:
                continue
            updated = blob.updated
            if updated.tzinfo is None:
                updated = updated.replace(tzinfo=dt.UTC)
            candidates.append(
                ObjectCandidate(
                    object_key=str(blob.name),
                    generation=str(blob.generation),
                    updated_at=updated,
                )
            )
        return candidates

    def delete_if_generation(self, object_key: str, generation: str) -> bool:
        blob = self.bucket.blob(object_key, generation=int(generation))
        try:
            blob.delete(if_generation_match=int(generation))
        except (NotFound, PreconditionFailed):
            return False
        return True

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
        existing = self.inspect(object_key)
        if existing is not None:
            self._consume_verified(source, length=length, sha256=sha256)
            return self._accept_existing(
                existing,
                length=length,
                sha256=sha256,
                content_type=content_type,
            )

        stage_key = f".staging/{uuid.uuid4()}"
        stage = self.bucket.blob(stage_key, chunk_size=UPLOAD_BUFFER_SIZE)
        stage.metadata = {**metadata, "sha256": sha256}
        reader = _HashingReader(source)
        stage_generation: int | str | None = None
        try:
            stage.upload_from_file(
                reader,
                rewind=False,
                size=length,
                content_type=content_type,
                if_generation_match=0,
                checksum="auto",
            )
            stage_generation = stage.generation
            cancellation_check = getattr(source, "raise_if_cancelled", None)
            if cancellation_check is not None:
                cancellation_check()
            if reader.bytes_read != length:
                raise V1Error(
                    400,
                    "INCOMPLETE_BODY",
                    "Upload ended before Content-Length",
                )
            if reader.sha256 != sha256:
                raise V1Error(
                    422,
                    "HASH_MISMATCH",
                    "Uploaded content does not match SHA-256",
                )
            try:
                promoted = self.bucket.copy_blob(
                    stage,
                    self.bucket,
                    object_key,
                    source_generation=stage_generation,
                    if_generation_match=0,
                )
            except PreconditionFailed:
                concurrent = self.inspect(object_key)
                if concurrent is None:
                    raise
                return self._accept_existing(
                    concurrent,
                    length=length,
                    sha256=sha256,
                    content_type=content_type,
                )
            return self._stored(promoted)
        finally:
            if stage_generation is not None:
                with suppress(NotFound, PreconditionFailed):
                    stage.delete(if_generation_match=stage_generation)

    @staticmethod
    def _stored(blob: Any) -> StoredUpload:
        metadata = blob.metadata or {}
        sha256 = metadata.get("sha256")
        if not sha256 or blob.generation is None or blob.size is None:
            raise V1Error(
                500,
                "INVALID_OBJECT_METADATA",
                "Stored object metadata is incomplete",
            )
        return StoredUpload(
            object_key=str(blob.name),
            generation=str(blob.generation),
            size=int(blob.size),
            sha256=str(sha256),
            content_type=str(blob.content_type or "application/octet-stream"),
        )

    @staticmethod
    def _accept_existing(
        existing: StoredUpload,
        *,
        length: int,
        sha256: str,
        content_type: str,
    ) -> StoredUpload:
        if (
            existing.size != length
            or existing.sha256 != sha256
            or existing.content_type != content_type
        ):
            raise V1Error(
                409,
                "UPLOAD_CONFLICT",
                "Object key belongs to different content",
            )
        return existing

    @staticmethod
    def _consume_verified(
        source: BinaryIO,
        *,
        length: int,
        sha256: str,
    ) -> None:
        reader = _HashingReader(source)
        remaining = length
        while remaining:
            chunk = reader.read(min(UPLOAD_BUFFER_SIZE, remaining))
            if not chunk:
                raise V1Error(
                    400,
                    "INCOMPLETE_BODY",
                    "Upload ended before Content-Length",
                )
            remaining -= len(chunk)
        if reader.sha256 != sha256:
            raise V1Error(
                422,
                "HASH_MISMATCH",
                "Uploaded content does not match SHA-256",
            )
