[CmdletBinding()]
param(
    [ValidateSet('L0', 'L1', 'L2', 'L3')]
    [string] $Layer = 'L1',
    [ValidateRange(0, 64)]
    [int] $Threads = 0,
    [ValidateRange(0, 86400)]
    [int] $TimeoutSeconds = 0,
    [string] $MetricsRoot = '',
    [switch] $NoMetrics,
    [switch] $KeepLog,
    [switch] $StreamOutput,
    [Parameter(Mandatory = $true)]
    [string[]] $MavenArguments
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$python = Get-Command python -ErrorAction SilentlyContinue
$prefix = @()
if ($null -eq $python) {
    $python = Get-Command py -ErrorAction SilentlyContinue
    $prefix = @('-3')
}
if ($null -eq $python) {
    throw "Python 3 is required by the Maven metrics runner (tried 'python' and 'py -3')."
}

$arguments = @(
    $prefix
    (Join-Path $PSScriptRoot 'haifa_build_metrics.py')
    '--layer', $Layer
    '--threads', [string] $Threads
    '--timeout-seconds', [string] $TimeoutSeconds
)
if (-not [string]::IsNullOrWhiteSpace($MetricsRoot)) {
    $arguments += @('--metrics-root', $MetricsRoot)
}
if ($NoMetrics) { $arguments += '--no-metrics' }
if ($KeepLog) { $arguments += '--keep-log' }
if ($StreamOutput) { $arguments += '--stream-output' }
$arguments += '--'
$arguments += $MavenArguments

& $python.Definition @arguments
exit $LASTEXITCODE
