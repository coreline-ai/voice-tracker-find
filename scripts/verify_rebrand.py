"""Verify the active AI R Voice identity and isolate legacy compatibility literals."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LEGACY_PATTERN = re.compile(r"thinktank|com\.thinktank|THINKTANK_", re.IGNORECASE)

CURRENT_ROOT_FILES = (
    ".env.example",
    "Dockerfile.api",
    "DISTRIBUTE.md",
    "HANDOFF.md",
    "README.md",
    "SETUP.md",
    "pyproject.toml",
)
ACTIVE_ROOTS = (
    "android-app",
    "migrations",
    "scripts",
    "src",
    "tests",
    "web",
)
SKIP_PARTS = {
    ".gradle",
    ".pytest_cache",
    ".ruff_cache",
    "__pycache__",
    "build",
    "local-maven",
}
ALLOWED_LEGACY_LINES = {
    "scripts/register_task.ps1": {'$LegacyTaskName = "thinktank-nightly"'},
    "scripts/register_receiver_task.ps1": {
        '$LegacyTaskName = "thinktank-receiver"'
    },
    "src/airvoice/legacy_migration.py": {
        'LEGACY_HOME = Path("~/.thinktank").expanduser()'
    },
    "tests/test_web_token_storage.py": {
        '{"thinktank-receiver-dashboard-token": "legacy-value"},',
        '"thinktank-receiver-dashboard-token": "legacy-value",',
        '"thinktank-receiver-dashboard-token",',
    },
    "web/dashboard/token-storage.js": {
        "const LEGACY_TOKEN_KEY = 'thinktank-receiver-dashboard-token';"
    },
}


def active_files() -> list[Path]:
    files = [ROOT / name for name in CURRENT_ROOT_FILES]
    for name in ACTIVE_ROOTS:
        for path in (ROOT / name).rglob("*"):
            if not path.is_file() or any(part in SKIP_PARTS for part in path.parts):
                continue
            if path.resolve() == Path(__file__).resolve():
                continue
            if path.name == "PROVENANCE.md":
                continue
            files.append(path)
    return files


def has_active_path_content(path: Path) -> bool:
    """Return whether a forbidden path contains active files.

    A stale ignored cache such as ``src/thinktank/__pycache__`` must not make the
    rebrand gate fail. Source files and non-ignored nested content still fail the
    gate, while empty directories and generated cache content are ignored.
    """
    if not path.exists():
        return False
    if path.is_file():
        return True
    try:
        candidates = path.rglob("*")
    except OSError:
        return True
    return any(
        candidate.is_file()
        and not any(part in SKIP_PARTS for part in candidate.relative_to(path).parts)
        for candidate in candidates
    )


def verify_legacy_literals() -> list[str]:
    failures: list[str] = []
    seen_allowed: dict[str, set[str]] = {key: set() for key in ALLOWED_LEGACY_LINES}
    for path in active_files():
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        relative = path.relative_to(ROOT).as_posix()
        for number, line in enumerate(text.splitlines(), 1):
            if not LEGACY_PATTERN.search(line):
                continue
            stripped = line.strip()
            if stripped in ALLOWED_LEGACY_LINES.get(relative, set()):
                seen_allowed[relative].add(stripped)
                continue
            failures.append(f"{relative}:{number}: {stripped}")
    for relative, allowed in ALLOWED_LEGACY_LINES.items():
        missing = allowed - seen_allowed[relative]
        failures.extend(f"{relative}: missing compatibility literal: {line}" for line in missing)
    return failures


def verify_identity_contract() -> list[str]:
    expected = {
        "android-app/app/build.gradle.kts": (
            'namespace = "com.coreline.ai.voice"',
            'applicationId = "com.coreline.ai.voice"',
        ),
        "android-app/app/src/main/res/values/strings.xml": (
            '<string name="app_name">AI R Voice</string>',
        ),
        "android-app/settings.gradle.kts": ('rootProject.name = "ai-r-voice-android"',),
        "pyproject.toml": ('name = "ai-r-voice"', 'packages = ["src/airvoice"]'),
        "web/dashboard/index.html": (
            "<title>AI R Voice · LAN Console</title>",
            "AI R VOICE / CONSOLE V1",
        ),
    }
    failures: list[str] = []
    for relative, markers in expected.items():
        text = (ROOT / relative).read_text(encoding="utf-8")
        for marker in markers:
            if marker not in text:
                failures.append(f"{relative}: missing identity marker: {marker}")

    forbidden_paths = (
        ROOT / "src/thinktank",
        ROOT / "android-app/app/src/main/kotlin/com/thinktank",
        ROOT / "thinktank-recorder.apk",
    )
    for path in forbidden_paths:
        if has_active_path_content(path):
            failures.append(f"legacy active path still exists: {path.relative_to(ROOT)}")
    if not (ROOT / "src/airvoice").is_dir():
        failures.append("missing canonical Python package: src/airvoice")
    if not (ROOT / "ai-r-voice.apk").is_file():
        failures.append("missing canonical QA artifact: ai-r-voice.apk")
    return failures


def main() -> int:
    failures = verify_identity_contract() + verify_legacy_literals()
    if failures:
        print("AI R Voice rebrand verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print("AI R Voice identity and legacy-literal allowlist verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
