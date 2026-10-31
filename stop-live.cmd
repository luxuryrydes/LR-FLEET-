@echo off
rem Stops the live app: closes the "LR Fleet LIVE" windows (the API shuts its database down cleanly).
setlocal EnableExtensions
cd /d "%~dp0"
set "ROOT=%~dp0"
if not exist "%ROOT%data" mkdir "%ROOT%data"
rem The flag tells each restart loop to stop instead of starting its part again.
> "%ROOT%data\live.stop" echo stop
rem Tunnel: this app's cloudflared only. Website: whatever serves port 4173 (npm renames its window).
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='cloudflared.exe'\" | Where-Object { $_.ExecutablePath -eq '%ROOT%.tools\cloudflared.exe' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" >nul 2>&1
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":4173 .*LISTENING"') do taskkill /pid %%p /t /f >nul 2>&1
taskkill /fi "WINDOWTITLE eq LR Fleet LIVE - Import worker*" /t /f >nul 2>&1
rem API: closed gently so it shuts its database down cleanly.
taskkill /fi "WINDOWTITLE eq LR Fleet LIVE - API*" /t >nul 2>&1
ping -n 10 127.0.0.1 >nul
taskkill /fi "WINDOWTITLE eq LR Fleet LIVE*" /t /f >nul 2>&1
call "%ROOT%live\stop-db.cmd"
echo [%date% %time%] stopped >> "%ROOT%data\logs\live.log"
echo LR Fleet Manager stopped.
if /i not "%~1"=="auto" pause
