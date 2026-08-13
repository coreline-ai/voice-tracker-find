@echo off
REM AI R Voice batch pipeline, wrapped so the scheduler run leaves a log.
REM
REM Task Scheduler discards stdout/stderr. Without a log a nightly failure is
REM invisible - you only notice days later when notes stop appearing.
REM cmd.exe is used for the redirect (not PowerShell) because PowerShell with
REM ErrorActionPreference=Stop treats native stderr as a terminating error, and
REM python's logging writes to stderr.
setlocal
set "LOG=%USERPROFILE%\.airvoice\pipeline.log"
if not exist "%USERPROFILE%\.airvoice" mkdir "%USERPROFILE%\.airvoice"
echo. >> "%LOG%"
echo ===== %DATE% %TIME% start ===== >> "%LOG%"
REM --normalize: 파이프라인 뒤에 증분 정규화(새 주제만 기존 클러스터에 배정).
"%~dp0..\.venv\Scripts\python.exe" -m airvoice.main --normalize >> "%LOG%" 2>&1
echo ===== %DATE% %TIME% exit=%ERRORLEVEL% ===== >> "%LOG%"
endlocal
