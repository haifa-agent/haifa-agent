Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

& (Join-Path $PSScriptRoot 'invoke-python.ps1') `
    -Script (Join-Path $PSScriptRoot 'haifa_build_metrics.py') `
    -ScriptArguments $args
exit $LASTEXITCODE
