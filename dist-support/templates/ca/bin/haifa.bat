@echo off
setlocal

rem Force console code page to UTF-8
chcp 65001 >nul 2>&1

rem JVM options
set "JAVA_OPTS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Xmx1024m"

rem Find Java: prefer bundled JRE, fallback to system java
set "JAVA_EXE=%~dp0..\jre\bin\java.exe"
if not exist "%JAVA_EXE%" (
    set "JAVA_EXE=java"
)

rem Find CLI JAR in lib directory
set "JAR_FILE="
for %%f in ("%~dp0..\lib\haifa-agent-cli*.jar") do (
    set "JAR_FILE=%%f"
)

if not defined JAR_FILE (
    echo [Error] haifa-agent-cli*.jar not found in lib directory.
    exit /b 1
)

rem The remote TUI is a thin client: pass its argument vector through untouched. It must not receive the bundled
rem --config (the Host owns the workspace, Runtime and credentials) and it must not create local data directories.
if /i "%~1"=="tui" (
    "%JAVA_EXE%" %JAVA_OPTS% -jar "%JAR_FILE%" %*
    exit /b %ERRORLEVEL%
)

rem Local modes resolve the per-user durable data and log directories (default: %USERPROFILE%\.haifa-agent\coding)
if not defined HAIFA_DATA_ROOT set "HAIFA_DATA_ROOT=%USERPROFILE%\.haifa-agent\coding"
if not exist "%HAIFA_DATA_ROOT%\data\transcripts" mkdir "%HAIFA_DATA_ROOT%\data\transcripts"
if errorlevel 1 exit /b %ERRORLEVEL%
if not exist "%HAIFA_DATA_ROOT%\logs" mkdir "%HAIFA_DATA_ROOT%\logs"
if errorlevel 1 exit /b %ERRORLEVEL%
if not defined HAIFA_SQLITE_DATABASE_PATH set "HAIFA_SQLITE_DATABASE_PATH=%HAIFA_DATA_ROOT%\data\runtime.db"
if not defined HAIFA_TRANSCRIPT_ROOT set "HAIFA_TRANSCRIPT_ROOT=%HAIFA_DATA_ROOT%\data\transcripts"
if not defined HAIFA_LOG_DIR set "HAIFA_LOG_DIR=%HAIFA_DATA_ROOT%\logs"

rem Launch Coding Agent with the bundled default configuration when available.
set "HAIFA_CONFIG=%~dp0..\haifa-coding.yaml"
if /i "%~1"=="host" goto :haifa_host

if exist "%HAIFA_CONFIG%" (
    "%JAVA_EXE%" %JAVA_OPTS% -jar "%JAR_FILE%" --config "%HAIFA_CONFIG%" %*
) else (
    "%JAVA_EXE%" %JAVA_OPTS% -jar "%JAR_FILE%" %*
)
exit /b %ERRORLEVEL%

rem The host subcommand must stay the first JVM argument, so the bundled configuration is inserted after it and an
rem explicit --config still wins because the parser keeps the last value. Arguments are rebuilt one at a time so
rem quoted values with spaces survive.
:haifa_host
set "HAIFA_HOST_ARGS="
shift
:haifa_host_args
if "%~1"=="" goto :haifa_host_run
set "HAIFA_HOST_ARGS=%HAIFA_HOST_ARGS% "%~1""
shift
goto :haifa_host_args
:haifa_host_run
if exist "%HAIFA_CONFIG%" (
    call "%JAVA_EXE%" %JAVA_OPTS% -jar "%JAR_FILE%" host --config "%HAIFA_CONFIG%" %HAIFA_HOST_ARGS%
) else (
    call "%JAVA_EXE%" %JAVA_OPTS% -jar "%JAR_FILE%" host %HAIFA_HOST_ARGS%
)
exit /b %ERRORLEVEL%
