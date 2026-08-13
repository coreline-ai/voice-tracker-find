"""Compatibility exports for the Receiver V1 domain and local adapter.

New server code should depend on :mod:`airvoice.server.ports` and inject a
persistence implementation.  The exports remain here so existing callers do
not break while the cloud adapter is introduced.
"""

from airvoice.adapters.local_receiver import (
    STALE_UPLOAD_TEMP_SECONDS,
    UPLOAD_CHUNK_SIZE,
    LocalReceiverV1Adapter,
    UploadReceipt,
    V1Error,
    atomic_write,
    cleanup_stale_upload_parts,
    etag_for,
    normalize_etag,
    normalize_sha256,
    sha256_file,
    utc_now,
)

ReceiverV1State = LocalReceiverV1Adapter

__all__ = [
    "STALE_UPLOAD_TEMP_SECONDS",
    "UPLOAD_CHUNK_SIZE",
    "LocalReceiverV1Adapter",
    "ReceiverV1State",
    "UploadReceipt",
    "V1Error",
    "atomic_write",
    "cleanup_stale_upload_parts",
    "etag_for",
    "normalize_etag",
    "normalize_sha256",
    "sha256_file",
    "utc_now",
]
