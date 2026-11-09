Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

& (Join-Path $PSScriptRoot 'invoke-python.ps1') `
    -Script (Join-Path $PSScriptRoot 'summarize_maven_build.py') `
    -ScriptArguments $args
exit $LASTEXITCODE
