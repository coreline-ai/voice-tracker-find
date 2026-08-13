"""Wire-level constants and validation shared by Receiver V1 implementations."""

from pathlib import Path

MAX_V1_UPLOAD_BYTES = 2 * 1024 * 1024 * 1024
MAX_V1_JSON_BYTES = 1024 * 1024

UPLOAD_STATUS_CREATED = "created"
UPLOAD_STATUS_ALREADY_EXISTS = "already_exists"
UPLOAD_WIRE_STATUSES = frozenset(
    {UPLOAD_STATUS_CREATED, UPLOAD_STATUS_ALREADY_EXISTS}
)

UPLOAD_MEDIA_TYPES = {
    ".m4a": "audio/mp4",
    ".mp3": "audio/mpeg",
    ".ogg": "audio/ogg",
    ".wav": "audio/wav",
}

NOTE_FOLDERS = frozenset({"1 wiki", "10-daily", "30-ideas", "90-archive"})

_WINDOWS_RESERVED_NAMES = frozenset(
    {"CON", "PRN", "AUX", "NUL"}
    | {f"COM{index}" for index in range(1, 10)}
    | {f"LPT{index}" for index in range(1, 10)}
)


def is_safe_leaf_name(name: str) -> bool:
    """Return whether ``name`` is a portable, non-traversing leaf filename."""
    if not name or name in {".", ".."}:
        return False
    if "/" in name or "\\" in name or ":" in name:
        return False
    if any(character < " " or character == "\x7f" for character in name):
        return False
    if name != name.strip(" ."):
        return False
    if name.split(".", 1)[0].upper() in _WINDOWS_RESERVED_NAMES:
        return False
    return name == Path(name).name
