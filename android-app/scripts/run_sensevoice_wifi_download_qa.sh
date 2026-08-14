#!/usr/bin/env bash
# Explicit physical proof for the Wi-Fi-only SenseVoice artifact path.
# PD20 is never accepted as a target.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/resolve_java_home.sh"
ADB_BIN="${ADB_BIN:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}/platform-tools/adb}"
APPROVED_SERIAL="R3CY40PXCAP"
APPROVED_MANUFACTURER="samsung"
APPROVED_MODEL="SM-S931N"
TEST_CLASS="com.coreline.ai.voice.ondevice.modelpack.SenseVoiceWifiDownloadDeviceTest"

[[ -x "$ADB_BIN" ]] || { echo "adb not found: $ADB_BIN" >&2; exit 2; }
[[ "$("$ADB_BIN" -s "$APPROVED_SERIAL" get-state 2>/dev/null || true)" == "device" ]] || {
  echo "Approved Samsung is not connected: $APPROVED_SERIAL" >&2
  exit 3
}

manufacturer="$("$ADB_BIN" -s "$APPROVED_SERIAL" shell getprop ro.product.manufacturer | tr -d '\r' | tr '[:upper:]' '[:lower:]')"
model="$("$ADB_BIN" -s "$APPROVED_SERIAL" shell getprop ro.product.model | tr -d '\r')"
[[ "$manufacturer" == "$APPROVED_MANUFACTURER" && "$model" == "$APPROVED_MODEL" ]] || {
  echo "Refusing non-approved device: ${manufacturer:-unknown} ${model:-unknown}" >&2
  exit 4
}

cd "$ROOT_DIR"
export ANDROID_SERIAL="$APPROVED_SERIAL"
airvoice_require_java21

./gradlew :feature-ondevice:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
  -Pandroid.testInstrumentationRunnerArguments.runWifiModelDownload=true
