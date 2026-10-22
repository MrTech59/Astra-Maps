#!/usr/bin/env bash
#
# The Phase 2 offline acceptance gate, for CI (and anyone with a shell).
#
# Puts the device into airplane mode, proves the network stack actually went
# down and *stayed* down, then runs the instrumented suite with
# `astra.requireOffline=true` so OfflineTileServingTest fails rather than skips
# if a network is still up.
#
# Why a script file: reactivecircus/android-emulator-runner runs the `script:`
# input one line at a time through /usr/bin/sh, so a multi-line `for` loop in
# the workflow YAML arrives as a syntax error. Committing the gate as a single
# command also means a developer can run exactly what CI runs.
#
# Why wifi is disabled as well as airplane mode: on a freshly booted emulator
# the virtual wifi can come back a minute later, after airplane mode was set —
# observed on the API 35 CI image, not reproducible on a long-running local AVD.
# `cmd wifi set-wifi-enabled disabled` records a *persisted* off state, so the
# boot-time wifi restore cannot undo it. Airplane mode is still enabled because
# that is the condition the acceptance criterion names, and the test asserts the
# network really is gone either way.
#
# The radios are restored on exit, including on failure.
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
ADB=(adb)
if [ -n "$SERIAL" ]; then
    ADB=(adb -s "$SERIAL")
fi

adb_shell() {
    "${ADB[@]}" shell "$@" >/dev/null 2>&1 || true
}

radios_off() {
    adb_shell svc data disable
    if ! "${ADB[@]}" shell cmd wifi set-wifi-enabled disabled >/dev/null 2>&1; then
        adb_shell svc wifi disable
    fi
    adb_shell cmd connectivity airplane-mode enable
}

radios_on() {
    adb_shell cmd connectivity airplane-mode disable
    # Enabling wifi can be refused while the airplane-mode transition is still
    # in flight, so retry until the wifi service agrees it is on.
    for _ in $(seq 1 10); do
        if "${ADB[@]}" shell cmd wifi set-wifi-enabled enabled >/dev/null 2>&1 &&
            "${ADB[@]}" shell cmd wifi status 2>/dev/null | grep -q 'Wifi is enabled'; then
            break
        fi
        adb_shell svc wifi enable
        sleep 1
    done
    adb_shell svc data enable
}

device_is_offline() {
    "${ADB[@]}" shell dumpsys connectivity | grep -q 'Active default network: none'
}

trap radios_on EXIT

echo 'Disabling radios...'
radios_off

# Require the offline state to hold for a while, not just for one sample: the
# wifi stack on a just-booted emulator can re-enable itself after airplane mode
# is set. Re-assert the radios whenever the network reappears.
stable=0
for _ in $(seq 1 90); do
    if device_is_offline; then
        stable=$((stable + 1))
        if [ "$stable" -ge 12 ]; then
            break
        fi
    else
        stable=0
        radios_off
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
