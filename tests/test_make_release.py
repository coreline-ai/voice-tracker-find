import hashlib
import zipfile
from pathlib import Path

from scripts.make_release import build_release


def test_release_zip_is_allowlisted_and_contains_manifest(tmp_path: Path) -> None:
    apk = tmp_path / "input.apk"
    output = tmp_path / "ai-r-voice-release.zip"
    apk.write_bytes(b"test-apk")

    build_release(output, apk)

    with zipfile.ZipFile(output) as archive:
        names = archive.namelist()
        assert "ai-r-voice.apk" in names
        assert "README.md" in names
        assert "SETUP.md" in names
        assert "DISTRIBUTE.md" in names
        assert "RELEASE-MANIFEST.sha256" in names
        assert ".env" not in names
        assert "users.json" not in names
        manifest = archive.read("RELEASE-MANIFEST.sha256").decode()
        assert f"{hashlib.sha256(b'test-apk').hexdigest()}  ai-r-voice.apk" in manifest
