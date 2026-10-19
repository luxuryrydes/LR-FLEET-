@echo off
rem ==========================================================================
rem  LR Fleet Manager - one-click local run on Windows (no Docker, no PostgreSQL install)
rem  Needs: Python 3.11+ and Node.js 20+ installed. Java 21 + Maven are downloaded
rem  automatically into .tools\ the first time (about 215 MB).
rem  Opens three windows (API, worker, website) and then http://localhost:5173
rem  Local sign-in:  admin@lrtaxi.local  /  Admin@12345   (change it under Users & roles)
rem ==========================================================================
setlocal EnableExtensions
cd /d "%~dp0"
set "ROOT=%~dp0"

where python >nul 2>nul || (echo Python is not installed. Install Python 3.12 from https://www.python.org/downloads/ and tick "Add to PATH". & pause & exit /b 1)
where npm >nul 2>nul || (echo Node.js is not installed. Install Node.js 20 LTS from https://nodejs.org/ & pause & exit /b 1)

rem ---- Java 21 + Maven (portable, inside .tools) ----
if not exist ".tools" mkdir ".tools"
set "JAVA_HOME="
for /d %%d in ("%ROOT%.tools\jdk-21*") do set "JAVA_HOME=%%~fd"
if not defined JAVA_HOME (
  echo Downloading Java 21 ^(Eclipse Temurin^)...
  curl.exe -L --fail -o ".tools\jdk21.zip" "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse" || goto :dlfail
  tar.exe -xf ".tools\jdk21.zip" -C ".tools" || goto :dlfail
  del ".tools\jdk21.zip"
  for /d %%d in ("%ROOT%.tools\jdk-21*") do set "JAVA_HOME=%%~fd"
)
if not exist ".tools\apache-maven-3.9.9\bin\mvn.cmd" (
  echo Downloading Maven 3.9.9...
  curl.exe -L --fail -o ".tools\maven.zip" "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip" || goto :dlfail
  tar.exe -xf ".tools\maven.zip" -C ".tools" || goto :dlfail
  del ".tools\maven.zip"
)
set "PATH=%JAVA_HOME%\bin;%ROOT%.tools\apache-maven-3.9.9\bin;%PATH%"

rem ---- Python worker environment ----
if not exist "worker\.venv\Scripts\python.exe" (
  echo Setting up the Python worker...
  python -m venv worker\.venv || goto :fail
  worker\.venv\Scripts\python -m pip install -q -r worker\requirements.txt || goto :fail
)

rem ---- Frontend packages ----
if not exist "frontend\node_modules" (
  echo Installing website packages...
  call npm --prefix frontend install --no-audit --no-fund || goto :fail
)

if not exist "data\files" mkdir "data\files"

echo Starting the API (first start downloads libraries and can take a few minutes)...
start "LR Fleet - API" cmd /k "cd /d "%ROOT%backend" && mvn -q -Plocal spring-boot:run"


start "LR Fleet - Website" cmd /k "cd /d "%ROOT%frontend" && npm run dev"

echo Waiting for the API on http://localhost:8080 ...
:wait
ping -n 6 127.0.0.1 >nul
curl.exe -s -o nul http://localhost:8080/actuator/health || goto :wait
rem The worker starts once the API is up and has created the database tables.
start "LR Fleet - Import worker" cmd /k "cd /d "%ROOT%worker" && set "WORKER_DATABASE_URL=postgresql://postgres:postgres@localhost:54329/postgres" && set "STORAGE_TYPE=local" && set "STORAGE_LOCAL_DIR=%ROOT%data\files" && .venv\Scripts\python -m fleet_worker.main"
start "" http://localhost:5173
echo.
echo  Ready:  http://localhost:5173
echo  Sign in: admin@lrtaxi.local  /  Admin@12345
echo  Close the three windows to stop. Your data is kept in the "data" folder.
pause
exit /b 0

:dlfail
echo Download failed. Check the internet connection and run this file again.
pause
exit /b 1

:fail
echo Setup failed - see the messages above.
pause
exit /b 1
