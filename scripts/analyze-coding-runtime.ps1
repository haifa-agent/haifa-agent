$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path $PSScriptRoot -Parent

& (Join-Path $repositoryRoot 'build-support/scripts/invoke-python.ps1') `
    -Script (Join-Path $PSScriptRoot 'analyze_coding_runtime.py') `
    -ScriptArguments $args
exit $LASTEXITCODE
