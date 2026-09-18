[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
)

# Keep console input and output in UTF-8
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$binDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$root = Split-Path -Parent $binDir

$javaExe = Join-Path $root 'jre\bin\java.exe'
if (-not (Test-Path $javaExe)) {
    $javaExe = 'java'
}

$jarFiles = Get-ChildItem -Path (Join-Path $root 'lib') -Filter 'haifa-agent-cli*.jar' -File
if ($jarFiles.Count -eq 0) {
    Write-Error "haifa-agent-cli*.jar was not found in the lib directory."
    exit 1
}
$jarPath = $jarFiles[0].FullName

# Resolve the per-user durable data and log directories (default: %USERPROFILE%\.haifa-agent\coding)
$dataRoot = if ($env:HAIFA_DATA_ROOT) {
    $env:HAIFA_DATA_ROOT
} else {
    Join-Path $env:USERPROFILE '.haifa-agent\coding'
}
$dataDir = Join-Path $dataRoot 'data'
$transcriptDir = Join-Path $dataDir 'transcripts'
$logDir = Join-Path $dataRoot 'logs'
New-Item -ItemType Directory -Force -Path $transcriptDir, $logDir | Out-Null

if (-not $env:HAIFA_SQLITE_DATABASE_PATH) { $env:HAIFA_SQLITE_DATABASE_PATH = Join-Path $dataDir 'runtime.db' }
if (-not $env:HAIFA_TRANSCRIPT_ROOT) { $env:HAIFA_TRANSCRIPT_ROOT = $transcriptDir }
if (-not $env:HAIFA_LOG_DIR) { $env:HAIFA_LOG_DIR = $logDir }

$javaOpts = @(
    '-Dfile.encoding=UTF-8',
    '-Dsun.stdout.encoding=UTF-8',
    '-Dsun.stderr.encoding=UTF-8',
    '-Xmx1024m'
)

$cliArgs = @('-jar', $jarPath)
$configPath = Join-Path $root 'haifa-coding.yaml'
if (Test-Path $configPath) {
    $cliArgs += @('--config', $configPath)
}
if ($null -ne $RemainingArgs) {
    $cliArgs += $RemainingArgs
}

& $javaExe @javaOpts @cliArgs
exit $LASTEXITCODE
