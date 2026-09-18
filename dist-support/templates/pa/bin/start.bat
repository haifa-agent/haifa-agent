@echo off
setlocal

rem Force UTF-8 console output
chcp 65001 >nul 2>&1

rem Switch working directory to package root
cd /d "%~dp0.."

rem Ensure logs and data directories exist
if not exist "logs" mkdir "logs"
if not exist "data" mkdir "data"

rem Find Java: prefer bundled JRE, fallback to system java
set "JAVA_EXE=jre\bin\java.exe"
if not exist "%JAVA_EXE%" (
    set "JAVA_EXE=java"
)

rem Find Server JAR in lib directory
set "JAR_FILE="
for %%f in ("lib\haifa-agent-personal-assistant-server*.jar") do (
    set "JAR_FILE=%%f"
)

if not defined JAR_FILE (
    echo [Error] haifa-agent-personal-assistant-server*.jar not found in lib directory.
    exit /b 1
)

set JAVA_OPTS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dlogging.file.name=logs/personal-assistant.log -Xmx2048m

echo ========================================================================
echo Starting Haifa Personal Assistant Service...
echo Web URL:    http://127.0.0.1:20001/index.html
echo Data Dir:   %CD%\data\personal-assistant
echo Log File:   %CD%\logs\personal-assistant.log
echo Note:       Close this window to stop the service.
echo             Use bin\start-background.vbs for background running.
echo ========================================================================

rem Launch browser after 2 seconds in background
start "" powershell -NoProfile -Command "Start-Sleep -Seconds 2; Start-Process 'http://127.0.0.1:20001/index.html'"

rem Launch Spring Boot Server
"%JAVA_EXE%" %JAVA_OPTS% -jar "%JAR_FILE%"

endlocal
