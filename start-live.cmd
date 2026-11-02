@echo off
rem Kept so older shortcuts and the autostart task still work: it simply starts run-local.cmd.
if /i "%~1"=="auto" (call "%~dp0run-local.cmd" -Auto) else (call "%~dp0run-local.cmd" %*)
