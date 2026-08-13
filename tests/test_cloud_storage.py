"""Cloud Storage adapter tests with generation-precondition fakes."""

from __future__ import annotations

import datetime as dt
import hashlib
from io import BytesIO

import pytest

pytest.importorskip("google.cloud.storage")

from google.api_core.exceptions import PreconditionFailed

from airvoice.adapters.cloud_storage import GcsUploadStore
from airvoice.receiver_v1 import V1Error
from airvoice.server.object_keys import (
    content_type_for_filename,
    recording_object_key,
)
from airvoice.server.orphan_sweeper import sweep_orphans


class FakeBlob:
    def __init__(
        self,
        bucket: "FakeBucket",
        name: str,
        *,
        chunk_size: int | None = None,
    ) -> None:
        self.bucket = bucket
        self.name = name
        self.chunk_size = chunk_size
        self.metadata: dict[str, str] = {}
        self.generation: int | None = None
        self.size: int | None = None
        self.content_type: str | None = None
        self.content = b""
        self.updated: dt.datetime | None = None

    def upload_from_file(
        self,
        source,
        *,
        rewind: bool,
        size: int,
        content_type: str,
        if_generation_match: int,
        checksum: str,
    ) -> None:  # noqa: ANN001
        assert not rewind
        assert if_generation_match == 0
        assert checksum == "auto"
        if self.name in self.bucket.objects:
            raise PreconditionFailed("object exists")
        self.content = source.read(size)
        self.size = len(self.content)
        self.content_type = content_type
        self.generation = self.bucket.next_generation()
        self.updated = dt.datetime.now(dt.UTC)
        self.bucket.objects[self.name] = self

    def delete(self, *, if_generation_match: int | str) -> None:
        if str(if_generation_match) != str(self.generation):
            raise PreconditionFailed("generation changed")
        self.bucket.objects.pop(self.name, None)


class FakeBucket:
    def __init__(self) -> None:
        self.objects: dict[str, FakeBlob] = {}
        self._generation = 0
        self.copy_hook = None

    def next_generation(self) -> int:
        self._generation += 1
        return self._generation

    def blob(
        self,
        name: str,
        *,
        chunk_size: int | None = None,
        generation: int | None = None,
    ) -> FakeBlob:
        if generation is not None and name in self.objects:
            return self.objects[name]
        return FakeBlob(self, name, chunk_size=chunk_size)

    def get_blob(self, name: str) -> FakeBlob | None:
        return self.objects.get(name)

    def list_blobs(self, *, prefix: str):
        return [
            blob
            for name, blob in self.objects.items()
            if name.startswith(prefix)
        ]

    def copy_blob(
        self,
        source: FakeBlob,
        destination_bucket: "FakeBucket",
        destination_name: str,
        *,
        source_generation: int | str | None,
        if_generation_match: int,
    ) -> FakeBlob:
        assert destination_bucket is self
        assert str(source_generation) == str(source.generation)
        assert if_generation_match == 0
        if self.copy_hook is not None:
            self.copy_hook(self, destination_name)
            self.copy_hook = None
        if destination_name in self.objects:
            raise PreconditionFailed("destination exists")
        copied = FakeBlob(self, destination_name)
        copied.content = source.content
        copied.size = source.size
        copied.content_type = source.content_type
        copied.metadata = dict(source.metadata)
        copied.generation = self.next_generation()
        copied.updated = dt.datetime.now(dt.UTC)
        self.objects[destination_name] = copied
        return copied


def _put(
    store: GcsUploadStore,
    body: bytes,
    *,
    object_key: str = "users/u/recordings/r/c/hash.m4a",
    declared_hash: str | None = None,
):
    sha256 = declared_hash or hashlib.sha256(body).hexdigest()
    return store.put_verified(
        object_key=object_key,
        source=BytesIO(body),
        length=len(body),
        sha256=sha256,
        content_type="audio/mp4",
        metadata={"uploadId": "upload-1"},
    )


def _existing_blob(
    bucket: FakeBucket,
    name: str,
    body: bytes,
    *,
    sha256: str | None = None,
) -> FakeBlob:
    blob = FakeBlob(bucket, name)
    blob.content = body
    blob.size = len(body)
    blob.content_type = "audio/mp4"
    blob.metadata = {"sha256": sha256 or hashlib.sha256(body).hexdigest()}
    blob.generation = bucket.next_generation()
    blob.updated = dt.datetime.now(dt.UTC)
    bucket.objects[name] = blob
    return blob


def test_object_key_is_deterministic_and_does_not_embed_client_filename() -> None:
    recording_id = "11111111-1111-4111-8111-111111111111"
    chunk_id = "22222222-2222-4222-8222-222222222222"
    sha256 = "a" * 64

    key = recording_object_key(
        user_id="user 한글",
        recording_id=recording_id,
        chunk_id=chunk_id,
        sha256=sha256,
        extension=".m4a",
    )

    assert key == (
        "users/user%20%ED%95%9C%EA%B8%80/recordings/"
        f"{recording_id}/{chunk_id}/{sha256}.m4a"
    )
    assert "client-recording" not in key
    assert content_type_for_filename("client-recording.WAV") == (".wav", "audio/wav")


def test_verified_upload_promotes_generation_zero_and_removes_stage() -> None:
    bucket = FakeBucket()
    store = GcsUploadStore(bucket)

    stored = _put(store, b"audio")

    assert stored.size == 5
    assert stored.sha256 == hashlib.sha256(b"audio").hexdigest()
    assert stored.content_type == "audio/mp4"
    assert stored.object_key in bucket.objects
    assert not any(name.startswith(".staging/") for name in bucket.objects)


def test_hash_mismatch_removes_staging_object_and_creates_no_final() -> None:
    bucket = FakeBucket()
    store = GcsUploadStore(bucket)

    with pytest.raises(V1Error) as raised:
        _put(store, b"audio", declared_hash="0" * 64)

    assert raised.value.status == 422
    assert raised.value.code == "HASH_MISMATCH"
    assert bucket.objects == {}


def test_cancelled_source_removes_stage_before_final_promotion() -> None:
    bucket = FakeBucket()
    body = b"audio"

    class CancelledSource(BytesIO):
        @staticmethod
        def raise_if_cancelled() -> None:
            raise RuntimeError("request cancelled")

    with pytest.raises(RuntimeError, match="request cancelled"):
        GcsUploadStore(bucket).put_verified(
            object_key="users/u/recordings/r/c/hash.m4a",
            source=CancelledSource(body),
            length=len(body),
            sha256=hashlib.sha256(body).hexdigest(),
            content_type="audio/mp4",
            metadata={},
        )

    assert bucket.objects == {}


def test_matching_orphan_is_adopted_after_consuming_and_verifying_body() -> None:
    bucket = FakeBucket()
    key = "users/u/recordings/r/c/hash.m4a"
    body = b"audio"
    existing = _existing_blob(bucket, key, body)
    source = BytesIO(body)

    stored = GcsUploadStore(bucket).put_verified(
        object_key=key,
        source=source,
        length=len(body),
        sha256=hashlib.sha256(body).hexdigest(),
        content_type="audio/mp4",
        metadata={},
    )

    assert stored.generation == str(existing.generation)
    assert source.tell() == len(body)
    assert len(bucket.objects) == 1


def test_existing_object_with_different_content_is_conflict() -> None:
    bucket = FakeBucket()
    key = "users/u/recordings/r/c/hash.m4a"
    _existing_blob(bucket, key, b"other")

    with pytest.raises(V1Error) as raised:
        _put(store=GcsUploadStore(bucket), body=b"audio", object_key=key)

    assert raised.value.status == 409
    assert raised.value.code == "UPLOAD_CONFLICT"


def test_copy_precondition_race_adopts_matching_concurrent_object() -> None:
    bucket = FakeBucket()
    key = "users/u/recordings/r/c/hash.m4a"
    body = b"audio"

    def create_concurrent(target: FakeBucket, destination_name: str) -> None:
        _existing_blob(target, destination_name, body)

    bucket.copy_hook = create_concurrent
    stored = _put(GcsUploadStore(bucket), body, object_key=key)

    assert stored.object_key == key
    assert stored.sha256 == hashlib.sha256(body).hexdigest()
    assert not any(name.startswith(".staging/") for name in bucket.objects)


def test_orphan_sweeper_deletes_only_old_unreferenced_generation() -> None:
    bucket = FakeBucket()
    now = dt.datetime(2026, 7, 24, tzinfo=dt.UTC)
    old_orphan = _existing_blob(bucket, "users/u/old.m4a", b"old")
    old_orphan.updated = now - dt.timedelta(days=2)
    referenced = _existing_blob(bucket, "users/u/referenced.m4a", b"kept")
    referenced.updated = now - dt.timedelta(days=2)
    recent = _existing_blob(bucket, "users/u/recent.m4a", b"recent")
    recent.updated = now - dt.timedelta(hours=1)

    class ReceiptIndex:
        def object_is_referenced(self, object_key: str, generation: str) -> bool:
            return (
                object_key == referenced.name
                and generation == str(referenced.generation)
            )

    report = sweep_orphans(
        catalog=GcsUploadStore(bucket),
        receipts=ReceiptIndex(),
        prefixes=("users/",),
        now=now,
    )

    assert report.scanned == 3
    assert report.deleted == 1
    assert report.referenced == 1
    assert report.recent == 1
    assert old_orphan.name not in bucket.objects
    assert referenced.name in bucket.objects
    assert recent.name in bucket.objects
