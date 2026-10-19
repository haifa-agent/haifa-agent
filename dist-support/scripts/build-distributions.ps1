Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$python = Get-Command python -ErrorAction SilentlyContinue
$prefix = @()
if ($null -eq $python) {
    $python = Get-Command py -ErrorAction SilentlyContinue
    $prefix = @('-3')
}
if ($null -eq $python) {
    throw "Python 3 is required for release distribution packaging (tried 'python' and 'py -3')."
}

$arguments = @(
    $prefix
    (Join-Path $PSScriptRoot 'package_distributions.py')
)
$arguments += $args

Write-Host "[TIP] Packaging only collects prebuilt artifacts. Build the SDK modules first with: invoke-haifa-maven.ps1 --layer L2 '--' -pl <sdk-modules> -am -Prelease-artifacts verify (see dist-support/README.md)."

& $python.Definition @arguments
exit $LASTEXITCODE
