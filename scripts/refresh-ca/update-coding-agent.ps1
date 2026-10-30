Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$python = Get-Command python -ErrorAction SilentlyContinue
$prefix = @()
if ($null -eq $python) {
    $python = Get-Command py -ErrorAction SilentlyContinue
    $prefix = @('-3')
}
if ($null -eq $python) { throw 'Python 3 is required (python or py -3).' }
& $python.Definition @prefix (Join-Path $PSScriptRoot 'update_coding_agent.py') @args
exit $LASTEXITCODE
