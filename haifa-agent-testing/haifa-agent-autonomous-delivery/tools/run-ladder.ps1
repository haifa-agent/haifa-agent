$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent

& (Join-Path $repositoryRoot 'build-support/scripts/invoke-python.ps1') `
    -Script (Join-Path $PSScriptRoot 'run_ladder.py') `
    -ScriptArguments $args
exit $LASTEXITCODE
