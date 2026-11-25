@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Run_Shell.ps1" %*
exit /b %ERRORLEVEL%
