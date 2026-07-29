"""Validated Cloud API runtime configuration and dependency assembly."""

from __future__ import annotations

import base64
import binascii
import os
from dataclasses import dataclass
from typing import Mapping

import sqlalchemy as sa
from google.cloud import storage

from thinktank.adapters.cloud_receiver import CloudReceiverV1Adapter
from thinktank.adapters.cloud_storage import GcsUploadStore
from thinktank.adapters.postgres import PostgresDataStore
from thinktank.server.auth import BearerTokenAuthenticator
from thinktank.server.contracts import MAX_V1_UPLOAD_BYTES


class CloudApiConfigurationError(ValueError):
    """Raised when a required runtime value is absent or unsafe."""


def _integer(
    values: Mapping[str, str],
    name: str,
    default: int,
    *,
    minimum: int,
    maximum: int,
) -> int:
    raw = values.get(name, str(default))
    try:
        value = int(raw)
    except ValueError as exc:
        raise CloudApiConfigurationError(f"{name} must be an integer") from exc
    if value < minimum or value > maximum:
        raise CloudApiConfigurationError(
            f"{name} must be between {minimum} and {maximum}"
        )
    return value


def _required(values: Mapping[str, str], name: str) -> str:
    value = values.get(name, "").strip()
    if not value:
        raise CloudApiConfigurationError(f"{name} is required")
    return value


def load_token_pepper(values: Mapping[str, str]) -> bytes:
    """Load a raw or base64-encoded token pepper without logging its value."""
    encoded = values.get("TOKEN_PEPPER_B64", "").strip()
    if encoded:
        try:
            pepper = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise CloudApiConfigurationError(
                "TOKEN_PEPPER_B64 must be valid base64"
            ) from exc
    else:
        pepper = _required(values, "TOKEN_PEPPER").encode("utf-8")
    if len(pepper) < 32:
        raise CloudApiConfigurationError(
            "TOKEN_PEPPER or decoded TOKEN_PEPPER_B64 must be at least 32 bytes"
        )
    return pepper


@dataclass(frozen=True)
class CloudApiSettings:
    database_url: str
    gcs_bucket: str
    token_pepper: bytes
    port: int = 8080
    db_pool_size: int = 5
    db_max_overflow: int = 2
    db_pool_timeout_seconds: int = 5
    db_connection_budget: int = 7
    request_timeout_seconds: int = 900
    graceful_timeout_seconds: int = 8
    max_upload_bytes: int = MAX_V1_UPLOAD_BYTES
    stream_queue_chunks: int = 4
    google_cloud_project: str | None = None

    @classmethod
    def from_env(
        cls,
        environ: Mapping[str, str] | None = None,
    ) -> CloudApiSettings:
        values = os.environ if environ is None else environ
        pool_size = _integer(
            values, "DB_POOL_SIZE", 5, minimum=1, maximum=100
        )
        max_overflow = _integer(
            values, "DB_MAX_OVERFLOW", 2, minimum=0, maximum=100
        )
        connection_budget = _integer(
            values, "DB_CONNECTION_BUDGET", 7, minimum=1, maximum=200
        )
        if pool_size + max_overflow > connection_budget:
            raise CloudApiConfigurationError(
                "DB_POOL_SIZE + DB_MAX_OVERFLOW exceeds DB_CONNECTION_BUDGET"
            )
        project = values.get("GOOGLE_CLOUD_PROJECT", "").strip() or None
        return cls(
            database_url=_required(values, "DATABASE_URL"),
            gcs_bucket=_required(values, "GCS_BUCKET"),
            token_pepper=load_token_pepper(values),
            port=_integer(values, "PORT", 8080, minimum=1, maximum=65535),
            db_pool_size=pool_size,
            db_max_overflow=max_overflow,
            db_pool_timeout_seconds=_integer(
                values,
                "DB_POOL_TIMEOUT_SECONDS",
                5,
                minimum=1,
                maximum=60,
            ),
            db_connection_budget=connection_budget,
            request_timeout_seconds=_integer(
                values,
                "REQUEST_TIMEOUT_SECONDS",
                900,
                minimum=1,
                maximum=3600,
            ),
            graceful_timeout_seconds=_integer(
                values,
                "GRACEFUL_TIMEOUT_SECONDS",
                8,
                minimum=1,
                maximum=9,
            ),
            max_upload_bytes=_integer(
                values,
                "MAX_UPLOAD_BYTES",
                MAX_V1_UPLOAD_BYTES,
                minimum=1,
                maximum=MAX_V1_UPLOAD_BYTES,
            ),
            stream_queue_chunks=_integer(
                values,
                "STREAM_QUEUE_CHUNKS",
                4,
                minimum=1,
                maximum=32,
            ),
            google_cloud_project=project,
        )


@dataclass
class CloudRuntime:
    receiver: CloudReceiverV1Adapter
    authenticator: BearerTokenAuthenticator
    data: PostgresDataStore
    engine: sa.Engine

    def close(self) -> None:
        self.engine.dispose()


def build_engine(settings: CloudApiSettings) -> sa.Engine:
    """Create a bounded, pre-pinged pool for one Cloud Run instance."""
    return sa.create_engine(
        settings.database_url,
        pool_size=settings.db_pool_size,
        max_overflow=settings.db_max_overflow,
        pool_timeout=settings.db_pool_timeout_seconds,
        pool_pre_ping=True,
        pool_recycle=1800,
    )


def build_cloud_runtime(settings: CloudApiSettings) -> CloudRuntime:
    engine = build_engine(settings)
    data = PostgresDataStore(engine)
    client = storage.Client(project=settings.google_cloud_project)
    uploads = GcsUploadStore(client.bucket(settings.gcs_bucket))
    receiver = CloudReceiverV1Adapter(uploads=uploads, data=data)
    authenticator = BearerTokenAuthenticator(
        data,
        pepper=settings.token_pepper,
    )
    return CloudRuntime(
        receiver=receiver,
        authenticator=authenticator,
        data=data,
        engine=engine,
    )
