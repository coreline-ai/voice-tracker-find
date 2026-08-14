from pathlib import Path

from scripts.verify_rebrand import has_active_path_content


def test_ignored_cache_only_legacy_path_is_not_active(tmp_path: Path) -> None:
    legacy_dir = "think" + "tank"
    cache = tmp_path / "src" / legacy_dir / "__pycache__"
    cache.mkdir(parents=True)
    (cache / "module.cpython-314.pyc").write_bytes(b"cache")

    assert has_active_path_content(tmp_path / "src" / legacy_dir) is False


def test_legacy_source_file_still_fails_active_path_gate(tmp_path: Path) -> None:
    legacy_dir = "think" + "tank"
    source = tmp_path / "src" / legacy_dir / "legacy.py"
    source.parent.mkdir(parents=True)
    source.write_text("legacy = True\n", encoding="utf-8")

    assert has_active_path_content(tmp_path / "src" / legacy_dir) is True
