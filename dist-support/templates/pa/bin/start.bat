@echo off
setlocal

rem Force UTF-8 console output
chcp 65001 >nul 2>&1

rem Switch working directory to package root
cd /d "%~dp0.."

rem Ensure logs and data directories exist
if not exist "logs" mkdir "logs"
if not exist "data" mkdir "data"

rem Resolve a stable continuation key for persisted state (generate once, then reuse)
if not defined HAIFA_PERSONAL_CONTINUATION_KEY (
    if not exist "data\continuation-key.env" (
        echo Generating a persistent continuation key...
        powershell -NoProfile -Command "$p='data\continuation-key.env'; $b=New-Object byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); New-Item -ItemType Directory -Force -Path (Split-Path -Parent $p) | Out-Null; Set-Content -Path $p -Value ('HAIFA_PERSONAL_CONTINUATION_KEY=' + [Convert]::ToBase64String($b)) -Encoding ascii"
    )
    for /f "usebackq tokens=1,* delims==" %%a in ("data\continuation-key.env") do set "HAIFA_PERSONAL_CONTINUATION_KEY=%%b"
)
if not defined HAIFA_PERSONAL_CONTINUATION_KEY (
    echo [Error] Unable to resolve HAIFA_PERSONAL_CONTINUATION_KEY.
    exit /b 1
)

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

rem This standalone package runs trusted host execution; opt in explicitly.
if not defined HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED set "HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED=true"

rem The bundled openai-codex provider reads these from the environment.
if not defined HAIFA_CODEX_ORIGINATOR set "HAIFA_CODEX_ORIGINATOR=haifa"
if not defined HAIFA_CODEX_USER_AGENT set "HAIFA_CODEX_USER_AGENT=haifa-agent/1"

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
