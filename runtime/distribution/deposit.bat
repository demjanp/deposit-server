@echo off
setlocal
set "DEPOSIT_HOME=%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%DEPOSIT_HOME%deposit.ps1" %*
set "DEPOSIT_EXIT=%ERRORLEVEL%"
exit /b %DEPOSIT_EXIT%
