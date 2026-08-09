[CmdletBinding()]
param(
    [string] $MetricsRoot = '',
    [ValidateRange(1, 10000)]
    [int] $Limit = 100,
    [string] $GitSha = '',
    [ValidateSet('', 'L0', 'L1', 'L2', 'L3')]
    [string] $Layer = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repository = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($MetricsRoot)) {
    $MetricsRoot = Join-Path $repository 'local-tmp\maven-build-metrics'
}
if (-not (Test-Path -LiteralPath $MetricsRoot -PathType Container)) {
    throw "Metrics directory does not exist: $MetricsRoot"
}

$records = Get-ChildItem -LiteralPath $MetricsRoot -Filter '*.json' -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First $Limit |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 | ConvertFrom-Json } |
    Where-Object {
        ([string]::IsNullOrWhiteSpace($GitSha) -or $_.gitSha -eq $GitSha) -and
        ([string]::IsNullOrWhiteSpace($Layer) -or $_.layer -eq $Layer)
    }

if (-not $records) {
    throw 'No matching Maven metric records were found.'
}

function Get-Percentile([long[]] $Values, [double] $Percentile) {
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling($Percentile * $sorted.Count) - 1
    return $sorted[[Math]::Max(0, $index)]
}

$clean = @($records | Where-Object { -not $_.hostSleepDetected })
$passing = @($clean | Where-Object { $_.classification -eq 'PASS' })
$wall = @($passing | ForEach-Object { [long] $_.wallMillis })
$moduleRows = @($passing | ForEach-Object { @($_.slowestModules) })
$testRows = @($passing | ForEach-Object { @($_.slowestTests) })
$summary = [ordered]@{
    schemaVersion = 1
    sampleCount = @($records).Count
    comparableCount = $clean.Count
    passingCount = $passing.Count
    classifications = @($records | Group-Object classification | Sort-Object Name | ForEach-Object {
        [ordered]@{ classification = $_.Name; count = $_.Count }
    })
    p50WallMillis = if ($wall.Count) { Get-Percentile $wall 0.50 } else { $null }
    p95WallMillis = if ($wall.Count) { Get-Percentile $wall 0.95 } else { $null }
    maxWallMillis = if ($wall.Count) { ($wall | Measure-Object -Maximum).Maximum } else { $null }
    slowestModules = @($moduleRows | Group-Object module | ForEach-Object {
        [ordered]@{
            module = $_.Name
            maxMillis = ($_.Group.millis | Measure-Object -Maximum).Maximum
        }
    } | Sort-Object maxMillis -Descending | Select-Object -First 10)
    slowestTests = @($testRows | Group-Object testClass | ForEach-Object {
        [ordered]@{
            testClass = $_.Name
            maxMillis = ($_.Group.millis | Measure-Object -Maximum).Maximum
        }
    } | Sort-Object maxMillis -Descending | Select-Object -First 10)
}

$summary | ConvertTo-Json -Depth 8
