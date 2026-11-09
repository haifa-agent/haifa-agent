Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

& (Join-Path $PSScriptRoot 'invoke-python.ps1') `
    -Script (Join-Path $PSScriptRoot 'codebase_stats.py') `
    -ScriptArguments $args
exit $LASTEXITCODE
