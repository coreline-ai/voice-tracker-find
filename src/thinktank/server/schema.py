"""PostgreSQL schema shared by repositories and Alembic autogeneration."""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

NAMING_CONVENTION = {
    "ix": "ix_%(table_name)s_%(column_0_N_name)s",
    "uq": "uq_%(table_name)s_%(column_0_N_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}

metadata = sa.MetaData(naming_convention=NAMING_CONVENTION)

users = sa.Table(
    "users",
    metadata,
    sa.Column("user_id", sa.String(128), primary_key=True),
    sa.Column("status", sa.String(32), nullable=False, server_default="active"),
    sa.Column(
        "created_at",
        sa.DateTime(timezone=True),
        nullable=False,
        server_default=sa.func.now(),
    ),
    sa.CheckConstraint(
        "status IN ('active', 'disabled')",
        name="status_allowed",
    ),
)

api_tokens = sa.Table(
    "api_tokens",
    metadata,
    sa.Column("token_id", sa.Uuid(), primary_key=True),
    sa.Column(
        "user_id",
        sa.String(128),
        sa.ForeignKey("users.user_id", ondelete="CASCADE"),
        nullable=False,
    ),
    sa.Column("token_digest", sa.String(64), nullable=False, unique=True),
    sa.Column("token_version", sa.Integer(), nullable=False),
    sa.Column(
        "created_at",
        sa.DateTime(timezone=True),
        nullable=False,
        server_default=sa.func.now(),
    ),
    sa.Column("expires_at", sa.DateTime(timezone=True)),
    sa.Column("revoked_at", sa.DateTime(timezone=True)),
    sa.Column("last_used_at", sa.DateTime(timezone=True)),
    sa.UniqueConstraint("user_id", "token_version"),
)

upload_receipts = sa.Table(
    "upload_receipts",
    metadata,
    sa.Column("upload_id", sa.Uuid(), primary_key=True),
    sa.Column(
        "user_id",
        sa.String(128),
        sa.ForeignKey("users.user_id", ondelete="CASCADE"),
        nullable=False,
    ),
    sa.Column("idempotency_key", sa.Uuid(), nullable=False),
    sa.Column("recording_id", sa.Uuid(), nullable=False),
    sa.Column("chunk_id", sa.Uuid(), nullable=False),
    sa.Column("filename", sa.String(255), nullable=False),
    sa.Column("extension", sa.String(16), nullable=False),
    sa.Column("content_type", sa.String(128), nullable=False),
    sa.Column("size", sa.BigInteger(), nullable=False),
    sa.Column("sha256", sa.String(64), nullable=False),
    sa.Column("object_key", sa.String(1024), nullable=False),
    sa.Column("object_generation", sa.String(64), nullable=False),
    sa.Column("status", sa.String(32), nullable=False),
    sa.Column(
        "received_at",
        sa.DateTime(timezone=True),
        nullable=False,
        server_default=sa.func.now(),
    ),
    sa.CheckConstraint("size > 0", name="positive_size"),
    sa.CheckConstraint("char_length(sha256) = 64", name="sha256_length"),
    sa.CheckConstraint(
        "status IN ('stored', 'processing', 'processed', 'failed')",
        name="status_allowed",
    ),
    sa.UniqueConstraint("user_id", "idempotency_key"),
    sa.UniqueConstraint("user_id", "filename"),
    sa.UniqueConstraint("user_id", "recording_id", "chunk_id"),
)

processing_jobs = sa.Table(
    "processing_jobs",
    metadata,
    sa.Column("job_id", sa.Uuid(), primary_key=True),
    sa.Column(
        "receipt_id",
        sa.Uuid(),
        sa.ForeignKey("upload_receipts.upload_id", ondelete="CASCADE"),
        nullable=False,
        unique=True,
    ),
    sa.Column("status", sa.String(32), nullable=False, server_default="queued"),
    sa.Column("attempt", sa.Integer(), nullable=False, server_default="0"),
    sa.Column("lease_owner", sa.String(255)),
    sa.Column("lease_expires_at", sa.DateTime(timezone=True)),
    sa.Column("last_error_code", sa.String(128)),
    sa.Column("worker_execution_id", sa.String(255)),
    sa.Column(
        "created_at",
        sa.DateTime(timezone=True),
        nullable=False,
        server_default=sa.func.now(),
    ),
    sa.Column(
        "updated_at",
        sa.DateTime(timezone=True),
        nullable=False,
        server_default=sa.func.now(),
    ),
    sa.CheckConstraint("attempt >= 0", name="attempt_nonnegative"),
    sa.CheckConstraint(
        "status IN ('queued', 'running', 'succeeded', 'retry', 'failed')",
        name="status_allowed",
    ),
)

outbox_events = sa.Table(
    "outbox_events",
    metadata,
    sa.Column("event_id", sa.Uuid(), primary_key=True),
    sa.Column("event_key", sa.String(255), nullable=False, unique=True),
    sa.Column("aggregate_type", sa.String(64), nullable=False),
    sa.Column("aggregate_id", sa.Uuid(), nullable=False),
    sa.Column("event_type", sa.String(128), nullable=False),
    sa.Column("payload", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
    sa.Column(
        "created_at",
        sa.DateTime(timezone=True),
        nullable=False,
        server_default=sa.func.now(),
    ),
    sa.Column("published_at", sa.DateTime(timezone=True)),
    sa.Column("publish_attempt", sa.Integer(), nullable=False, server_default="0"),
    sa.Column("last_error", sa.String(512)),
    sa.CheckConstraint("publish_attempt >= 0", name="publish_attempt_nonnegative"),
)

notes = sa.Table(
    "notes",
    metadata,
    sa.Column("note_id", sa.Uuid(), primary_key=True),
    sa.Column(
        "user_id",
        sa.String(128),
        sa.ForeignKey("users.user_id", ondelete="CASCADE"),
        nullable=False,
    ),
    sa.Column("folder", sa.String(255), nullable=False),
    sa.Column("name", sa.String(255), nullable=False),
    sa.Column("content", sa.Text(), nullable=False),
    sa.Column("revision", sa.String(64), nullable=False),
    sa.Column(
        "created_at",
        sa.DateTime(timezone=True),
        nullable=False,
        server_default=sa.func.now(),
    ),
    sa.Column(
        "updated_at",
        sa.DateTime(timezone=True),
        nullable=False,
        server_default=sa.func.now(),
    ),
    sa.Column("archived_at", sa.DateTime(timezone=True)),
    sa.CheckConstraint("char_length(revision) = 64", name="revision_length"),
)

sa.Index(
    "uq_notes_active_user_folder_name",
    notes.c.user_id,
    notes.c.folder,
    notes.c.name,
    unique=True,
    postgresql_where=notes.c.archived_at.is_(None),
)

note_events = sa.Table(
    "note_events",
    metadata,
    sa.Column("event_id", sa.Uuid(), primary_key=True),
    sa.Column(
        "note_id",
        sa.Uuid(),
        sa.ForeignKey("notes.note_id", ondelete="CASCADE"),
        nullable=False,
    ),
    sa.Column("user_id", sa.String(128), nullable=False),
    sa.Column("event_type", sa.String(32), nullable=False),
    sa.Column("revision", sa.String(64), nullable=False),
    sa.Column(
        "created_at",
        sa.DateTime(timezone=True),
        nullable=False,
        server_default=sa.func.now(),
    ),
    sa.CheckConstraint(
        "event_type IN ('created', 'updated', 'archived')",
        name="event_type_allowed",
    ),
)

sa.Index(
    "ix_outbox_events_unpublished",
    outbox_events.c.created_at,
    postgresql_where=outbox_events.c.published_at.is_(None),
)
sa.Index(
    "ix_processing_jobs_claim",
    processing_jobs.c.status,
    processing_jobs.c.lease_expires_at,
)
