@echo off
rem LR Fleet Manager - one-click start. Double-click this file; all the work is done by scripts\run-local.ps1:
rem it installs what is missing (first run), starts the API + database, import worker and live website in this window,
rem checks the Cloudflare Tunnel service and confirms https://fleet.lrbilling.in shows the app.
rem Stop: stop-local.cmd, Ctrl+C, or close this window (the database closes safely each way).
title LR Fleet Manager
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run-local.ps1" %*
if errorlevel 1 (
  echo.
  echo LR Fleet Manager could not start. Read the message above, or data\logs\run-local.log.
  if /i not "%~1"=="-Auto" pause
)
