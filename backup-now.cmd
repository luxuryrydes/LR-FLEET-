@echo off
rem Backs up all data (database + uploaded files) while the app is running. Keeps the last 14 backups.
setlocal EnableExtensions
cd /d "%~dp0"
set "ROOT=%~dp0"
set "LOGS=%ROOT%data\logs"
if not exist "%LOGS%" mkdir "%LOGS%"
set "FOLDER=%ROOT%data\backups"
if exist "%ROOT%data\backup-folder.txt" set /p FOLDER=<"%ROOT%data\backup-folder.txt"
curl.exe -s -o nul --fail http://localhost:8080/actuator/health
if errorlevel 1 (
  echo [%date% %time%] backup skipped: the app is not running >> "%LOGS%\backup.log"
  echo The app is not running - start it first, then back up.
  if /i not "%~1"=="auto" pause
  exit /b 1
)
set "WORKER_DATABASE_URL=postgresql://postgres:postgres@localhost:54329/postgres"
cd /d "%ROOT%worker"
"%ROOT%worker\.venv\Scripts\python.exe" -m fleet_worker.backup create --out "%FOLDER%" --files "%ROOT%data\files" --keep 14 >> "%LOGS%\backup.log" 2>&1
if errorlevel 1 (
  echo [%date% %time%] backup FAILED >> "%LOGS%\backup.log"
  echo Backup failed - see data\logs\backup.log
) else (
  echo Backup saved in %FOLDER%
)
if /i not "%~1"=="auto" pause
