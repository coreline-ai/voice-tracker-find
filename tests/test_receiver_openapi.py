"""Executable drift checks for the Receiver V1 OpenAPI contract."""

from __future__ import annotations

from pathlib import Path

import yaml
from openapi_spec_validator import validate

from thinktank.server.contracts import (
    MAX_V1_UPLOAD_BYTES,
    UPLOAD_MEDIA_TYPES,
    UPLOAD_WIRE_STATUSES,
)

ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "docs" / "receiver-api-v1.yaml"
ANDROID_API_TEST = (
    ROOT
    / "android-app/app/src/test/kotlin/com/thinktank/recorder/next"
    / "data/remote/ReceiverApiTest.kt"
)


class UniqueKeyLoader(yaml.SafeLoader):
    """YAML loader that rejects duplicate mapping keys."""


def _construct_unique_mapping(loader, node, deep=False):  # noqa: ANN001, ANN202, FBT002
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise ValueError(f"duplicate YAML key: {key!r}")
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    _construct_unique_mapping,
)


def _spec() -> dict[str, object]:
    parsed = yaml.load(OPENAPI.read_text(encoding="utf-8"), Loader=UniqueKeyLoader)
    assert isinstance(parsed, dict)
    return parsed


def test_receiver_openapi_is_valid_and_has_unique_yaml_keys() -> None:
    validate(_spec())


def test_upload_contract_matches_server_and_android_fixtures() -> None:
    spec = _spec()
    upload = spec["paths"]["/api/v1/upload/{userId}/{filename}"]["put"]
    content_length = next(
        parameter
        for parameter in upload["parameters"]
        if parameter.get("name") == "Content-Length"
    )
    media_types = set(upload["requestBody"]["content"])
    statuses = set(
        spec["components"]["schemas"]["UploadReceipt"]["properties"]["status"]["enum"]
    )

    assert content_length["schema"]["maximum"] == MAX_V1_UPLOAD_BYTES
    assert media_types == set(UPLOAD_MEDIA_TYPES.values())
    assert statuses == UPLOAD_WIRE_STATUSES

    android_fixture = ANDROID_API_TEST.read_text(encoding="utf-8")
    assert '"status":"stored"' not in android_fixture
    for status in UPLOAD_WIRE_STATUSES:
        assert status in android_fixture
