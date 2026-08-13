"""Non-destructive migration helper for the pre-AI R Voice runtime home.

The Android application ID intentionally starts with a fresh sandbox.  The PC
runtime is different: users may already have recordings, databases, tokens and
certificates under the legacy home.  This module copies that directory through
an atomic staging path and never removes the source.
"""

from __future__ import annotations

import argparse
import shutil
import uuid
from dataclasses import dataclass
from enum import Enum
from pathlib import Path


LEGACY_HOME = Path("~/.thinktank").expanduser()
AIRVOICE_HOME = Path("~/.airvoice").expanduser()


class MigrationStatus(str, Enum):
    NOT_NEEDED = "not_needed"
    READY = "ready"
    COPIED = "copied"
    TARGET_CONFLICT = "target_conflict"
    FAILED = "failed"


@dataclass(frozen=True)
class MigrationResult:
    status: MigrationStatus
    source: Path
    target: Path
    message: str


def inspect_legacy_home(
    source: Path = LEGACY_HOME,
    target: Path = AIRVOICE_HOME,
) -> MigrationResult:
    """Return a safe migration decision without reading file contents."""
    source = source.expanduser().resolve()
    target = target.expanduser().resolve()
    if not source.exists():
        return MigrationResult(
            MigrationStatus.NOT_NEEDED,
            source,
            target,
            "이전 런타임 홈이 없어 마이그레이션이 필요하지 않습니다.",
        )
    if not source.is_dir():
        return MigrationResult(
            MigrationStatus.FAILED,
            source,
            target,
            "이전 런타임 홈 경로가 디렉터리가 아닙니다.",
        )
    if target.exists():
        if not target.is_dir() or any(target.iterdir()):
            return MigrationResult(
                MigrationStatus.TARGET_CONFLICT,
                source,
                target,
                "새 런타임 홈에 데이터가 있어 자동 병합하지 않습니다.",
            )
    return MigrationResult(
        MigrationStatus.READY,
        source,
        target,
        "이전 데이터를 새 런타임 홈으로 안전하게 복사할 수 있습니다.",
    )


def migrate_legacy_home(
    source: Path = LEGACY_HOME,
    target: Path = AIRVOICE_HOME,
) -> MigrationResult:
    """Copy a legacy home atomically while preserving the source directory."""
    decision = inspect_legacy_home(source, target)
    if decision.status is not MigrationStatus.READY:
        return decision

    source = decision.source
    target = decision.target
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = target.with_name(f".{target.name}.migration-{uuid.uuid4().hex}")
    target_was_empty = target.exists()
    try:
        shutil.copytree(source, staging, copy_function=shutil.copy2)
        if target_was_empty:
            target.rmdir()
        staging.replace(target)
    except OSError as exc:
        shutil.rmtree(staging, ignore_errors=True)
        if target_was_empty and not target.exists():
            target.mkdir(parents=True, exist_ok=True)
        return MigrationResult(
            MigrationStatus.FAILED,
            source,
            target,
            f"마이그레이션 복사에 실패했습니다: {type(exc).__name__}",
        )

    return MigrationResult(
        MigrationStatus.COPIED,
        source,
        target,
        "이전 데이터를 복사했습니다. 원본은 삭제하지 않았습니다.",
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="AI R Voice PC 런타임 홈 마이그레이션",
    )
    parser.add_argument("--source", type=Path, default=LEGACY_HOME)
    parser.add_argument("--target", type=Path, default=AIRVOICE_HOME)
    parser.add_argument(
        "--apply",
        action="store_true",
        help="검사만 하지 않고 실제 비파괴 복사를 수행합니다.",
    )
    args = parser.parse_args(argv)
    result = (
        migrate_legacy_home(args.source, args.target)
        if args.apply
        else inspect_legacy_home(args.source, args.target)
    )
    print(f"status={result.status.value}")
    print(f"source={result.source}")
    print(f"target={result.target}")
    print(result.message)
    return 0 if result.status is not MigrationStatus.FAILED else 1


if __name__ == "__main__":
    raise SystemExit(main())
