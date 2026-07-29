"""Initial Receiver server data schema.

Revision ID: 20260724_0001
Revises:
Create Date: 2026-07-24 09:00:00 KST
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "20260724_0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("user_id", sa.String(length=128), nullable=False),
        sa.Column("status", sa.String(length=32), server_default="active", nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "status IN ('active', 'disabled')",
            name=op.f("ck_users_status_allowed"),
        ),
        sa.PrimaryKeyConstraint("user_id", name=op.f("pk_users")),
    )
    op.create_table(
        "api_tokens",
        sa.Column("token_id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.String(length=128), nullable=False),
        sa.Column("token_digest", sa.String(length=64), nullable=False),
        sa.Column("token_version", sa.Integer(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("expires_at", sa.DateTime(timezone=True)),
        sa.Column("revoked_at", sa.DateTime(timezone=True)),
        sa.Column("last_used_at", sa.DateTime(timezone=True)),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.user_id"],
            name=op.f("fk_api_tokens_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("token_id", name=op.f("pk_api_tokens")),
        sa.UniqueConstraint("token_digest", name=op.f("uq_api_tokens_token_digest")),
        sa.UniqueConstraint(
            "user_id",
            "token_version",
            name=op.f("uq_api_tokens_user_id_token_version"),
        ),
    )
    op.create_table(
        "upload_receipts",
        sa.Column("upload_id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.String(length=128), nullable=False),
        sa.Column("idempotency_key", sa.Uuid(), nullable=False),
        sa.Column("recording_id", sa.Uuid(), nullable=False),
        sa.Column("chunk_id", sa.Uuid(), nullable=False),
        sa.Column("filename", sa.String(length=255), nullable=False),
        sa.Column("extension", sa.String(length=16), nullable=False),
        sa.Column("content_type", sa.String(length=128), nullable=False),
        sa.Column("size", sa.BigInteger(), nullable=False),
        sa.Column("sha256", sa.String(length=64), nullable=False),
        sa.Column("object_key", sa.String(length=1024), nullable=False),
        sa.Column("object_generation", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column(
            "received_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "size > 0",
            name=op.f("ck_upload_receipts_positive_size"),
        ),
        sa.CheckConstraint(
            "char_length(sha256) = 64",
            name=op.f("ck_upload_receipts_sha256_length"),
        ),
        sa.CheckConstraint(
            "status IN ('stored', 'processing', 'processed', 'failed')",
            name=op.f("ck_upload_receipts_status_allowed"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.user_id"],
            name=op.f("fk_upload_receipts_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("upload_id", name=op.f("pk_upload_receipts")),
        sa.UniqueConstraint(
            "user_id",
            "filename",
            name=op.f("uq_upload_receipts_user_id_filename"),
        ),
        sa.UniqueConstraint(
            "user_id",
            "idempotency_key",
            name=op.f("uq_upload_receipts_user_id_idempotency_key"),
        ),
        sa.UniqueConstraint(
            "user_id",
            "recording_id",
            "chunk_id",
            name=op.f("uq_upload_receipts_user_id_recording_id_chunk_id"),
        ),
    )
    op.create_table(
        "processing_jobs",
        sa.Column("job_id", sa.Uuid(), nullable=False),
        sa.Column("receipt_id", sa.Uuid(), nullable=False),
        sa.Column("status", sa.String(length=32), server_default="queued", nullable=False),
        sa.Column("attempt", sa.Integer(), server_default="0", nullable=False),
        sa.Column("lease_owner", sa.String(length=255)),
        sa.Column("lease_expires_at", sa.DateTime(timezone=True)),
        sa.Column("last_error_code", sa.String(length=128)),
        sa.Column("worker_execution_id", sa.String(length=255)),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "attempt >= 0",
            name=op.f("ck_processing_jobs_attempt_nonnegative"),
        ),
        sa.CheckConstraint(
            "status IN ('queued', 'running', 'succeeded', 'retry', 'failed')",
            name=op.f("ck_processing_jobs_status_allowed"),
        ),
        sa.ForeignKeyConstraint(
            ["receipt_id"],
            ["upload_receipts.upload_id"],
            name=op.f("fk_processing_jobs_receipt_id_upload_receipts"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("job_id", name=op.f("pk_processing_jobs")),
        sa.UniqueConstraint(
            "receipt_id",
            name=op.f("uq_processing_jobs_receipt_id"),
        ),
    )
    op.create_index(
        "ix_processing_jobs_claim",
        "processing_jobs",
        ["status", "lease_expires_at"],
    )
    op.create_table(
        "outbox_events",
        sa.Column("event_id", sa.Uuid(), nullable=False),
        sa.Column("event_key", sa.String(length=255), nullable=False),
        sa.Column("aggregate_type", sa.String(length=64), nullable=False),
        sa.Column("aggregate_id", sa.Uuid(), nullable=False),
        sa.Column("event_type", sa.String(length=128), nullable=False),
        sa.Column("payload", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("published_at", sa.DateTime(timezone=True)),
        sa.Column("publish_attempt", sa.Integer(), server_default="0", nullable=False),
        sa.Column("last_error", sa.String(length=512)),
        sa.CheckConstraint(
            "publish_attempt >= 0",
            name=op.f("ck_outbox_events_publish_attempt_nonnegative"),
        ),
        sa.PrimaryKeyConstraint("event_id", name=op.f("pk_outbox_events")),
        sa.UniqueConstraint("event_key", name=op.f("uq_outbox_events_event_key")),
    )
    op.create_index(
        "ix_outbox_events_unpublished",
        "outbox_events",
        ["created_at"],
        postgresql_where=sa.text("published_at IS NULL"),
    )
    op.create_table(
        "notes",
        sa.Column("note_id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.String(length=128), nullable=False),
        sa.Column("folder", sa.String(length=255), nullable=False),
        sa.Column("name", sa.String(length=255), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("revision", sa.String(length=64), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("archived_at", sa.DateTime(timezone=True)),
        sa.CheckConstraint(
            "char_length(revision) = 64",
            name=op.f("ck_notes_revision_length"),
        ),
        sa.ForeignKeyConstraint(
            ["user_id"],
            ["users.user_id"],
            name=op.f("fk_notes_user_id_users"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("note_id", name=op.f("pk_notes")),
    )
    op.create_index(
        "uq_notes_active_user_folder_name",
        "notes",
        ["user_id", "folder", "name"],
        unique=True,
        postgresql_where=sa.text("archived_at IS NULL"),
    )
    op.create_table(
        "note_events",
        sa.Column("event_id", sa.Uuid(), nullable=False),
        sa.Column("note_id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.String(length=128), nullable=False),
        sa.Column("event_type", sa.String(length=32), nullable=False),
        sa.Column("revision", sa.String(length=64), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "event_type IN ('created', 'updated', 'archived')",
            name=op.f("ck_note_events_event_type_allowed"),
        ),
        sa.ForeignKeyConstraint(
            ["note_id"],
            ["notes.note_id"],
            name=op.f("fk_note_events_note_id_notes"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("event_id", name=op.f("pk_note_events")),
    )


def downgrade() -> None:
    op.drop_table("note_events")
    op.drop_index("uq_notes_active_user_folder_name", table_name="notes")
    op.drop_table("notes")
    op.drop_index("ix_outbox_events_unpublished", table_name="outbox_events")
    op.drop_table("outbox_events")
    op.drop_index("ix_processing_jobs_claim", table_name="processing_jobs")
    op.drop_table("processing_jobs")
    op.drop_table("upload_receipts")
    op.drop_table("api_tokens")
    op.drop_table("users")
