@echo off
rem Double-click this to run install-to-phone.ps1 (a .ps1 opens in Notepad when double-clicked). The window stays open at the end.
rem The script builds the app first if the APK is older than the source code, says which version it installs, and checks
rem afterwards which version the phone has - so it never quietly installs an old build.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-to-phone.ps1"
echo.
pause
