Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$python = Get-Command python -ErrorAction SilentlyContinue
$prefix = @()
if ($null -eq $python) {
    $python = Get-Command py -ErrorAction SilentlyContinue
    $prefix = @('-3')
}
if ($null -eq $python) {
    throw "Python 3 is required by the codebase statistics script (tried 'python' and 'py -3')."
}

$arguments = @()
$arguments += $prefix
$arguments += Join-Path $PSScriptRoot 'codebase_stats.py'
$arguments += $args

& $python.Definition @arguments
exit $LASTEXITCODE
