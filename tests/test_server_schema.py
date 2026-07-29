"""Static gates for the cloud PostgreSQL schema and credential model."""

from __future__ import annotations

from io import StringIO
from pathlib import Path

import pytest

pytest.importorskip("alembic")
pytest.importorskip("sqlalchemy")

from alembic import command
from alembic.config import Config

from thinktank.server.schema import metadata
from thinktank.server.security import token_digest, token_digest_matches

ROOT = Path(__file__).resolve().parents[1]


def test_initial_alembic_migration_renders_postgresql_sql() -> None:
    output = StringIO()
    config = Config(str(ROOT / "alembic.ini"), output_buffer=output)

    command.upgrade(config, "head", sql=True)

    sql = output.getvalue()
    for table in (
        "users",
        "api_tokens",
        "upload_receipts",
        "processing_jobs",
        "outbox_events",
        "notes",
        "note_events",
    ):
        assert f"CREATE TABLE {table}" in sql
    assert "uq_upload_receipts_user_id_recording_id_chunk_id" in sql
    assert "uq_notes_active_user_folder_name" in sql
    assert "WHERE archived_at IS NULL" in sql
    assert "token_digest" in sql
    assert "token_plaintext" not in sql


def test_schema_metadata_matches_initial_table_set() -> None:
    assert set(metadata.tables) == {
        "users",
        "api_tokens",
        "upload_receipts",
        "processing_jobs",
        "outbox_events",
        "notes",
        "note_events",
    }


def test_bearer_token_uses_keyed_digest_and_constant_time_comparison() -> None:
    pepper = b"p" * 32
    digest = token_digest("secret-token", pepper=pepper)

    assert digest != "secret-token"
    assert len(digest) == 64
    assert token_digest_matches("secret-token", digest, pepper=pepper)
    assert not token_digest_matches("other-token", digest, pepper=pepper)
