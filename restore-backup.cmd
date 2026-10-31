@echo off
rem Replaces ALL current data with a backup. The app must be running. Restart it afterwards.
setlocal EnableExtensions
cd /d "%~dp0"
set "ROOT=%~dp0"
curl.exe -s -o nul --fail http://localhost:8080/actuator/health || (echo Start the app first ^(start-live.cmd or run-local.cmd^). & pause & exit /b 1)
echo Drag the backup file ^(lr-fleet-backup-....zip^) into this window and press Enter:
set "FILE="
set /p FILE=Backup file:
set "FILE=%FILE:"=%"
if not exist "%FILE%" (echo File not found. & pause & exit /b 1)
echo.
echo ALL current data will be replaced by this backup. Type YES to continue.
set "OK="
set /p OK=Confirm:
if not "%OK%"=="YES" (echo Cancelled. & pause & exit /b 1)
set "WORKER_DATABASE_URL=postgresql://postgres:postgres@localhost:54329/postgres"
cd /d "%ROOT%worker"
"%ROOT%worker\.venv\Scripts\python.exe" -m fleet_worker.backup restore --file "%FILE%" --files "%ROOT%data\files" --yes
echo.
echo Now restart the app: stop-live.cmd then start-live.cmd ^(or close and reopen run-local.cmd^).
pause
