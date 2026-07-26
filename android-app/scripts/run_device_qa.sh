#!/usr/bin/env bash
# Physical-device QA runner.
#
# `preflight` is non-destructive. Connected instrumentation targets only the disposable
# `.deviceTest` package and never installs, clears, or uninstalls the persistent `.qa` preview.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB_DEFAULT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}/platform-tools/adb"
ADB_BIN="${ADB_BIN:-$ADB_DEFAULT}"
# This repository has two devices visible to ADB. PD20 is explicitly excluded from all
# development/QA work; never replace this serial from an environment variable.
APPROVED_SERIAL="R3CY40PXCAP"
APPROVED_MANUFACTURER="samsung"
APPROVED_MODEL="SM-S931N"
SERIAL="$APPROVED_SERIAL"
CASE_NAME="preflight"

usage() {
  cat <<'USAGE'
Usage: run_device_qa.sh [--case preflight|core|native|all]

Target is fixed to the approved Samsung SM-S931N device (R3CY40PXCAP).
PD20 and every other device are rejected before a device command is executed.

  preflight  Record non-destructive device facts only (default).
  core       Run required connected instrumentation in the isolated .deviceTest package.
  native     Run connected instrumentation including optional installed-model smoke tests.
  all        Alias for native.

This script refuses destructive commands against com.thinktank.recorder.next.qa. It never reads
preview recordings/transcripts, toggles radios, deletes preview models, or pulls user content.
USAGE
}

while (($#)); do
  case "$1" in
    --serial)
      [[ "${2:-}" == "$APPROVED_SERIAL" ]] || {
        echo "Only the approved Samsung device is permitted: $APPROVED_SERIAL ($APPROVED_MODEL)" >&2
        exit 2
      }
      shift 2
      ;;
    --case) CASE_NAME="${2:-}"; shift 2 ;;
    --allow-qa-package-reset|--destructive)
      echo "Reset approval is obsolete: instrumentation is restricted to .deviceTest." >&2
      exit 2
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -x "$ADB_BIN" ]] || { echo "adb not found: $ADB_BIN" >&2; exit 2; }
case "$CASE_NAME" in preflight|core|native|all) ;; *) echo "Invalid --case: $CASE_NAME" >&2; exit 2;; esac

CONNECTED=()
while IFS= read -r connected_serial; do
  [[ -n "$connected_serial" ]] && CONNECTED+=("$connected_serial")
done < <("$ADB_BIN" devices | awk '$2 == "device" {print $1}')
if [[ " ${CONNECTED[*]} " != *" $SERIAL "* ]]; then
  echo "Approved Samsung device is not in authorized device state: $SERIAL" >&2
  "$ADB_BIN" devices -l >&2
  exit 3
fi

MANUFACTURER="$("$ADB_BIN" -s "$SERIAL" shell getprop ro.product.manufacturer | tr -d '\r' | tr '[:upper:]' '[:lower:]')"
MODEL="$("$ADB_BIN" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
if [[ "$MANUFACTURER" != "$APPROVED_MANUFACTURER" || "$MODEL" != "$APPROVED_MODEL" ]]; then
  echo "Approved serial did not identify as $APPROVED_MANUFACTURER $APPROVED_MODEL; refusing QA." >&2
  echo "manufacturer=${MANUFACTURER:-unknown}, model=${MODEL:-unknown}" >&2
  exit 4
fi

STAMP="$(date '+%Y%m%d_%H%M%S')"
EVIDENCE_DIR="$ROOT_DIR/build/device-qa/${STAMP}_${SERIAL}"
mkdir -p "$EVIDENCE_DIR"
ADB=("$ADB_BIN" -s "$SERIAL")

write_device_facts() {
  {
    echo "serial=$SERIAL"
    echo "approved_target=$APPROVED_MANUFACTURER $APPROVED_MODEL"
    echo "excluded_target=PD20"
    echo "case=$CASE_NAME"
    echo "preview_package_protected=true"
    echo "collected_at=$(date --iso-8601=seconds 2>/dev/null || date)"
    echo "model=$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
    echo "abi=$("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
    echo "android_release=$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"
    echo "android_sdk=$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "battery=$("${ADB[@]}" shell dumpsys battery | awk '/^  level:|^  status:/{printf "%s%s", sep, $0; sep="; "}' | tr -d '\r')"
    echo "data_fs=$("${ADB[@]}" shell 'df -k /data | tail -1' | tr -d '\r')"
    echo "preview_package=$("${ADB[@]}" shell 'pm path com.thinktank.recorder.next.qa' | tr -d '\r')"
    echo "test_package=$("${ADB[@]}" shell 'pm path com.thinktank.recorder.next.deviceTest' | tr -d '\r')"
  } > "$EVIDENCE_DIR/device.txt"
}

sample_qwen_pss() {
  local package_name="com.thinktank.recorder.next.deviceTest:local_ai_qwen"
  while true; do
    local pids
    pids="$("${ADB[@]}" shell "pidof $package_name" 2>/dev/null | tr -d '\r' || true)"
    for pid in $pids; do
      local pss
      pss="$("${ADB[@]}" shell "dumpsys meminfo $pid | awk '/TOTAL PSS:/ {print \$3; exit}'" 2>/dev/null | tr -d '\r' || true)"
      printf '%s pid=%s totalPssKb=%s\n' "$(date '+%H:%M:%S')" "$pid" "${pss:-unknown}" >> "$EVIDENCE_DIR/qwen-pss.txt"
    done
    sleep 1
  done
}

capture_safe_logcat() {
  # AndroidRuntime errors are diagnostic-only and avoid application INFO output that may contain
  # user-entered transcript text.
  "${ADB[@]}" logcat -d -v threadtime AndroidRuntime:E '*:S' > "$EVIDENCE_DIR/android-runtime-errors.log" || true
}

write_device_facts
printf 'Evidence directory: %s\n' "$EVIDENCE_DIR"

if [[ "$CASE_NAME" == "preflight" ]]; then
  cat "$EVIDENCE_DIR/device.txt"
  exit 0
fi

sample_qwen_pss &
SAMPLER_PID=$!
cleanup() {
  kill "$SAMPLER_PID" 2>/dev/null || true
  wait "$SAMPLER_PID" 2>/dev/null || true
  capture_safe_logcat
  printf 'preview_package_after=%s\n' "$("${ADB[@]}" shell 'pm path com.thinktank.recorder.next.qa' | tr -d '\r')" >> "$EVIDENCE_DIR/device.txt"
  printf 'test_package_after=%s\n' "$("${ADB[@]}" shell 'pm path com.thinktank.recorder.next.deviceTest' | tr -d '\r')" >> "$EVIDENCE_DIR/device.txt"
}
trap cleanup EXIT

cd "$ROOT_DIR"
export ANDROID_SERIAL="$SERIAL"
DEFAULT_JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  export JAVA_HOME="$DEFAULT_JAVA_HOME"
fi

# Model smoke tests use JUnit assumptions and are reported as skipped when the approved local
# model/fixture is not present. The runner never downloads or imports a model on its own.
./gradlew \
  :feature-ondevice:connectedDebugAndroidTest \
  :app:connectedDeviceTestAndroidTest \
  --stacktrace 2>&1 | tee "$EVIDENCE_DIR/gradle-connected.log"
