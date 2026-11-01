@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\ecocloud.ps1" %*
exit /b %ERRORLEVEL%
