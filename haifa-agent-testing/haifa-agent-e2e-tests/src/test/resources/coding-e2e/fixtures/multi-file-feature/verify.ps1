$ErrorActionPreference = 'Stop'
$pythonCommand = if ($env:HAIFA_PYTHON_EXECUTABLE) { $env:HAIFA_PYTHON_EXECUTABLE } else { 'python' }
& $pythonCommand (Join-Path $PSScriptRoot 'verify.py')
exit $LASTEXITCODE
