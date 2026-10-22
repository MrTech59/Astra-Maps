#!/usr/bin/env bash
#
# The Phase 2 offline acceptance gate, for CI (and anyone with a shell).
#
# Puts the emulator into airplane mode, proves the network stack actually went
# down, then runs the instrumented suite with `astra.requireOffline=true` so
# OfflineTileServingTest fails rather than skips if a network is still up.
#
# Why a script file: reactivecircus/android-emulator-runner runs the `script:`
# input one line at a time through /usr/bin/sh, so a multi-line `for` loop in
# the workflow YAML arrives as a syntax error. Committing the gate as a single
# command also means a developer can run exactly what CI runs.
#
# Airplane mode is restored afterwards, including on failure.
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
if [ -n "$SERIAL" ]; then
    ADB=(adb -s "$SERIAL")
fi

restore_radios() {
    "${ADB[@]}" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
}
trap restore_radios EXIT

echo 'Enabling airplane mode...'
"${ADB[@]}" shell cmd connectivity airplane-mode enable

# The command returns immediately; the network stack needs a moment to tear
# everything down, so poll instead of racing it.
for _ in $(seq 1 30); do
    if "${ADB[@]}" shell dumpsys connectivity | grep -q 'Active default network: none'; then
        break
    fi
    sleep 1
done

state="$("${ADB[@]}" shell dumpsys connectivity | grep -m1 'Active default network' || true)"
case "$state" in
    *'Active default network: none'*) ;;
    *)
        echo "::error title=airplane mode::the device still has a network: ${state:-<no state>}"
        exit 1
        ;;
esac
echo "Device is offline: $state"

./gradlew --no-daemon :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.astra.requireOffline=true
