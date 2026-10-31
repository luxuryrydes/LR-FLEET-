@echo off
title LR Fleet LIVE - Website
for %%i in ("%~dp0..") do set "ROOT=%%~fi\"
cd /d "%ROOT%frontend"
:loop
call npm run preview >> "%ROOT%data\logs\web.log" 2>&1
echo [%date% %time%] website stopped - restarting in 10 s >> "%ROOT%data\logs\web.log"
if exist "%ROOT%data\live.stop" exit /b 0
ping -n 11 127.0.0.1 >nul
goto loop
