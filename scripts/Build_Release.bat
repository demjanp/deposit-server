@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Build_Release.ps1" %*
exit /b %ERRORLEVEL%
