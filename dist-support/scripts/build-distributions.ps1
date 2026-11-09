Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent

Write-Host "[TIP] Packaging only collects prebuilt artifacts. Build the SDK modules first with: invoke-haifa-maven.ps1 --layer L2 '--' -pl <sdk-modules> -am -Prelease-artifacts verify (see dist-support/README.md)."

& (Join-Path $repositoryRoot 'build-support/scripts/invoke-python.ps1') `
    -Script (Join-Path $PSScriptRoot 'package_distributions.py') `
    -ScriptArguments $args
exit $LASTEXITCODE
