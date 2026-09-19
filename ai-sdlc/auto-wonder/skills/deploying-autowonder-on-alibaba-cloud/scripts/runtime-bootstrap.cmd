@echo off
setlocal
if "%~1"=="--project-root" (
  powershell.exe -NoProfile -File "%~dp0windows\runtime-bootstrap.ps1" -ProjectRoot "%~2"
) else (
  powershell.exe -NoProfile -File "%~dp0windows\runtime-bootstrap.ps1" %*
)
exit /b %errorlevel%
