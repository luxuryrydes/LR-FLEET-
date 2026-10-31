
@echo off
rem ==========================================================================
rem LR Fleet Manager - PRODUCTION LIVE
rem
rem Starts:
rem   1. API + database
rem   2. Import worker
rem   3. Production website on localhost:4173
rem
rem Cloudflare:
rem   Uses the EXISTING cloudflared Windows service.
rem   Does NOT install or start another Cloudflare tunnel.
rem
rem Public routes are configured separately in Cloudflare:
rem   fleet.lrbilling.in  -> http://localhost:4173
rem   billing.lrbilling.in -> http://localhost:4000
rem
rem Before first use:
rem   Run run-local.cmd once to install dependencies and initialize database.
rem   Change the default administrator password.
rem
rem Do not run run-local.cmd while production is running.
rem Use stop-live.cmd to stop the Fleet Manager application.
rem ==========================================================================

setlocal EnableExtensions
cd /d "%~dp0"

set "ROOT=%~dp0"
set "AUTO=%~1"
set "LOGS=%ROOT%data\logs"

if not exist "%LOGS%" mkdir "%LOGS%"

rem ---- Load production environment -----------------------------------------

call "%ROOT%live\env.cmd"
if errorlevel 1 goto :fail

if exist "%ROOT%data\live.stop" del "%ROOT%data\live.stop"

rem ---- Check whether the application is already running -------------------

curl.exe -s -o nul --fail http://localhost:8080/actuator/health

if not errorlevel 1 (
    echo.
    echo LR Fleet Manager is already running.
    echo Run stop-live.cmd before restarting.
    goto :fail
)

rem ---- Build API -----------------------------------------------------------

echo.
echo ============================================================
echo             LR FLEET MANAGER - PRODUCTION
echo ============================================================
echo.
echo Building API. Please wait...
echo [%date% %time%] Building API >> "%LOGS%\live.log"

call mvn -q -f "%ROOT%backend\pom.xml" -Plocal -DskipTests package > "%LOGS%\build-api.log" 2>&1

if errorlevel 1 (
    echo.
    echo ERROR: API build failed.
    echo Check data\logs\build-api.log
    goto :fail
)

rem ---- Build website -------------------------------------------------------

echo.
echo Building production website...

set "VITE_API_BASE_URL="

call npm --prefix "%ROOT%frontend" run build > "%LOGS%\build-web.log" 2>&1

if errorlevel 1 (
    echo.
    echo ERROR: Website build failed.
    echo Check data\logs\build-web.log
    goto :fail
)

rem ---- Start API and database ----------------------------------------------

echo.
echo Starting API and database...

start "LR Fleet LIVE - API" /min cmd /c ""%ROOT%live\api.cmd""

rem ---- Wait for API --------------------------------------------------------

set /a N=0

:waitapi

ping -n 4 127.0.0.1 >nul

set /a N+=1

curl.exe -s -o nul --fail http://localhost:8080/actuator/health

if not errorlevel 1 goto :apiup

if %N% GEQ 100 (
    echo.
    echo ERROR: API did not start.
    echo Check data\logs\api.log
    goto :fail
)

goto :waitapi

:apiup

echo.
echo API is running successfully.

rem ---- Verify administrator password before starting website --------------

echo.
echo Checking administrator security settings...

curl.exe -s --fail -o "%TEMP%\lrfleet-share-check.txt" http://localhost:8080/api/system/share-check

if errorlevel 1 goto :sharecheck_failed

findstr /c:"\"defaultAdminPassword\":false" "%TEMP%\lrfleet-share-check.txt" >nul

if errorlevel 1 goto :default_password

echo Administrator security check passed.

rem ---- Start import worker -------------------------------------------------

echo.
echo Starting import worker...

start "LR Fleet LIVE - Import worker" /min cmd /c ""%ROOT%live\worker.cmd""

rem ---- Start production website --------------------------------------------

echo.
echo Starting production website on port 4173...

start "LR Fleet LIVE - Website" /min cmd /c ""%ROOT%live\web.cmd""

rem ---- Wait for website ----------------------------------------------------

set /a W=0

:waitweb

ping -n 2 127.0.0.1 >nul

set /a W+=1

curl.exe -s -o nul --fail http://localhost:4173

if not errorlevel 1 goto :webup

if %W% GEQ 30 (
    echo.
    echo ERROR: Production website did not start on port 4173.
    echo Check data\logs\web.log
    goto :fail
)

goto :waitweb

:webup

rem ---- Check existing Cloudflare service -----------------------------------

echo.
echo Checking existing Cloudflare Windows service...

sc query cloudflared | findstr /I "RUNNING" >nul

if errorlevel 1 (
    echo WARNING: Cloudflare service is not running.
    echo Check the Cloudflare Windows service before using the public domain.
) else (
    echo Existing Cloudflare service is running.
)

rem ---- Production started --------------------------------------------------

echo [%date% %time%] API, worker and production website started >> "%LOGS%\live.log"

echo.
echo ============================================================
echo             LR FLEET MANAGER STARTED
echo ============================================================
echo.
echo Local production website:
echo http://localhost:4173
echo.
echo API health:
echo http://localhost:8080/actuator/health
echo.
echo Cloudflare:
echo Existing Windows Cloudflare service is being used.
echo.
echo Public address:
echo https://fleet.lrbilling.in
echo.
echo Billing Expo:
echo Existing route remains unchanged.
echo https://billing.lrbilling.in
echo.
echo Logs:
echo %LOGS%
echo.
echo Keep the LR Fleet LIVE windows running.
echo Use stop-live.cmd to stop the application.
echo ============================================================
echo.

if /i not "%AUTO%"=="auto" pause

exit /b 0

rem ---- Default administrator password detected ----------------------------

:default_password

echo.
echo SECURITY CHECK FAILED.
echo.
echo An administrator is still using a default password.
echo The production website has NOT been started.
echo.
echo Start run-local.cmd, sign in and change the administrator password.
echo Then close the local application, run stop-live.cmd and retry.
echo.

goto :fail

rem ---- Security endpoint unavailable --------------------------------------

:sharecheck_failed

echo.
echo ERROR: Could not verify administrator security settings.
echo The production website has NOT been started.
echo Check the API and data\logs\api.log.
echo.

goto :fail

rem ---- Startup failure -----------------------------------------------------

:fail

echo [%date% %time%] start-live failed >> "%LOGS%\live.log"

if /i not "%AUTO%"=="auto" pause

exit /b 1
