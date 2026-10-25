@echo off
setlocal

rem Force UTF-8 console output
chcp 65001 >nul 2>&1

rem Switch working directory to package root
cd /d "%~dp0.."

rem Load optional conf\pa.env (KEY=VALUE, lines starting with # are ignored).
rem System environment variables win; this file only fills the gaps.
if exist "conf\pa.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in ("conf\pa.env") do (
        if not "%%a"=="" if not defined %%a set "%%a=%%b"
    )
)

rem Ensure logs and data directories exist
if not exist "logs" mkdir "logs"
if not defined HAIFA_PERSONAL_DATA_DIR set "HAIFA_PERSONAL_DATA_DIR=%CD%\data\personal-assistant"
if not exist "%HAIFA_PERSONAL_DATA_DIR%" mkdir "%HAIFA_PERSONAL_DATA_DIR%"

rem Find Java: prefer bundled JRE, fallback to system java
set "JAVA_EXE=jre\bin\java.exe"
if not exist "%JAVA_EXE%" (
    set "JAVA_EXE=java"
)

rem Server JAR sits at the package root; the Linux package uses the same name
set "JAR_FILE=app.jar"
if not exist "%JAR_FILE%" (
    echo [Error] app.jar not found in package root.
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
echo Data Dir:   %HAIFA_PERSONAL_DATA_DIR%
echo Log File:   %CD%\logs\personal-assistant.log
echo Note:       Run bin\stop.bat to stop the service.
echo             Use bin\start-background.vbs for background running.
echo ========================================================================

rem Check if service is already running on port 20001
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":20001" ^| findstr "LISTENING"') do (
    echo [INFO] Haifa Personal Assistant is already running on port 20001 - PID: %%a.
    echo Opening browser directly...
    start "" powershell -NoProfile -Command "Start-Process 'http://127.0.0.1:20001/index.html'"
    exit /b 0
)

rem Launch Spring Boot Server in background redirecting output to log file
start /b "" "%JAVA_EXE%" %JAVA_OPTS% -jar "%JAR_FILE%" >> "%CD%\logs\personal-assistant.log" 2>&1

rem Poll readiness on port 20001 with dynamic loading animation, then open browser
powershell -NoProfile -Command "$port = 20001; $url = 'http://127.0.0.1:20001/index.html'; $spinners = @('|', '/', '-', '\'); $sw = [System.Diagnostics.Stopwatch]::StartNew(); $ready = $false; $step = 0; $timeoutSec = 30; while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) { $tcp = New-Object System.Net.Sockets.TcpClient; try { $async = $tcp.BeginConnect('127.0.0.1', $port, $null, $null); if ($async.AsyncWaitHandle.WaitOne(150) -and $tcp.Connected) { $ready = $true; break } } catch { } finally { $tcp.Close() }; $step++; $s = $spinners[$step %% 4]; $sec = [math]::Round($sw.Elapsed.TotalSeconds, 1); Write-Host -NoNewline ([char]13 + 'Starting Personal Assistant service... [' + $s + '] (' + $sec + 's) '); Start-Sleep -Milliseconds 150 }; Write-Host ''; if ($ready) { $sec = [math]::Round($sw.Elapsed.TotalSeconds, 1); Write-Host ('[OK] Service is ready (took ' + $sec + 's)! Opening browser...') -ForegroundColor Green; Start-Process $url } else { Write-Host ('[ERROR] Service did not become ready within ' + $timeoutSec + 's. Check logs\personal-assistant.log.') -ForegroundColor Red; exit 1 }"
if errorlevel 1 (
    echo [Error] Personal Assistant service did not become ready.
    exit /b 1
)

echo.
echo ========================================================================
echo Haifa Personal Assistant service is active.
echo To view full logs, see: %CD%\logs\personal-assistant.log
echo To stop service, run bin\stop.bat.
echo ========================================================================
echo.

powershell -NoProfile -Command "Get-Content -Path 'logs\personal-assistant.log' -Tail 20 -Wait"

endlocal
