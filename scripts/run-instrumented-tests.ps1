<#
.SYNOPSIS
    Run the Astra Maps instrumented tests on a connected Android device.

.DESCRIPTION
    AGP 8.12 removed the legacy instrumentation runner: the Unified Test
    Platform is now the only runner, and it cannot be turned off.

    UTP writes a device-profile file whose name is derived from the raw device
    serial. A phone attached over `adb connect host:port` has a serial such as
    `192.168.1.151:34013`, and `:` is not a legal character in a Windows file
    name, so the profile write fails with
    `FileNotFoundException: Invalid file path` and the run aborts before a
    single test is discovered.

    This script sidesteps the harness entirely: it installs the app and test
    APKs and drives `adb shell am instrument` directly, which works over USB,
    over TCP and over wireless debugging alike, on Windows, Linux and macOS.

.PARAMETER Serial
    Device serial to target. Defaults to the single connected device.

.PARAMETER Class
    Optional instrumentation class filter, for example
    `com.astramaps.app.data.local.Fts4AvailabilityTest`. Defaults to every
    test in the test APK.

.EXAMPLE
    ./scripts/run-instrumented-tests.ps1
    ./scripts/run-instrumented-tests.ps1 -Serial 192.168.1.151:34013
#>
[CmdletBinding()]
param(
    [string] $Serial = '',
    [string] $Class = '',
    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    if (-not (Test-Path $adb)) {
        $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    }
    if (-not $adb) { throw 'adb not found. Set ANDROID_HOME or put adb on PATH.' }

    function Invoke-Adb {
        param([string[]] $AdbArgs)
        $output = & $adb @AdbArgs 2>&1
        return @($output | ForEach-Object { $_.ToString() })
    }

    # --- pick a device -------------------------------------------------------
    if (-not $Serial) {
        $devices = @(Invoke-Adb -AdbArgs @('devices') | Where-Object { $_ -match '\tdevice$' } |
            ForEach-Object { ($_ -split '\s+')[0] })
        if ($devices.Count -eq 0) { throw 'No device is connected. Attach a phone or start an emulator.' }
        if ($devices.Count -gt 1) {
            Write-Host "Multiple devices connected; pass -Serial to choose one of:" -ForegroundColor Yellow
            $devices | ForEach-Object { Write-Host "  $_" }
            throw 'Ambiguous device selection.'
        }
        $Serial = $devices[0]
    }
    Write-Host "Device: $Serial" -ForegroundColor Cyan

    $props = @(Invoke-Adb -AdbArgs @('-s', $Serial, 'shell', 'getprop', 'ro.build.version.release'))
    $sdk = @(Invoke-Adb -AdbArgs @('-s', $Serial, 'shell', 'getprop', 'ro.build.version.sdk'))
    Write-Host ("Android {0} (API {1})" -f $props[0], $sdk[0]) -ForegroundColor Cyan

    # --- build ---------------------------------------------------------------
    if (-not $SkipBuild) {
        Write-Host 'Building APKs...' -ForegroundColor Cyan
        & "$repoRoot\gradlew.bat" --no-daemon ':app:assembleDebug' ':app:assembleDebugAndroidTest'
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
    }

    $appApk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $testApk = Join-Path $repoRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    foreach ($apk in @($appApk, $testApk)) {
        if (-not (Test-Path $apk)) { throw "APK not found: $apk" }
    }

    # --- install -------------------------------------------------------------
    Write-Host 'Installing...' -ForegroundColor Cyan
    foreach ($apk in @($appApk, $testApk)) {
        $result = @(Invoke-Adb -AdbArgs @('-s', $Serial, 'install', '-r', '-t', $apk))
        if ($result -notcontains 'Success') {
            $result | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
            throw "Failed to install $apk"
        }
    }

    # --- run -----------------------------------------------------------------
    $runner = 'com.astramaps.app.test/androidx.test.runner.AndroidJUnitRunner'
    $adbArgs = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r')
    if ($Class) { $adbArgs += @('-e', 'class', $Class) }
    $adbArgs += $runner

    Write-Host 'Running instrumented tests...' -ForegroundColor Cyan
    $raw = @(Invoke-Adb -AdbArgs $adbArgs)
    $text = $raw -join "`n"

    # `am instrument -r` emits INSTRUMENTATION_STATUS_* records. A failure
    # shows up as a stack trace under INSTRUMENTATION_STATUS: stack=, and the
    # run ends with either `OK (n tests)` or `FAILURES!!!`.
    $testsRun = 0
    $failures = 0
    $ok = $false
    if ($text -match 'OK \((\d+) tests?\)') {
        $testsRun = [int]$Matches[1]
        $ok = $true
    }
    if ($text -match 'Tests run:\s*(\d+),\s*Failures:\s*(\d+)') {
        $testsRun = [int]$Matches[1]
        $failures = [int]$Matches[2]
        $ok = $false
    }
    # `am instrument` always reports INSTRUMENTATION_CODE: -1 for a run that
    # completed, whether it passed or failed, so it carries no signal here.

    # Surface any stack traces the runner reported.
    $stack = @($raw | Where-Object { $_ -match 'INSTRUMENTATION_STATUS: stack=' } | ForEach-Object {
        ($_ -replace '^.*INSTRUMENTATION_STATUS: stack=', '').Trim()
    })
    if ($stack.Count -gt 0) {
        Write-Host ''
        Write-Host '--- failures ---' -ForegroundColor Red
        $stack | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    }

    Write-Host ''
    Write-Host "Tests run: $testsRun, failures: $failures" -ForegroundColor Cyan

    # Persist a JUnit XML alongside the Gradle output so the result is
    # inspectable after the fact, exactly like an AGP run would leave.
    $outDir = Join-Path $repoRoot 'app\build\outputs\androidTest-results\manual'
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    $xml = Join-Path $outDir "TEST-$($Serial -replace '[^A-Za-z0-9._-]', '_').xml"
    $suite = 'com.astramaps.app'
    $xmlBody = "<?xml version='1.0' encoding='UTF-8' ?>`n<testsuite tests=`"$testsRun`" failures=`"$failures`" errors=`"0`" skipped=`"0`" time=`"0`" hostname=`"$Serial`">`n  <properties>`n    <property name=`"device`" value=`"$($Serial -replace '[^A-Za-z0-9._-]', '_')`" />`n  </properties>`n  <system-out><![CDATA[$text]]></system-out>`n</testsuite>`n"
    Set-Content -Path $xml -Value $xmlBody -Encoding UTF8
    Write-Host "Report: $xml"

    if ($failures -gt 0 -or -not $ok) {
        Write-Host ''
        Write-Host 'INSTRUMENTED TESTS FAILED' -ForegroundColor Red
        exit 1
    }
    Write-Host ''
    Write-Host 'INSTRUMENTED TESTS PASSED' -ForegroundColor Green
    exit 0
}
finally {
    Pop-Location
}
