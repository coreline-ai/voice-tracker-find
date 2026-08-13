#!/usr/bin/env bash
# Static guard for the persistent Samsung preview package.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$ROOT_DIR/scripts/run_device_qa.sh"
INSTALLER="$ROOT_DIR/scripts/install_samsung_preview.sh"
LEGACY_RUNNER="$ROOT_DIR/../scripts/run_samsung_device_tests.sh"
JAVA_RESOLVER="$ROOT_DIR/scripts/resolve_java_home.sh"
APP_BUILD="$ROOT_DIR/app/build.gradle.kts"
PROTECTED_PACKAGE="com.coreline.ai.voice.qa"

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
grep -Fq 'PACKAGE_NAME="com.coreline.ai.voice.qa"' "$INSTALLER" ||
  fail "persistent preview installer package is not pinned"
grep -Fq 'APPROVED_SERIAL="R3CY40PXCAP"' "$INSTALLER" ||
  fail "persistent preview installer serial is not pinned"
grep -Fq 'install -r -t "$APK_PATH"' "$INSTALLER" ||
  fail "persistent preview installer does not use data-preserving replacement"
grep -Fq 'run_device_qa.sh" --case core' "$LEGACY_RUNNER" ||
  fail "legacy Samsung runner does not delegate to isolated deviceTest QA"
grep -Fq 'airvoice_require_java21' "$INSTALLER" ||
  fail "persistent preview installer is not pinned to Java 21+"
grep -Fq 'jdk-21.0.11+10/Contents/Home' "$JAVA_RESOLVER" ||
  fail "Java 21 resolver does not include the managed Temurin runtime"

if grep -Eq ':app:connected(DevicePreview|DeviceQa)AndroidTest' "$RUNNER"; then
  fail "runner contains a connected task for the persistent preview package"
fi
for protected_script in "$RUNNER" "$INSTALLER" "$LEGACY_RUNNER"; do
  if grep -Eq "(pm clear|uninstall|install-multiple).*${PROTECTED_PACKAGE//./\\.}" "$protected_script"; then
    fail "$protected_script contains a destructive command for $PROTECTED_PACKAGE"
  fi
done

echo "Device QA isolation check passed."
