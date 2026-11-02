@echo off
rem LR Fleet Manager - stops the app safely (the database is closed cleanly). The Cloudflare Tunnel service keeps running:
rem it is shared with Billing Expo.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-local.ps1"
if /i not "%~1"=="auto" (echo. & pause)
