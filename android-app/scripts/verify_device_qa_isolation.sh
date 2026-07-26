#!/usr/bin/env bash
# Static guard for the persistent Samsung preview package.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$ROOT_DIR/scripts/run_device_qa.sh"
APP_BUILD="$ROOT_DIR/app/build.gradle.kts"
PROTECTED_PACKAGE="com.thinktank.recorder.next.qa"

fail() {
  echo "Device QA isolation check failed: $*" >&2
  exit 1
}

grep -Fq 'create("devicePreview")' "$APP_BUILD" ||
  fail "devicePreview build type is missing"
grep -Fq 'applicationIdSuffix = ".qa"' "$APP_BUILD" ||
  fail "persistent .qa application ID is missing"
grep -Fq 'create("deviceTest")' "$APP_BUILD" ||
  fail "deviceTest build type is missing"
grep -Fq 'applicationIdSuffix = ".deviceTest"' "$APP_BUILD" ||
  fail "isolated .deviceTest application ID is missing"
grep -Fq 'testBuildType = "deviceTest"' "$APP_BUILD" ||
  fail "instrumentation is not pinned to deviceTest"
grep -Fq ':app:connectedDeviceTestAndroidTest' "$RUNNER" ||
  fail "runner does not target deviceTest instrumentation"

if grep -Eq ':app:connected(DevicePreview|DeviceQa)AndroidTest' "$RUNNER"; then
  fail "runner contains a connected task for the persistent preview package"
fi
if grep -Eq "(pm clear|uninstall|install-multiple).*${PROTECTED_PACKAGE//./\\.}" "$RUNNER"; then
  fail "runner contains a destructive command for $PROTECTED_PACKAGE"
fi

echo "Device QA isolation check passed."
