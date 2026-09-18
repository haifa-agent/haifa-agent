@echo off
setlocal

rem Force console code page to UTF-8
chcp 65001 >nul 2>&1

rem JVM options
set "JAVA_OPTS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Xmx1024m"

rem Resolve the per-user durable data and log directories (default: %USERPROFILE%\.haifa-agent\coding)
if not defined HAIFA_DATA_ROOT set "HAIFA_DATA_ROOT=%USERPROFILE%\.haifa-agent\coding"
if not exist "%HAIFA_DATA_ROOT%\data\transcripts" mkdir "%HAIFA_DATA_ROOT%\data\transcripts"
if errorlevel 1 exit /b %ERRORLEVEL%
if not exist "%HAIFA_DATA_ROOT%\logs" mkdir "%HAIFA_DATA_ROOT%\logs"
if errorlevel 1 exit /b %ERRORLEVEL%
if not defined HAIFA_SQLITE_DATABASE_PATH set "HAIFA_SQLITE_DATABASE_PATH=%HAIFA_DATA_ROOT%\data\runtime.db"
if not defined HAIFA_TRANSCRIPT_ROOT set "HAIFA_TRANSCRIPT_ROOT=%HAIFA_DATA_ROOT%\data\transcripts"
if not defined HAIFA_LOG_DIR set "HAIFA_LOG_DIR=%HAIFA_DATA_ROOT%\logs"

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

rem Launch Coding Agent with the bundled default configuration when available
set "HAIFA_CONFIG=%~dp0..\haifa-coding.yaml"
if exist "%HAIFA_CONFIG%" (
    "%JAVA_EXE%" %JAVA_OPTS% -jar "%JAR_FILE%" --config "%HAIFA_CONFIG%" %*
) else (
    "%JAVA_EXE%" %JAVA_OPTS% -jar "%JAR_FILE%" %*
)
exit /b %ERRORLEVEL%
