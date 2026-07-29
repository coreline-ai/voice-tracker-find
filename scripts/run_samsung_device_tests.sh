#!/usr/bin/env bash

set -euo pipefail

readonly PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ANDROID_PROJECT="${PROJECT_ROOT}/android-app"
readonly TARGET_SERIAL="${THINKTANK_ANDROID_SERIAL:-R3CY40PXCAP}"
readonly TARGET_MANUFACTURER="${THINKTANK_ANDROID_MANUFACTURER:-samsung}"
readonly TARGET_MODEL="${THINKTANK_ANDROID_MODEL:-SM-S931N}"

resolve_adb() {
    local candidate
    for candidate in \
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
        "${ANDROID_HOME:-}/platform-tools/adb"
    do
        if [[ "${candidate}" != "/platform-tools/adb" && -x "${candidate}" ]]; then
            printf '%s\n' "${candidate}"
            return 0
        fi
    done

    command -v adb
}

readonly ADB="$(resolve_adb)"
readonly APP_APK="${ANDROID_PROJECT}/app/build/outputs/apk/debug/app-debug.apk"
readonly TEST_APK="${ANDROID_PROJECT}/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
readonly TEST_RUNNER="com.thinktank.recorder.next.debug.test/androidx.test.runner.AndroidJUnitRunner"

wait_for_target() {
    local attempt state
    for attempt in {1..15}; do
        state="$("${ADB}" -s "${TARGET_SERIAL}" get-state 2>/dev/null || true)"
        if [[ "${state}" == "device" ]]; then
            return 0
        fi
        sleep 2
    done

    echo "ERROR: Samsung QA device ${TARGET_SERIAL} did not reconnect within 30 seconds." >&2
    "${ADB}" devices -l >&2
    return 1
}

install_apk() {
    local apk="$1"
    local attempt output

    for attempt in {1..3}; do
        wait_for_target
        if output="$("${ADB}" -s "${TARGET_SERIAL}" install -r -t "${apk}" 2>&1)"; then
            printf '%s\n' "${output}"
            if grep -q "Success" <<<"${output}"; then
                return 0
            fi
        else
            printf '%s\n' "${output}" >&2
        fi
        echo "ADB install retry ${attempt}/3: ${apk}" >&2
        sleep 2
    done

    echo "ERROR: Failed to install ${apk} on ${TARGET_SERIAL}." >&2
    return 1
}

wait_for_target

readonly ACTUAL_MANUFACTURER="$("${ADB}" -s "${TARGET_SERIAL}" shell getprop ro.product.manufacturer | tr -d '\r')"
readonly ACTUAL_MODEL="$("${ADB}" -s "${TARGET_SERIAL}" shell getprop ro.product.model | tr -d '\r')"
readonly ACTUAL_ANDROID="$("${ADB}" -s "${TARGET_SERIAL}" shell getprop ro.build.version.release | tr -d '\r')"
readonly ACTUAL_API="$("${ADB}" -s "${TARGET_SERIAL}" shell getprop ro.build.version.sdk | tr -d '\r')"
readonly ACTUAL_MANUFACTURER_LOWER="$(printf '%s' "${ACTUAL_MANUFACTURER}" | tr '[:upper:]' '[:lower:]')"
readonly TARGET_MANUFACTURER_LOWER="$(printf '%s' "${TARGET_MANUFACTURER}" | tr '[:upper:]' '[:lower:]')"

if [[ "${ACTUAL_MANUFACTURER_LOWER}" != "${TARGET_MANUFACTURER_LOWER}" ]]; then
    echo "ERROR: ${TARGET_SERIAL} manufacturer is ${ACTUAL_MANUFACTURER}, expected ${TARGET_MANUFACTURER}." >&2
    exit 1
fi

if [[ "${ACTUAL_MODEL}" != "${TARGET_MODEL}" ]]; then
    echo "ERROR: ${TARGET_SERIAL} model is ${ACTUAL_MODEL}, expected ${TARGET_MODEL}." >&2
    exit 1
fi

echo "Pinned Android QA device:"
echo "  serial=${TARGET_SERIAL}"
echo "  manufacturer=${ACTUAL_MANUFACTURER}"
echo "  model=${ACTUAL_MODEL}"
echo "  android=${ACTUAL_ANDROID} api=${ACTUAL_API}"
echo "  adb=${ADB}"

export ANDROID_SERIAL="${TARGET_SERIAL}"

(
    cd "${ANDROID_PROJECT}"
    ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
)

install_apk "${APP_APK}"
install_apk "${TEST_APK}"

instrumentation_output=""
instrumentation_status=1
for attempt in {1..3}; do
    wait_for_target
    set +e
    instrumentation_output="$(
        "${ADB}" -s "${TARGET_SERIAL}" shell am instrument -w -r "${TEST_RUNNER}" 2>&1
    )"
    instrumentation_status=$?
    set -e
    printf '%s\n' "${instrumentation_output}"

    if grep -Eq 'OK \([0-9]+ tests?\)' <<<"${instrumentation_output}"; then
        echo "Samsung physical-device instrumentation tests passed."
        exit 0
    fi

    if ! grep -Eqi "device .* not found|device offline|no devices" <<<"${instrumentation_output}"; then
        if (( instrumentation_status != 0 )); then
            exit "${instrumentation_status}"
        fi
        exit 1
    fi

    echo "ADB instrumentation retry ${attempt}/3 after transport reconnect." >&2
    sleep 2
done

echo "ERROR: Instrumentation did not complete on ${TARGET_SERIAL}." >&2
exit 1
