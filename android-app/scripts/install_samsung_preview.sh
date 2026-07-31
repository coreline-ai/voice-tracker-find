#!/usr/bin/env bash
# Builds and updates the persistent Samsung preview app without clearing app data.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/resolve_java_home.sh"
ADB_DEFAULT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}/platform-tools/adb"
ADB_BIN="${ADB_BIN:-$ADB_DEFAULT}"
APPROVED_SERIAL="R3CY40PXCAP"
APPROVED_MANUFACTURER="samsung"
APPROVED_MODEL="SM-S931N"
PACKAGE_NAME="com.thinktank.recorder.next.qa"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/devicePreview/app-devicePreview.apk"
METADATA_PATH="$ROOT_DIR/app/build/outputs/apk/devicePreview/output-metadata.json"
ALLOW_FIRST_INSTALL=false

usage() {
  cat <<'USAGE'
Usage: install_samsung_preview.sh [--allow-first-install]

Builds com.thinktank.recorder.next.qa and updates only Samsung SM-S931N
(R3CY40PXCAP) with `adb install -r`. The script never uninstalls or clears app data.

--allow-first-install  Required only when the persistent .qa package is not installed yet.
USAGE
}

while (($#)); do
  case "$1" in
    --allow-first-install)
      ALLOW_FIRST_INSTALL=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ! -x "$ADB_BIN" ]]; then
  ADB_BIN="$(command -v adb || true)"
fi
[[ -x "$ADB_BIN" ]] || { echo "adb not found" >&2; exit 2; }

[[ "$("$ADB_BIN" -s "$APPROVED_SERIAL" get-state 2>/dev/null || true)" == "device" ]] || {
  echo "Approved Samsung is not connected: $APPROVED_SERIAL" >&2
  "$ADB_BIN" devices -l >&2
  exit 3
}

manufacturer="$("$ADB_BIN" -s "$APPROVED_SERIAL" shell getprop ro.product.manufacturer |
  tr -d '\r' | tr '[:upper:]' '[:lower:]')"
model="$("$ADB_BIN" -s "$APPROVED_SERIAL" shell getprop ro.product.model | tr -d '\r')"
[[ "$manufacturer" == "$APPROVED_MANUFACTURER" && "$model" == "$APPROVED_MODEL" ]] || {
  echo "Refusing non-approved device: ${manufacturer:-unknown} ${model:-unknown}" >&2
  exit 4
}

cd "$ROOT_DIR"
thinktank_require_java21
./gradlew :app:assembleDevicePreview

[[ -f "$APK_PATH" && -f "$METADATA_PATH" ]] || {
  echo "devicePreview build output is missing" >&2
  exit 5
}
grep -Fq "\"applicationId\": \"$PACKAGE_NAME\"" "$METADATA_PATH" || {
  echo "Refusing APK metadata with an unexpected application ID" >&2
  exit 6
}
grep -Fq '"outputFile": "app-devicePreview.apk"' "$METADATA_PATH" || {
  echo "Refusing unexpected devicePreview output file" >&2
  exit 7
}

installed_path="$(
  "$ADB_BIN" -s "$APPROVED_SERIAL" shell pm path "$PACKAGE_NAME" 2>/dev/null |
    tr -d '\r' || true
)"
if [[ -z "$installed_path" && "$ALLOW_FIRST_INSTALL" != true ]]; then
  echo "$PACKAGE_NAME is not installed." >&2
  echo "A first install requires explicit --allow-first-install approval." >&2
  exit 8
fi

before_models="not-installed"
if [[ -n "$installed_path" ]]; then
  before_models="$(
    "$ADB_BIN" -s "$APPROVED_SERIAL" shell run-as "$PACKAGE_NAME" \
      du -sk files/ondevice/models 2>/dev/null | tr -d '\r' || true
  )"
fi

install_output="$("$ADB_BIN" -s "$APPROVED_SERIAL" install -r -t "$APK_PATH" 2>&1)" || {
  printf '%s\n' "$install_output" >&2
  echo "Update failed. The existing app was preserved; no uninstall or data clear was attempted." >&2
  exit 9
}
printf '%s\n' "$install_output"
grep -Fq "Success" <<<"$install_output" || {
  echo "ADB did not report a successful package update" >&2
  exit 10
}

after_path="$("$ADB_BIN" -s "$APPROVED_SERIAL" shell pm path "$PACKAGE_NAME" | tr -d '\r')"
[[ -n "$after_path" ]] || { echo "Updated preview package is missing" >&2; exit 11; }
after_models="$(
  "$ADB_BIN" -s "$APPROVED_SERIAL" shell run-as "$PACKAGE_NAME" \
    du -sk files/ondevice/models 2>/dev/null | tr -d '\r' || true
)"

"$ADB_BIN" -s "$APPROVED_SERIAL" shell am start -W \
  -n "$PACKAGE_NAME/com.thinktank.recorder.next.MainActivity"

echo "Persistent Samsung preview update completed."
echo "  serial=$APPROVED_SERIAL"
echo "  package=$PACKAGE_NAME"
echo "  models_before=${before_models:-unavailable}"
echo "  models_after=${after_models:-unavailable}"
