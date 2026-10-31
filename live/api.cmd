@echo off
rem API + embedded database. Restarts after a crash.
title LR Fleet LIVE - API
call "%~dp0env.cmd" || exit /b 1
cd /d "%ROOT%backend"
:loop
call "%~dp0stop-db.cmd"
echo [%date% %time%] API starting >> "%ROOT%data\logs\api.log"
java -Xmx1g -Duser.timezone=Asia/Kolkata -jar target\fleet-backend-1.0.0.jar >> "%ROOT%data\logs\api.log" 2>&1
echo [%date% %time%] API stopped - restarting in 15 s >> "%ROOT%data\logs\api.log"
if exist "%ROOT%data\live.stop" exit /b 0
ping -n 16 127.0.0.1 >nul
goto loop
