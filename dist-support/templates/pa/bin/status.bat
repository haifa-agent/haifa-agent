@echo off
setlocal enabledelayedexpansion

rem Force UTF-8 console output
chcp 65001 >nul 2>&1

echo Checking Haifa Personal Assistant service status...

set "FOUND_PID="
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":20001" ^| findstr "LISTENING"') do (
    set "FOUND_PID=%%a"
)

if defined FOUND_PID (
    echo [RUNNING] Personal Assistant is active on port 20001 - PID !FOUND_PID!.
    echo           Web URL: http://127.0.0.1:20001/index.html
) else (
    echo [STOPPED] Personal Assistant is currently not running.
)

endlocal
