@echo off
REM thinktank LAN receiver - double-click launcher.
REM .ps1 files do not run on double-click by Windows design (security).
REM This .cmd wrapper runs it without changing any file association.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run_receiver.ps1" %*
pause
