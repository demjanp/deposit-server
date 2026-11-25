@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Run_Server.ps1" %*
exit /b %ERRORLEVEL%
