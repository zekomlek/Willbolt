# Builds Willbolt, signed.
#   .\tools\build.ps1                    -> the full edition APK (GitHub / sideloading): app\build\outputs\apk\full\release\app-full-release.apk
#   .\tools\build.ps1 -Edition play      -> the Play edition APK, and with -Bundle the .aab to upload to Google Play
#   .\tools\build.ps1 -Variant debug     -> a debug build (debuggable: anyone with a USB cable can read its files, locks included)
param(
    [ValidateSet('full', 'play')][string]$Edition = 'full',
    [ValidateSet('release', 'debug')][string]$Variant = 'release',
    [switch]$Bundle
)
. "$PSScriptRoot\env.ps1"
Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    $name = $Edition + $Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1)
    $task = if ($Bundle) { 'bundle' + $name.Substring(0, 1).ToUpper() + $name.Substring(1) } else { 'assemble' + $name.Substring(0, 1).ToUpper() + $name.Substring(1) }
    & .\gradlew.bat $task --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
    $found = @(Get-ChildItem "app\build\outputs\apk\$Edition\$Variant\*.apk" -ErrorAction SilentlyContinue) +
             @(Get-ChildItem "app\build\outputs\bundle\$name\*.aab" -ErrorAction SilentlyContinue)
    $found | ForEach-Object { Write-Host ("Built: " + $_.FullName + "  (" + [math]::Round($_.Length / 1MB, 1) + " MB)") }
} finally {
    Pop-Location
}
