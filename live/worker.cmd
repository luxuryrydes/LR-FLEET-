@echo off
title LR Fleet LIVE - Import worker
for %%i in ("%~dp0..") do set "ROOT=%%~fi\"
set "WORKER_DATABASE_URL=postgresql://postgres:postgres@localhost:54329/postgres"
set "STORAGE_TYPE=local"
set "STORAGE_LOCAL_DIR=%ROOT%data\files"
cd /d "%ROOT%worker"
:loop
.venv\Scripts\python -m fleet_worker.main >> "%ROOT%data\logs\worker.log" 2>&1
echo [%date% %time%] worker stopped - restarting in 15 s >> "%ROOT%data\logs\worker.log"
if exist "%ROOT%data\live.stop" exit /b 0
ping -n 16 127.0.0.1 >nul
goto loop
