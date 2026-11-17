# Dot-source this to get a shell that can build Willbolt:   . .\tools\env.ps1
# Defaults match where the tools were installed on this PC; override with WILLBOLT_TOOLS / ANDROID_HOME if yours differ.
$toolRoot = if ($env:WILLBOLT_TOOLS) { $env:WILLBOLT_TOOLS } else { Join-Path $env:USERPROFILE 'android-dev' }
$env:JAVA_HOME = Join-Path $toolRoot 'jdk-17'
# NOTE: keep the SDK OUTSIDE %LOCALAPPDATA%. The Claude desktop app is a packaged app, so anything its tools
# write under AppData is invisible to your own terminal.
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $toolRoot 'android-sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
