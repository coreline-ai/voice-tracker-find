#!/usr/bin/env bash
# Compatibility wrapper. Physical instrumentation is isolated in .deviceTest and never installs
# or clears the persistent .qa preview package.
set -euo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$PROJECT_ROOT/android-app/scripts/run_device_qa.sh" --case core
