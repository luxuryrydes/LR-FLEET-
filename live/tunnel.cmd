@echo off
title LR Fleet LIVE - Cloudflare
for %%i in ("%~dp0..") do set "ROOT=%%~fi\"
set /p TUNNEL_TOKEN=<"%ROOT%.tools\cloudflare-tunnel-token.txt"
:loop
"%ROOT%.tools\cloudflared.exe" tunnel --no-autoupdate run --token %TUNNEL_TOKEN% >> "%ROOT%data\logs\tunnel.log" 2>&1
echo [%date% %time%] tunnel stopped - reconnecting in 10 s >> "%ROOT%data\logs\tunnel.log"
if exist "%ROOT%data\live.stop" exit /b 0
ping -n 11 127.0.0.1 >nul
goto loop
