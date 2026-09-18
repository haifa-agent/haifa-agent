$ErrorActionPreference = 'Stop'
$pythonScript = Join-Path $PSScriptRoot 'analyze_coding_runtime.py'
$pythonArguments = @($pythonScript) + $args

if ($env:HAIFA_PYTHON_EXECUTABLE) {
    if (-not (Test-Path -LiteralPath $env:HAIFA_PYTHON_EXECUTABLE -PathType Leaf)) {
        throw 'HAIFA_PYTHON_EXECUTABLE must resolve to a file.'
    }
    & $env:HAIFA_PYTHON_EXECUTABLE @pythonArguments
    exit $LASTEXITCODE
}

$python = Get-Command python.exe, python3.exe -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($python) {
    & $python.Source @pythonArguments
    exit $LASTEXITCODE
}

$pythonLauncher = Get-Command py.exe -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($pythonLauncher) {
    & $pythonLauncher.Source -3 @pythonArguments
    exit $LASTEXITCODE
}

throw 'Python 3 is required. Set HAIFA_PYTHON_EXECUTABLE or add python.exe to PATH.'
