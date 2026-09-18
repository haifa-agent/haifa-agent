Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
$pythonPrefix = @()
if ($null -eq $pythonCommand) {
    $pythonCommand = Get-Command py -ErrorAction SilentlyContinue
    $pythonPrefix = @('-3')
}
if ($null -eq $pythonCommand) {
    throw "Python 3 was not found on PATH (tried 'python' and 'py -3')."
}

$pythonScript = Join-Path $PSScriptRoot 'real_environment.py'
$arguments = @()
$arguments += $pythonPrefix
$arguments += $pythonScript
$arguments += $args

& $pythonCommand.Definition @arguments
exit $LASTEXITCODE
