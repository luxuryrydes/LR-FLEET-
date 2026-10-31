@echo off
rem Stops a database left running by an API that was killed, so the next start can use port 54329 and data\pg.
for %%i in ("%~dp0..") do set "ROOT=%%~fi\"
if not exist "%ROOT%data\pg\postmaster.pid" exit /b 0
for /d %%d in ("%TEMP%\embedded-pg\PG-*") do if exist "%%d\bin\pg_ctl.exe" "%%d\bin\pg_ctl.exe" -D "%ROOT%data\pg" stop -m fast >nul 2>&1
exit /b 0
