"""Read-only status data for the local receiver dashboard.

The dashboard intentionally lives on the existing receiver process.  It does
not introduce a cloud dependency or a second service: an authenticated browser
request reads the same inbox, vault and local receiver state that the Android
client uses.
"""

from __future__ import annotations

import datetime as dt
import shutil
from pathlib import Path
from typing import Any

from thinktank.ingest import AUDIO_EXTENSIONS


def _utc_timestamp(timestamp: float) -> str:
    return dt.datetime.fromtimestamp(timestamp, tz=dt.UTC).isoformat().replace(
        "+00:00", "Z"
    )


def _file_info(path: Path) -> dict[str, Any]:
    stat = path.stat()
    return {
        "name": path.name,
        "size": stat.st_size,
        "updatedAt": _utc_timestamp(stat.st_mtime),
        "extension": path.suffix.lower().lstrip("."),
    }


def _audio_files(directory: Path) -> list[Path]:
    try:
        paths = [
            path
            for path in directory.iterdir()
            if path.is_file()
            and not path.name.startswith(".")
            and path.suffix.lower() in AUDIO_EXTENSIONS
        ]
    except OSError:
        return []
    return sorted(paths, key=lambda path: path.stat().st_mtime, reverse=True)


def _note_files(vault: Path, folders: tuple[str, ...]) -> list[tuple[str, Path]]:
    found: list[tuple[str, Path]] = []
    for folder in folders:
        directory = vault / folder
        try:
            files = [path for path in directory.glob("*.md") if path.is_file()]
        except OSError:
            continue
        found.extend((folder, path) for path in files)
    return sorted(found, key=lambda item: item[1].stat().st_mtime, reverse=True)


def _storage(directory: Path) -> dict[str, Any]:
    try:
        usage = shutil.disk_usage(directory)
    except OSError:
        return {
            "available": False,
            "total": 0,
            "used": 0,
            "free": 0,
            "usedPercent": 0,
        }
    used_percent = round((usage.used / usage.total) * 100, 1) if usage.total else 0
    return {
        "available": True,
        "total": usage.total,
        "used": usage.used,
        "free": usage.free,
        "usedPercent": used_percent,
    }


def build_dashboard_summary(
    server: Any,
    user: Any,
    note_folders: list[str] | tuple[str, ...],
) -> dict[str, Any]:
    """Build a browser-safe, user-scoped snapshot of receiver state.

    Absolute paths and bearer tokens are deliberately omitted.  The caller is
    already authenticated, and the browser only needs operational facts.
    """
    now = dt.datetime.now(dt.UTC)
    inbox = _audio_files(user.settings.ingest_dir)
    notes = _note_files(user.settings.obsidian_vault, tuple(note_folders))
    storage = _storage(user.settings.ingest_dir)
    started_at = getattr(server, "server_started_at", now.timestamp())
    queue_bytes = sum(path.stat().st_size for path in inbox)
    port = int(server.server_address[1])

    return {
        "status": "ok",
        "apiVersion": "v1",
        "generatedAt": now.isoformat().replace("+00:00", "Z"),
        "receiver": {
            "status": "operational",
            "port": port,
            "startedAt": _utc_timestamp(started_at),
            "autoProcess": bool(getattr(server, "auto_process", False)),
            "tlsEnabled": bool(getattr(server, "tls_enabled", False)),
            "multiUser": bool(getattr(server, "multi_user", False)),
        },
        "user": {
            "name": user.name or "default",
            "inboxLabel": user.settings.ingest_dir.name or "inbox",
            "vaultLabel": user.settings.obsidian_vault.name or "vault",
        },
        "queue": {
            "count": len(inbox),
            "bytes": queue_bytes,
            "items": [_file_info(path) for path in inbox[:12]],
        },
        "notes": {
            "count": len(notes),
            "items": [
                {
                    "folder": folder,
                    **_file_info(path),
                }
                for folder, path in notes[:12]
            ],
        },
        "storage": storage,
        "refreshSeconds": 10,
    }
