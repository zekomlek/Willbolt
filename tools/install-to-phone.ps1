# Installs the FULL edition of Willbolt on a USB-connected phone and gives it the permissions that Android only lets a
# computer grant. (The Play edition has no such permissions; it installs from the Play Store like any app.)
#
# Run it from the project folder (the one that contains app\, tools\ and README.md):
#     powershell -ExecutionPolicy Bypass -File .\tools\install-to-phone.ps1
# or from the folder above it:
#     powershell -ExecutionPolicy Bypass -File .\willbolt\tools\install-to-phone.ps1
# or just double-click install-to-phone.bat.
#
# It never installs an out-of-date build: if anything under app\src (or the version file) is newer than the APK, it builds
# the APK first with tools\build.ps1 (a minute or two). Pass -NoBuild to install whatever APK is there, or -Apk <file> to
# install a particular file. It says which version it is installing and which one the phone has afterwards.
#
# Prepare the phone once:  Settings > About phone > tap "Build number" 7 times (this unlocks "Developer options"; nothing else changes),
#                          then Settings > System & updates > Developer options > USB debugging ON,
#                          plug in the cable and tap "Allow" on the phone.
# The script waits for the phone, so you can start it first and do the phone steps afterwards.
# Read this file before running it - every step is a plain adb command.
param(
    [string]$Apk = "$PSScriptRoot\..\app\build\outputs\apk\full\release\app-full-release.apk",
    [switch]$NoBuild
)
. "$PSScriptRoot\env.ps1"

$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$pkg = 'app.willbolt'
$root = (Resolve-Path "$PSScriptRoot\..").Path

if (-not (Test-Path $adb)) {
    Write-Host "adb not found at: $adb"
    Write-Host 'Expected the Android SDK in %USERPROFILE%\android-dev\android-sdk (set ANDROID_HOME if yours is elsewhere).'
    exit 1
}

# ---- make sure the APK is the latest one ---------------------------------------------------------------------------
function Test-ApkStale {
    if (-not (Test-Path $Apk)) { return $true }
    $built = (Get-Item $Apk).LastWriteTime
    $newest = Get-ChildItem -Path "$root\app\src", "$root\app\build.gradle.kts", "$root\PRIVACY.md", "$root\THIRD_PARTY_NOTICES.md" -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    return ($null -ne $newest -and $newest.LastWriteTime -gt $built)
}

if (-not $NoBuild -and -not $PSBoundParameters.ContainsKey('Apk') -and (Test-ApkStale)) {
    Write-Host 'The APK is older than the source code - building it first (a minute or two)...'
    try {
        & "$PSScriptRoot\build.ps1"
    } catch {
        Write-Host "The build failed: $_"
        exit 1
    }
}

if (-not (Test-Path $Apk)) {
    Write-Host "APK not found: $Apk"
    Write-Host 'Build it first:  powershell -ExecutionPolicy Bypass -File .\tools\build.ps1'
    exit 1
}

# Which version is in the APK (read from the APK itself, not from its name).
$want = ''
$aapt = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Filter aapt2.exe -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if ($aapt) {
    $badging = & $aapt.FullName dump badging $Apk | Select-String -Pattern '^package: ' | Select-Object -First 1
    if ($badging -and ($badging.Line -match "versionCode='(\d+)' versionName='([^']+)'")) { $want = "$($Matches[2]) (code $($Matches[1]))" }
}
Write-Host ("Installing Willbolt " + $(if ($want) { $want } else { '(version unknown)' }) + " from " + (Resolve-Path $Apk).Path)

& $adb start-server | Out-Null

function Get-PhoneState {
    $rows = @(& $adb devices | Select-String -Pattern '\t(device|unauthorized|offline)\s*$')
    if ($rows.Count -gt 1) { return 'many' }
    if ($rows.Count -eq 1) { return $rows[0].Matches[0].Groups[1].Value }
    return 'none'
}

$state = Get-PhoneState
$shown = ''
$deadline = (Get-Date).AddMinutes(3)
while ($state -ne 'device' -and (Get-Date) -lt $deadline) {
    if ($state -ne $shown) {
        $shown = $state
        switch ($state) {
            'none'         { Write-Host 'Waiting for the phone... plug it in and switch on USB debugging (Settings > System & updates > Developer options > USB debugging).' }
            'unauthorized' { Write-Host 'Phone found. Unlock it and tap "Allow" on the "Allow USB debugging?" prompt (tick "Always allow from this computer").' }
            'offline'      { Write-Host 'The phone shows as "offline" - unplug the cable, plug it back in and unlock the phone.' }
            'many'         { Write-Host 'More than one device is connected - unplug the others.' }
        }
    }
    Start-Sleep -Seconds 2
    $state = Get-PhoneState
}
if ($state -ne 'device') {
    Write-Host 'Gave up waiting. Run this script again once the phone is ready.'
    exit 1
}

Write-Host '1/5 Installing the app (accept any install prompt on the phone)...'
& $adb install -r $Apk
if ($LASTEXITCODE -ne 0) { Write-Host 'Install failed.'; exit 1 }

# Say what the phone has now, so an old version cannot slip through unnoticed.
$have = ''
$line = & $adb shell dumpsys package $pkg | Select-String -Pattern 'versionName=(\S+)' | Select-Object -First 1
if ($line) { $have = $line.Matches[0].Groups[1].Value }
Write-Host "    The phone now has Willbolt $have."
if ($want -and $have -and -not $want.StartsWith($have + ' ')) {
    Write-Host "    WARNING: the APK was $want but the phone reports $have - run this script again."
}

Write-Host '2/5 Self-repair permission (lets Willbolt switch its own accessibility service back on)...'
& $adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS

Write-Host '3/5 Exempt from "remove permissions if app is unused"...'
& $adb shell cmd appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore

Write-Host '4/5 Allow background running + exempt from battery optimisation...'
& $adb shell cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow
& $adb shell cmd appops set $pkg RUN_IN_BACKGROUND allow
& $adb shell dumpsys deviceidle whitelist +$pkg

Write-Host '5/5 Opening the app...'
& $adb shell am start -n "$pkg/.ui.MainActivity"

Write-Host ''
Write-Host 'Done. On the phone: open Willbolt, tap "Turn on" on the Home screen, then Settings (top right) > Setup.'
Write-Host 'Do the phone-maker battery step in Setup (Honor: App launch, all three switches on) BEFORE you lock anything - otherwise the phone stops Willbolt when the screen is off, and nothing is blocked until you open it again.'
Write-Host 'Uninstall protection is off until you set it up: on Home, tap the padlock next to "Uninstall protection" (or switch it on in the sheet of any lock you make).'
