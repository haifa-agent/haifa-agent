Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent

& (Join-Path $repositoryRoot 'build-support/scripts/invoke-python.ps1') `
    -Script (Join-Path $PSScriptRoot 'update_coding_agent.py') `
    -ScriptArguments $args
exit $LASTEXITCODE
