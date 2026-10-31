@echo off
schtasks /delete /f /tn "LR Fleet Live" >nul 2>&1
schtasks /delete /f /tn "LR Fleet Backup" >nul 2>&1
echo Auto-start and nightly backup removed. Your data and backups are not touched.
pause
