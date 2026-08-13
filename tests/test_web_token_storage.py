from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "web" / "dashboard" / "token-storage.js"
NODE = shutil.which("node")


@pytest.mark.parametrize(
    ("initial", "expected_token", "expected_keys"),
    [
        ({}, "", []),
        (
            {"thinktank-receiver-dashboard-token": "legacy-value"},
            "legacy-value",
            ["airvoice-receiver-dashboard-token"],
        ),
        (
            {
                "airvoice-receiver-dashboard-token": "current-value",
                "thinktank-receiver-dashboard-token": "legacy-value",
            },
            "current-value",
            [
                "airvoice-receiver-dashboard-token",
                "thinktank-receiver-dashboard-token",
            ],
        ),
    ],
)
def test_session_token_migration_states(
    initial: dict[str, str],
    expected_token: str,
    expected_keys: list[str],
) -> None:
    if NODE is None:
        pytest.skip("node is required for the browser storage contract test")
    harness = f"""
const vm = require('vm');
const fs = require('fs');
const values = new Map(Object.entries({json.dumps(initial)}));
const sessionStorage = {{
  getItem: (key) => values.has(key) ? values.get(key) : null,
  setItem: (key, value) => values.set(key, value),
  removeItem: (key) => values.delete(key),
}};
const context = {{ sessionStorage }};
vm.createContext(context);
vm.runInContext(fs.readFileSync({json.dumps(str(SCRIPT))}, 'utf8'), context);
const token = context.AirVoiceTokenStorage.read();
process.stdout.write(JSON.stringify({{ token, keys: [...values.keys()].sort() }}));
"""

    completed = subprocess.run(  # noqa: S603
        [NODE, "-e", harness],
        check=True,
        capture_output=True,
        text=True,
    )
    result = json.loads(completed.stdout)

    assert result["token"] == expected_token
    assert result["keys"] == sorted(expected_keys)
