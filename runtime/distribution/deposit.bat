@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0deposit.ps1" %*
exit /b %ERRORLEVEL%
