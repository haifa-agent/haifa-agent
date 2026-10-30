Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path $PSScriptRoot -Parent

& (Join-Path $repositoryRoot 'build-support/scripts/invoke-python.ps1') `
    -Script (Join-Path $PSScriptRoot 'real_environment.py') `
    -ScriptArguments $args
exit $LASTEXITCODE
