$ErrorActionPreference = 'Stop'
& python.exe (Join-Path $PSScriptRoot 'test.py')
exit $LASTEXITCODE
