"""Versioned receiver API state and filesystem operations.

The legacy Android client is intentionally kept in :mod:`thinktank.receiver`.
This module implements the durable contracts used by the Compose client:

* a user-scoped SQLite upload receipt ledger;
* content verified, idempotent audio delivery;
* stable note identifiers with content-hash revisions;
* atomic note writes and archive operations; and
* structured APK metadata.

Only Python's standard library is used so enabling the receiver does not add a
runtime dependency to the voice pipeline.
"""

from __future__ import annotations

import contextlib
import datetime as dt
import hashlib
import json
import os
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

UPLOAD_CHUNK_SIZE = 64 * 1024
# An interrupted request leaves a request-scoped ``.part`` file behind.  Keep
# it long enough for diagnostics, but do not let restarts accumulate orphaned
# recordings forever.  This cleanup only runs while creating the receiver
# state, therefore it cannot race an upload handled by that server process.
STALE_UPLOAD_TEMP_SECONDS = 24 * 60 * 60


class V1Error(Exception):
    """An error that maps directly to a versioned HTTP API response."""

    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


@dataclass(frozen=True)
class UploadReceipt:
    upload_id: str
    idempotency_key: str
    recording_id: str
    chunk_id: str
    filename: str
    size: int
    sha256: str
    status: str
    received_at: str

    def as_dict(self) -> dict[str, object]:
        return {
            "uploadId": self.upload_id,
            "idempotencyKey": self.idempotency_key,
            "recordingId": self.recording_id,
            "chunkId": self.chunk_id,
            "filename": self.filename,
            "size": self.size,
            "sha256": self.sha256,
            "status": self.status,
            "receivedAt": self.received_at,
        }


def utc_now() -> str:
    return dt.datetime.now(dt.UTC).isoformat().replace("+00:00", "Z")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(UPLOAD_CHUNK_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cleanup_stale_upload_parts(
    directories: list[Path] | tuple[Path, ...],
    *,
    now: float | None = None,
    max_age_seconds: int = STALE_UPLOAD_TEMP_SECONDS,
) -> int:
    """Remove stale V1 request temp files left by a terminated receiver.

    The final recording name is never touched.  Only the exact random-name
    V1 temporary format is eligible, and malformed/missing paths are skipped
    so startup remains available when an inbox is concurrently managed by an
    administrator.  ``now`` is injected for deterministic tests.
    """
    cutoff = (time.time() if now is None else now) - max_age_seconds
    removed = 0
    for directory in directories:
        try:
            candidates = tuple(directory.glob(".thinktank-v1.*.part"))
        except OSError:
            continue
        for candidate in candidates:
            try:
                stat = candidate.lstat()
                if stat.st_mtime > cutoff:
                    continue
                # unlinking a symlink removes only the link, never its target.
                candidate.unlink()
                removed += 1
            except FileNotFoundError:
                continue
            except OSError:
                continue
    return removed


def normalize_sha256(value: str | None) -> str | None:
    """Return a lowercase hex digest from supported HTTP header formats."""
    if value is None:
        return None
    candidate = value.strip()
    if candidate.lower().startswith("sha-256="):
        candidate = candidate.split("=", 1)[1].strip()
    if candidate.lower().startswith("sha256:"):
        candidate = candidate.split(":", 1)[1].strip()
    candidate = candidate.lower()
    if len(candidate) != 64 or any(ch not in "0123456789abcdef" for ch in candidate):
        raise V1Error(400, "INVALID_SHA256", "SHA-256 must be 64 hexadecimal characters")
    return candidate


def normalize_etag(value: str | None) -> str | None:
    if value is None:
        return None
    result = value.strip()
    if result.startswith("W/"):
        result = result[2:].strip()
    if len(result) >= 2 and result[0] == result[-1] == '"':
        result = result[1:-1]
    return result


def etag_for(revision: str) -> str:
    return f'"{revision}"'


def _revision(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _fsync_directory(directory: Path) -> None:
    """Best-effort directory fsync after an atomic rename."""
    try:
        descriptor = os.open(directory, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(descriptor)
    except OSError:
        pass
    finally:
        os.close(descriptor)


def atomic_write(path: Path, content: bytes) -> None:
    part = path.parent / f".thinktank-v1.{uuid.uuid4().hex}.part"
    try:
        with part.open("xb") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(part, path)
        _fsync_directory(path.parent)
    finally:
        part.unlink(missing_ok=True)


class ReceiverV1State:
    """Thread-safe durable state shared by all handler instances."""

    def __init__(
        self,
        database: str | Path,
        note_folders: list[str],
        *,
        ingest_directories: list[Path] | tuple[Path, ...] = (),
    ) -> None:
        self.database = Path(database).expanduser()
        self.database.parent.mkdir(parents=True, exist_ok=True)
        self.note_folders = tuple(note_folders)
        self._write_lock = threading.RLock()
        self._initialize()
        cleanup_stale_upload_parts(ingest_directories)

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database, timeout=30)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 30000")
        return connection

    def _initialize(self) -> None:
        with self._connect() as db:
            db.executescript(
                """
                PRAGMA journal_mode = WAL;
                CREATE TABLE IF NOT EXISTS upload_receipts (
                    user_id TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    upload_id TEXT NOT NULL,
                    recording_id TEXT NOT NULL,
                    chunk_id TEXT NOT NULL,
                    filename TEXT NOT NULL,
                    size INTEGER NOT NULL CHECK(size >= 0),
                    sha256 TEXT NOT NULL,
                    status TEXT NOT NULL,
                    received_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, idempotency_key),
                    UNIQUE (user_id, upload_id),
                    UNIQUE (user_id, filename),
                    UNIQUE (user_id, recording_id, chunk_id)
                );
                CREATE TABLE IF NOT EXISTS note_identities (
                    user_id TEXT NOT NULL,
                    note_id TEXT NOT NULL,
                    folder TEXT NOT NULL,
                    name TEXT NOT NULL,
                    archived_at TEXT,
                    PRIMARY KEY (user_id, note_id),
                    UNIQUE (user_id, folder, name)
                );
                """
            )
            columns = {
                row["name"]
                for row in db.execute("PRAGMA table_info(upload_receipts)").fetchall()
            }
            if "recording_id" not in columns:
                db.execute(
                    "ALTER TABLE upload_receipts "
                    "ADD COLUMN recording_id TEXT NOT NULL DEFAULT ''"
                )
            if "chunk_id" not in columns:
                db.execute(
                    "ALTER TABLE upload_receipts "
                    "ADD COLUMN chunk_id TEXT NOT NULL DEFAULT ''"
                )
            db.execute(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS uq_upload_receipt_chunk
                ON upload_receipts(user_id, recording_id, chunk_id)
                WHERE recording_id <> '' AND chunk_id <> ''
                """
            )

    @staticmethod
    def user_id(user) -> str:  # noqa: ANN001
        # The empty name is the established identifier for single-user mode.
        return str(user.name)

    @staticmethod
    def _receipt(row: sqlite3.Row) -> UploadReceipt:
        return UploadReceipt(
            upload_id=row["upload_id"],
            idempotency_key=row["idempotency_key"],
            recording_id=row["recording_id"],
            chunk_id=row["chunk_id"],
            filename=row["filename"],
            size=row["size"],
            sha256=row["sha256"],
            status=row["status"],
            received_at=row["received_at"],
        )

    def _find_receipt(
        self,
        db: sqlite3.Connection,
        user_id: str,
        idempotency_key: str,
        filename: str,
        recording_id: str,
        chunk_id: str,
    ) -> sqlite3.Row | None:
        return db.execute(
            """
            SELECT * FROM upload_receipts
            WHERE user_id = ?
              AND (
                  idempotency_key = ?
                  OR filename = ?
                  OR (recording_id = ? AND chunk_id = ?)
              )
            ORDER BY
                CASE WHEN idempotency_key = ? THEN 0 ELSE 1 END,
                CASE WHEN recording_id = ? AND chunk_id = ? THEN 0 ELSE 1 END
            LIMIT 1
            """,
            (
                user_id,
                idempotency_key,
                filename,
                recording_id,
                chunk_id,
                idempotency_key,
                recording_id,
                chunk_id,
            ),
        ).fetchone()

    @staticmethod
    def _same_upload(
        row: sqlite3.Row,
        *,
        recording_id: str,
        chunk_id: str,
        filename: str,
        size: int,
        sha256: str,
    ) -> bool:
        return (
            row["recording_id"] == recording_id
            and row["chunk_id"] == chunk_id
            and row["filename"] == filename
            and row["size"] == size
            and row["sha256"] == sha256
        )

    def receive_upload(
        self,
        *,
        user,
        filename: str,
        length: int,
        declared_sha256: str | None,
        idempotency_key: str,
        recording_id: str,
        chunk_id: str,
        source: BinaryIO,
    ) -> tuple[UploadReceipt, bool]:
        """Stream, verify and commit an upload.

        Returns ``(receipt, created)``. Request-specific temporary files avoid
        concurrent writers sharing a path. The SQLite receipt remains after the
        pipeline moves the ingest file, so later retries are still idempotent.
        """
        ingest_dir: Path = user.settings.ingest_dir
        temp = ingest_dir / f".thinktank-v1.{uuid.uuid4().hex}.part"
        remaining = length
        digest = hashlib.sha256()
        try:
            with temp.open("xb") as handle:
                while remaining:
                    chunk = source.read(min(UPLOAD_CHUNK_SIZE, remaining))
                    if not chunk:
                        raise V1Error(
                            400, "INCOMPLETE_BODY", "Upload ended before Content-Length"
                        )
                    handle.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
                handle.flush()
                os.fsync(handle.fileno())

            actual_sha256 = digest.hexdigest()
            if declared_sha256 is not None and actual_sha256 != declared_sha256:
                raise V1Error(
                    422, "HASH_MISMATCH", "Uploaded content does not match SHA-256"
                )

            user_id = self.user_id(user)
            target = ingest_dir / filename
            with self._write_lock, self._connect() as db:
                db.execute("BEGIN IMMEDIATE")
                existing = self._find_receipt(
                    db,
                    user_id,
                    idempotency_key,
                    filename,
                    recording_id,
                    chunk_id,
                )
                if existing is not None:
                    if not self._same_upload(
                        existing,
                        recording_id=recording_id,
                        chunk_id=chunk_id,
                        filename=filename,
                        size=length,
                        sha256=actual_sha256,
                    ):
                        raise V1Error(
                            409,
                            "UPLOAD_CONFLICT",
                            "Idempotency key or filename belongs to different content",
                        )
                    return self._receipt(existing), False

                # Adopt a matching orphan target if a process stopped after rename
                # but before its receipt transaction completed.
                if target.exists():
                    target_size = target.stat().st_size
                    target_hash = sha256_file(target)
                    if target_size != length or target_hash != actual_sha256:
                        raise V1Error(
                            409,
                            "UPLOAD_CONFLICT",
                            "Filename already exists with different content",
                        )
                else:
                    os.replace(temp, target)
                    _fsync_directory(ingest_dir)

                received_at = utc_now()
                upload_id = idempotency_key
                db.execute(
                    """
                    INSERT INTO upload_receipts (
                        user_id, idempotency_key, upload_id, recording_id,
                        chunk_id, filename, size, sha256, status, received_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'stored', ?)
                    """,
                    (
                        user_id,
                        idempotency_key,
                        upload_id,
                        recording_id,
                        chunk_id,
                        filename,
                        length,
                        actual_sha256,
                        received_at,
                    ),
                )
                row = self._find_receipt(
                    db,
                    user_id,
                    idempotency_key,
                    filename,
                    recording_id,
                    chunk_id,
                )
                assert row is not None
                return self._receipt(row), True
        finally:
            temp.unlink(missing_ok=True)

    @staticmethod
    def _vault_root(user) -> Path:  # noqa: ANN001
        return user.settings.obsidian_vault.resolve()

    def _note_path(
        self, user, folder: str, name: str, *, must_exist: bool
    ) -> Path:  # noqa: ANN001
        if folder not in self.note_folders:
            raise V1Error(400, "INVALID_FOLDER", "Folder is not exposed to mobile clients")
        root = self._vault_root(user)
        directory = root / folder
        directory.mkdir(parents=True, exist_ok=True)
        candidate = directory / name
        if candidate.is_symlink():
            raise V1Error(400, "UNSAFE_NOTE_PATH", "Symbolic links are not supported")
        resolved_parent = candidate.parent.resolve()
        if not resolved_parent.is_relative_to(root):
            raise V1Error(400, "UNSAFE_NOTE_PATH", "Note path escapes the vault")
        if must_exist and (not candidate.is_file() or candidate.is_symlink()):
            raise V1Error(404, "NOTE_NOT_FOUND", "Note does not exist")
        return candidate

    def _identity(
        self,
        db: sqlite3.Connection,
        *,
        user_id: str,
        folder: str,
        name: str,
    ) -> str:
        row = db.execute(
            """
            SELECT note_id FROM note_identities
            WHERE user_id = ? AND folder = ? AND name = ? AND archived_at IS NULL
            """,
            (user_id, folder, name),
        ).fetchone()
        if row is not None:
            return row["note_id"]
        note_id = str(uuid.uuid4())
        db.execute(
            """
            INSERT INTO note_identities(user_id, note_id, folder, name)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(user_id, folder, name)
            DO UPDATE SET archived_at = NULL
            """,
            (user_id, note_id, folder, name),
        )
        row = db.execute(
            """
            SELECT note_id FROM note_identities
            WHERE user_id = ? AND folder = ? AND name = ?
            """,
            (user_id, folder, name),
        ).fetchone()
        assert row is not None
        return row["note_id"]

    @staticmethod
    def _note_dict(
        *, note_id: str, folder: str, name: str, content: bytes, path: Path
    ) -> dict[str, object]:
        return {
            "id": note_id,
            "folder": folder,
            "name": name,
            "content": content.decode("utf-8"),
            "revision": _revision(content),
            "updatedAt": dt.datetime.fromtimestamp(
                path.stat().st_mtime, tz=dt.UTC
            ).isoformat().replace("+00:00", "Z"),
        }

    def list_notes(self, user, collected: list[tuple[str, Path]]) -> list[dict[str, object]]:  # noqa: ANN001
        user_id = self.user_id(user)
        notes: list[dict[str, object]] = []
        with self._write_lock, self._connect() as db:
            for folder, path in collected:
                try:
                    safe = self._note_path(user, folder, path.name, must_exist=True)
                    content = safe.read_bytes()
                    content.decode("utf-8")
                except (OSError, UnicodeDecodeError, V1Error):
                    continue
                note_id = self._identity(
                    db, user_id=user_id, folder=folder, name=path.name
                )
                notes.append(
                    self._note_dict(
                        note_id=note_id,
                        folder=folder,
                        name=path.name,
                        content=content,
                        path=safe,
                    )
                )
        return notes

    def _identity_by_id(self, db: sqlite3.Connection, user_id: str, note_id: str):
        return db.execute(
            """
            SELECT * FROM note_identities
            WHERE user_id = ? AND note_id = ? AND archived_at IS NULL
            """,
            (user_id, note_id),
        ).fetchone()

    @staticmethod
    def _identity_any_by_id(
        db: sqlite3.Connection, user_id: str, note_id: str
    ) -> sqlite3.Row | None:
        return db.execute(
            """
            SELECT * FROM note_identities
            WHERE user_id = ? AND note_id = ?
            """,
            (user_id, note_id),
        ).fetchone()

    def get_note(self, user, note_id: str) -> dict[str, object]:  # noqa: ANN001
        user_id = self.user_id(user)
        with self._connect() as db:
            identity = self._identity_by_id(db, user_id, note_id)
            if identity is None:
                raise V1Error(404, "NOTE_NOT_FOUND", "Note does not exist")
            path = self._note_path(
                user, identity["folder"], identity["name"], must_exist=True
            )
            content = path.read_bytes()
            try:
                content.decode("utf-8")
            except UnicodeDecodeError as exc:
                raise V1Error(500, "INVALID_NOTE_ENCODING", "Note is not UTF-8") from exc
            return self._note_dict(
                note_id=note_id,
                folder=identity["folder"],
                name=identity["name"],
                content=content,
                path=path,
            )

    def create_note(
        self, user, *, folder: str, name: str, content: str  # noqa: ANN001
    ) -> dict[str, object]:
        if not name.endswith(".md"):
            raise V1Error(400, "INVALID_NOTE_NAME", "Note name must end with .md")
        path = self._note_path(user, folder, name, must_exist=False)
        user_id = self.user_id(user)
        encoded = content.encode("utf-8")
        with self._write_lock, self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if path.exists():
                raise V1Error(409, "NOTE_CONFLICT", "A note with this name exists")
            existing = db.execute(
                """
                SELECT 1 FROM note_identities
                WHERE user_id = ? AND folder = ? AND name = ? AND archived_at IS NULL
                """,
                (user_id, folder, name),
            ).fetchone()
            if existing is not None:
                raise V1Error(409, "NOTE_CONFLICT", "A note with this name exists")
            atomic_write(path, encoded)
            note_id = self._identity(
                db, user_id=user_id, folder=folder, name=name
            )
            return self._note_dict(
                note_id=note_id,
                folder=folder,
                name=name,
                content=encoded,
                path=path,
            )

    def update_note(
        self, user, note_id: str, *, content: str, if_match: str | None  # noqa: ANN001
    ) -> dict[str, object]:
        expected = normalize_etag(if_match)
        if expected is None:
            raise V1Error(428, "PRECONDITION_REQUIRED", "If-Match is required")
        user_id = self.user_id(user)
        encoded = content.encode("utf-8")
        with self._write_lock, self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            identity = self._identity_by_id(db, user_id, note_id)
            if identity is None:
                raise V1Error(404, "NOTE_NOT_FOUND", "Note does not exist")
            path = self._note_path(
                user, identity["folder"], identity["name"], must_exist=True
            )
            current = path.read_bytes()
            if expected not in {"*", _revision(current)}:
                raise V1Error(
                    412, "REVISION_CONFLICT", "Note changed since it was downloaded"
                )
            atomic_write(path, encoded)
            return self._note_dict(
                note_id=note_id,
                folder=identity["folder"],
                name=identity["name"],
                content=encoded,
                path=path,
            )

    def archive_note(
        self, user, note_id: str, *, if_match: str | None, archive_dir: str  # noqa: ANN001
    ) -> dict[str, object]:
        expected = normalize_etag(if_match)
        if expected is None:
            raise V1Error(428, "PRECONDITION_REQUIRED", "If-Match is required")
        user_id = self.user_id(user)
        with self._write_lock, self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            identity = self._identity_any_by_id(db, user_id, note_id)
            if identity is None:
                raise V1Error(404, "NOTE_NOT_FOUND", "Note does not exist")
            if identity["archived_at"] is not None:
                return {
                    "id": note_id,
                    "archived": True,
                    "status": "archived",
                    "archivedAt": identity["archived_at"],
                }
            source = self._note_path(
                user, identity["folder"], identity["name"], must_exist=True
            )
            current = source.read_bytes()
            if expected not in {"*", _revision(current)}:
                raise V1Error(
                    412, "REVISION_CONFLICT", "Note changed since it was downloaded"
                )
            archive = self._vault_root(user) / archive_dir
            archive.mkdir(parents=True, exist_ok=True)
            target = archive / source.name
            counter = 2
            while target.exists():
                target = archive / f"{source.stem}_{counter}{source.suffix}"
                counter += 1
            os.replace(source, target)
            _fsync_directory(source.parent)
            _fsync_directory(archive)
            archived_at = utc_now()
            db.execute(
                """
                UPDATE note_identities SET archived_at = ?
                WHERE user_id = ? AND note_id = ?
                """,
                (archived_at, user_id, note_id),
            )
            return {
                "id": note_id,
                "archived": True,
                "status": "archived",
                "archivedAt": archived_at,
                "archiveName": target.name,
            }

    @staticmethod
    def apk_info(apk: Path) -> dict[str, object]:
        if not apk.is_file():
            raise V1Error(404, "APK_NOT_FOUND", "APK is not configured")
        metadata_path = apk.with_suffix(".version.json")
        metadata: dict[str, object] = {}
        if metadata_path.is_file():
            try:
                loaded = json.loads(metadata_path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    metadata = loaded
            except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise V1Error(
                    500, "INVALID_APK_METADATA", "APK version metadata is invalid"
                ) from exc
        notes_path = apk.with_suffix(".version.txt")
        release_notes = ""
        if notes_path.is_file():
            with contextlib.suppress(OSError, UnicodeDecodeError):
                release_notes = notes_path.read_text(encoding="utf-8")
        try:
            version_code = int(metadata.get("versionCode", 0))
        except (TypeError, ValueError) as exc:
            raise V1Error(
                500, "INVALID_APK_METADATA", "APK versionCode must be an integer"
            ) from exc
        result = {
            "versionCode": version_code,
            "versionName": str(metadata.get("versionName", apk.stem)),
            "sha256": sha256_file(apk),
            "size": apk.stat().st_size,
            "releaseNotes": str(metadata.get("releaseNotes", release_notes)),
            "downloadUrl": "/api/v1/apk",
        }
        return result
