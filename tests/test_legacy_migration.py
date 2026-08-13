from pathlib import Path

from pytest import MonkeyPatch

from airvoice.legacy_migration import (
    MigrationStatus,
    inspect_legacy_home,
    migrate_legacy_home,
)


def test_missing_legacy_home_needs_no_migration(tmp_path: Path) -> None:
    result = inspect_legacy_home(tmp_path / "legacy", tmp_path / "airvoice")

    assert result.status is MigrationStatus.NOT_NEEDED


def test_existing_target_is_never_merged_or_overwritten(tmp_path: Path) -> None:
    source = tmp_path / "legacy"
    target = tmp_path / "airvoice"
    source.mkdir()
    target.mkdir()
    (source / "pipeline.db").write_bytes(b"legacy")
    (target / "pipeline.db").write_bytes(b"current")

    result = migrate_legacy_home(source, target)

    assert result.status is MigrationStatus.TARGET_CONFLICT
    assert (source / "pipeline.db").read_bytes() == b"legacy"
    assert (target / "pipeline.db").read_bytes() == b"current"


def test_migration_copies_data_and_preserves_source(tmp_path: Path) -> None:
    source = tmp_path / "legacy"
    target = tmp_path / "airvoice"
    source.mkdir()
    (source / "db").mkdir()
    (source / "db" / "pipeline.db").write_bytes(b"database")
    (source / "receiver-token.txt").write_text("private", encoding="utf-8")

    result = migrate_legacy_home(source, target)

    assert result.status is MigrationStatus.COPIED
    assert (target / "db" / "pipeline.db").read_bytes() == b"database"
    assert (target / "receiver-token.txt").read_text(encoding="utf-8") == "private"
    assert (source / "db" / "pipeline.db").read_bytes() == b"database"


def test_empty_target_can_be_replaced_atomically(tmp_path: Path) -> None:
    source = tmp_path / "legacy"
    target = tmp_path / "airvoice"
    source.mkdir()
    target.mkdir()
    (source / "inbox").mkdir()

    result = migrate_legacy_home(source, target)

    assert result.status is MigrationStatus.COPIED
    assert (target / "inbox").is_dir()
    assert source.is_dir()


def test_partial_permission_failure_rolls_back_without_touching_source(
    tmp_path: Path,
    monkeypatch: MonkeyPatch,
) -> None:
    source = tmp_path / "legacy"
    target = tmp_path / "airvoice"
    source.mkdir()
    (source / "pipeline.db").write_bytes(b"legacy")

    def fail_after_partial_copy(
        _source: Path,
        staging: Path,
        **_kwargs: object,
    ) -> None:
        staging.mkdir()
        (staging / "partial.db").write_bytes(b"partial")
        raise PermissionError("simulated permission failure")

    monkeypatch.setattr(
        "airvoice.legacy_migration.shutil.copytree",
        fail_after_partial_copy,
    )

    result = migrate_legacy_home(source, target)

    assert result.status is MigrationStatus.FAILED
    assert result.message.endswith("PermissionError")
    assert (source / "pipeline.db").read_bytes() == b"legacy"
    assert not target.exists()
    assert not list(tmp_path.glob(".airvoice.migration-*"))
