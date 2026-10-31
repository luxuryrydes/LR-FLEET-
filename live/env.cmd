@echo off
rem Shared settings for the live scripts: portable Java 21 + Maven from .tools, checks the one-time setup.
for %%i in ("%~dp0..") do set "ROOT=%%~fi\"
set "JAVA_HOME="
for /d %%d in ("%ROOT%.tools\jdk-21*") do set "JAVA_HOME=%%~fd"
if not defined JAVA_HOME (echo Run run-local.cmd once first ^(installs Java, Maven and the packages^). & exit /b 1)
if not exist "%ROOT%.tools\apache-maven-3.9.9\bin\mvn.cmd" (echo Run run-local.cmd once first. & exit /b 1)
if not exist "%ROOT%worker\.venv\Scripts\python.exe" (echo Run run-local.cmd once first. & exit /b 1)
if not exist "%ROOT%frontend\node_modules" (echo Run run-local.cmd once first. & exit /b 1)
set "PATH=%JAVA_HOME%\bin;%ROOT%.tools\apache-maven-3.9.9\bin;%PATH%"
exit /b 0
