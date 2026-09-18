@echo off
setlocal enabledelayedexpansion

rem Force UTF-8 console output
chcp 65001 >nul 2>&1

echo Checking for Personal Assistant process on port 20001...

set "FOUND_PID="
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":20001" ^| findstr "LISTENING"') do (
    set "FOUND_PID=%%a"
)

if defined FOUND_PID (
    echo Stopping process PID: !FOUND_PID! ...
    taskkill /F /PID !FOUND_PID! >nul 2>&1
    echo [OK] Haifa Personal Assistant service stopped.
) else (
    echo [INFO] Personal Assistant is not currently running (port 20001 is idle).
)

endlocal
