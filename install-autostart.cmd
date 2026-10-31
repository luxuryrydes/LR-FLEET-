@echo off
rem Starts LR Fleet Manager LIVE when you sign in to Windows, and backs up the data every night at 23:30.
rem Run again to change the backup folder. uninstall-autostart.cmd removes both.
setlocal EnableExtensions
cd /d "%~dp0"
set "ROOT=%~dp0"
if not exist "%ROOT%data" mkdir "%ROOT%data"

set "DEFAULT=%ROOT%data\backups"
if exist "%USERPROFILE%\OneDrive" set "DEFAULT=%USERPROFILE%\OneDrive\LR Fleet Backups"
if exist "%USERPROFILE%\Google Drive" set "DEFAULT=%USERPROFILE%\Google Drive\LR Fleet Backups"
echo.
echo Where should the nightly backups go? A OneDrive / Google Drive folder keeps a copy off this PC.
echo Press Enter for: %DEFAULT%
set "FOLDER="
set /p FOLDER=Backup folder:
if "%FOLDER%"=="" set "FOLDER=%DEFAULT%"
if not exist "%FOLDER%" mkdir "%FOLDER%" || (echo Could not create that folder. & pause & exit /b 1)
> "%ROOT%data\backup-folder.txt" echo %FOLDER%

schtasks /create /f /tn "LR Fleet Live" /sc onlogon /rl limited /tr "\"%ROOT%start-live.cmd\" auto" >nul || goto :fail
schtasks /create /f /tn "LR Fleet Backup" /sc daily /st 23:30 /rl limited /tr "\"%ROOT%backup-now.cmd\" auto" >nul || goto :fail
echo.
echo Done:
echo  - LR Fleet Manager starts by itself when you sign in to Windows.
echo  - The data is backed up every night at 23:30 to: %FOLDER%  ^(last 14 kept^)
echo Keep this PC on and awake: Settings ^> System ^> Power ^> Sleep = Never ^(when plugged in^).
pause
exit /b 0
:fail
echo Could not create the scheduled tasks.
pause
exit /b 1
