@echo off
rem ==========================================================================
rem  LR Fleet Manager - share the running app with colleagues (Cloudflare Tunnel)
rem  1. Start run-local.cmd first and keep its windows open.
rem  2. Double-click this file. It prints a https:// link; send it to colleagues.
rem  Colleagues sign in with their own user (create them under Users & roles).
rem  The link works while this PC is on and these windows stay open.
rem
rem  Permanent address on your own domain (optional): create a tunnel in the
rem  Cloudflare dashboard (Zero Trust > Networks > Tunnels), add a public
rem  hostname pointing to http://localhost:4173, and save its token in
rem  .tools\cloudflare-tunnel-token.txt  - see docs\SHARING.md
rem ==========================================================================
setlocal EnableExtensions
cd /d "%~dp0"
set "ROOT=%~dp0"
if not exist ".tools" mkdir ".tools"

curl.exe -s -o nul --fail http://localhost:8080/actuator/health
if errorlevel 1 (
  echo The app is not running. Start run-local.cmd first, wait until the website opens, then run this file again.
  pause
  exit /b 1
)

rem ---- never put the app online while an admin still has a published password
curl.exe -s http://localhost:8080/api/system/share-check > "%TEMP%\lrfleet-share-check.txt"
findstr /c:"\"defaultAdminPassword\":false" "%TEMP%\lrfleet-share-check.txt" >nul
if errorlevel 1 (
  echo.
  echo  STOP: an admin account still uses a default password ^(for example Admin@12345^).
  echo  Anyone on the internet could sign in with it. Open http://localhost:5173, go to
  echo  Users ^& roles, edit the admin user, set a new strong password, then run this file again.
  echo.
  pause
  exit /b 1
)

if not exist ".tools\cloudflared.exe" (
  echo Downloading Cloudflare Tunnel ^(cloudflared^) from github.com/cloudflare...
  curl.exe -L --fail -o ".tools\cloudflared.exe" "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe" || goto :dlfail
)

echo Building the website for sharing...
set "VITE_API_BASE_URL="
call npm --prefix frontend run build || goto :fail

start "LR Fleet - Shared website" cmd /k "cd /d "%ROOT%frontend" && npm run preview"
echo Waiting for the shared website on http://localhost:4173 ...
:wait
ping -n 3 127.0.0.1 >nul
curl.exe -s -o nul http://localhost:4173/ || goto :wait

echo.
if exist ".tools\cloudflare-tunnel-token.txt" goto :named

echo  Opening a free temporary link. Look below for a line like
echo      https://something-random.trycloudflare.com
echo  and send that link to your colleagues. It changes every time you run this file.
echo  Keep this window open. Close it to stop sharing.
echo.
".tools\cloudflared.exe" tunnel --no-autoupdate --url http://localhost:4173
exit /b 0

:named
set /p TUNNEL_TOKEN=<".tools\cloudflare-tunnel-token.txt"
echo  Connecting your permanent Cloudflare address. Colleagues use the hostname you set in the Cloudflare dashboard.
echo  Keep this window open. Close it to stop sharing.
echo.
".tools\cloudflared.exe" tunnel --no-autoupdate run --token %TUNNEL_TOKEN%
exit /b 0

:dlfail
echo Download failed. Check the internet connection and run this file again.
pause
exit /b 1

:fail
echo Something failed - see the messages above.
pause
exit /b 1
