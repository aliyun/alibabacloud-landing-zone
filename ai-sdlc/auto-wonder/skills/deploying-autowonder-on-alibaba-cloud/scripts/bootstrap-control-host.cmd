@echo off
setlocal
powershell.exe -NoProfile -File "%~dp0windows\bootstrap-control-host.ps1" %*
exit /b %errorlevel%
