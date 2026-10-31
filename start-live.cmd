@echo off
rem ==========================================================================
rem  LR Fleet Manager - LIVE on this PC, published through your Cloudflare Tunnel
rem  Starts (minimised, each restarts itself if it stops):
rem    API + database, import worker, website (built), Cloudflare connection.
rem  Before the first run: run-local.cmd once (installs everything), change the
rem  admin password, and save your tunnel token - see docs\GO-LIVE.md
rem  stop-live.cmd stops everything. install-autostart.cmd starts it at sign-in.
rem  Do not run run-local.cmd at the same time (same ports and database).
rem ==========================================================================
setlocal EnableExtensions
cd /d "%~dp0"
set "ROOT=%~dp0"
set "AUTO=%~1"
set "LOGS=%ROOT%data\logs"
if not exist "%LOGS%" mkdir "%LOGS%"
call "%ROOT%live\env.cmd" || goto :fail
if exist "%ROOT%data\live.stop" del "%ROOT%data\live.stop"

if not exist "%ROOT%.tools\cloudflare-tunnel-token.txt" (
  echo No tunnel token yet. Save it in .tools\cloudflare-tunnel-token.txt - see docs\GO-LIVE.md
  goto :fail
)
if not exist "%ROOT%.tools\cloudflared.exe" (
  echo Downloading Cloudflare Tunnel ^(cloudflared^) from github.com/cloudflare...
  curl.exe -L --fail -o "%ROOT%.tools\cloudflared.exe" "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe" || goto :fail
)

curl.exe -s -o nul --fail http://localhost:8080/actuator/health
if not errorlevel 1 (
  echo The app is already running. Use stop-live.cmd first to restart it.
  goto :fail
)

echo [%date% %time%] Building the API... >> "%LOGS%\live.log"
echo Building the API ^(about a minute^)...
call mvn -q -f "%ROOT%backend\pom.xml" -Plocal -DskipTests package > "%LOGS%\build-api.log" 2>&1 || (echo API build failed - see data\logs\build-api.log & goto :fail)
echo Building the website...
set "VITE_API_BASE_URL="
call npm --prefix "%ROOT%frontend" run build > "%LOGS%\build-web.log" 2>&1 || (echo Website build failed - see data\logs\build-web.log & goto :fail)

start "LR Fleet LIVE - API" /min cmd /c ""%ROOT%live\api.cmd""
echo Starting the API and database...
set /a N=0
:waitapi
ping -n 4 127.0.0.1 >nul
set /a N+=1
curl.exe -s -o nul --fail http://localhost:8080/actuator/health && goto :apiup
if %N% GEQ 100 (echo The API did not start - see data\logs\api.log & goto :fail)
goto :waitapi
:apiup
start "LR Fleet LIVE - Import worker" /min cmd /c ""%ROOT%live\worker.cmd""
start "LR Fleet LIVE - Website" /min cmd /c ""%ROOT%live\web.cmd""

rem ---- never publish while an admin still has a published default password
curl.exe -s http://localhost:8080/api/system/share-check > "%TEMP%\lrfleet-share-check.txt"
findstr /c:"\"defaultAdminPassword\":false" "%TEMP%\lrfleet-share-check.txt" >nul
if errorlevel 1 (
  echo [%date% %time%] NOT published: an admin still uses a default password >> "%LOGS%\live.log"
  echo.
  echo  The app runs on this PC ^(http://localhost:4173^) but is NOT published:
  echo  an admin account still uses a default password such as Admin@12345.
  echo  Change it under Users ^& roles, then run stop-live.cmd and start-live.cmd again.
  goto :fail
)

start "LR Fleet LIVE - Cloudflare" /min cmd /c ""%ROOT%live\tunnel.cmd""
echo [%date% %time%] Live: API, worker, website and Cloudflare started >> "%LOGS%\live.log"
echo.
echo  LR Fleet Manager is LIVE on your Cloudflare address.
echo  On this PC: http://localhost:4173   Logs: data\logs
echo  The minimised "LR Fleet LIVE" windows must stay open. stop-live.cmd stops them.
if /i not "%AUTO%"=="auto" pause
exit /b 0

:fail
echo [%date% %time%] start-live failed >> "%LOGS%\live.log"
if /i not "%AUTO%"=="auto" pause
exit /b 1
