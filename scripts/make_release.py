"""Build a deterministic, allowlisted AI R Voice source release zip."""

from __future__ import annotations

import argparse
import hashlib
import zipfile
from collections.abc import Iterable
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ALLOW_FILES = (
    "pyproject.toml",
    "README.md",
    "SETUP.md",
    "DISTRIBUTE.md",
    ".env.example",
)
ALLOW_DIRS = ("src", "scripts", "tests")
CLAUDE_DIRS = ("agents", "commands", "skills")
SKIP_DIR_NAMES = {"__pycache__", ".pytest_cache", ".ruff_cache"}
SENSITIVE_NAMES = {".env", "users.json"}
SENSITIVE_SUFFIXES = {
    ".db",
    ".idsig",
    ".jks",
    ".key",
    ".keystore",
    ".m4a",
    ".mp3",
    ".p12",
    ".pem",
    ".pfx",
    ".wav",
}
FIXED_ZIP_TIME = (2026, 8, 13, 0, 0, 0)


def _walk(directory: Path) -> Iterable[Path]:
    if not directory.is_dir():
        return
    for path in sorted(directory.rglob("*")):
        relative = path.relative_to(ROOT)
        if path.is_symlink() or any(part in SKIP_DIR_NAMES for part in relative.parts):
            continue
        if path.is_file():
            yield path


def _validate_source(path: Path) -> None:
    name = path.name.lower()
    if name in SENSITIVE_NAMES or path.suffix.lower() in SENSITIVE_SUFFIXES:
        raise ValueError(f"sensitive file rejected: {path.relative_to(ROOT)}")
    if "receiver-token" in name or name.endswith("_original.txt"):
        raise ValueError(f"sensitive file rejected: {path.relative_to(ROOT)}")


def release_sources() -> list[tuple[Path, str]]:
    sources: list[tuple[Path, str]] = []
    for relative in ALLOW_FILES:
        path = ROOT / relative
        if path.is_file():
            _validate_source(path)
            sources.append((path, relative))
    for relative in ALLOW_DIRS:
        for path in _walk(ROOT / relative):
            _validate_source(path)
            sources.append((path, path.relative_to(ROOT).as_posix()))
    for relative in CLAUDE_DIRS:
        for path in _walk(ROOT / ".claude" / relative):
            _validate_source(path)
            sources.append((path, path.relative_to(ROOT).as_posix()))
    return sorted(sources, key=lambda item: item[1])


def _write_bytes(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, FIXED_ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data)


def build_release(output: Path, apk: Path) -> Path:
    if not apk.is_file():
        raise FileNotFoundError(f"APK not found: {apk}")
    output.parent.mkdir(parents=True, exist_ok=True)
    sources = release_sources()
    manifest: list[str] = []
    with zipfile.ZipFile(output, "w") as archive:
        for path, name in sources:
            data = path.read_bytes()
            _write_bytes(archive, name, data)
            manifest.append(f"{hashlib.sha256(data).hexdigest()}  {name}")
        apk_data = apk.read_bytes()
        _write_bytes(archive, "ai-r-voice.apk", apk_data)
        manifest.append(f"{hashlib.sha256(apk_data).hexdigest()}  ai-r-voice.apk")
        _write_bytes(
            archive,
            "RELEASE-MANIFEST.sha256",
            ("\n".join(manifest) + "\n").encode(),
        )
    return output


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="AI R Voice privacy-safe release zip builder",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "dist" / "ai-r-voice-release.zip",
    )
    parser.add_argument(
        "--apk",
        type=Path,
        default=ROOT / "ai-r-voice.apk",
    )
    args = parser.parse_args(argv)
    output = build_release(args.output.resolve(), args.apk.resolve())
    print(output)
    print(f"sha256={hashlib.sha256(output.read_bytes()).hexdigest()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
