# Copyright 2026 Haifa Project
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

<#
.SYNOPSIS
Runs a repository Python 3 entry script with the shared interpreter resolution.

.DESCRIPTION
Every Windows entry point forwards its arguments through this script, so the
interpreter lookup lives in exactly one place: HAIFA_PYTHON_EXECUTABLE first,
then python.exe/python3 on PATH, then the `py -3` launcher. Arguments are passed
through unchanged and the child exit code becomes $LASTEXITCODE, which the caller
returns unchanged.

.EXAMPLE
& .\invoke-python.ps1 -Script .\codebase_stats.py -ScriptArguments @('modules')

.EXAMPLE
$env:HAIFA_PYTHON_EXECUTABLE = 'C:\Python311\python.exe'
& .\invoke-python.ps1 -Script .\codebase_stats.py
#>

param(
    [Parameter(Mandatory = $true)][string]$Script,
    [string[]]$ScriptArguments = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$interpreter = $null
$interpreterPrefix = @()

if ($env:HAIFA_PYTHON_EXECUTABLE) {
    if (-not (Test-Path -LiteralPath $env:HAIFA_PYTHON_EXECUTABLE -PathType Leaf)) {
        throw 'HAIFA_PYTHON_EXECUTABLE must resolve to a file.'
    }
    $interpreter = $env:HAIFA_PYTHON_EXECUTABLE
}
else {
    $command = Get-Command python.exe, python3.exe, python3, python -CommandType Application `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $command) {
        $interpreter = $command.Source
    }
    else {
        $launcher = Get-Command py.exe -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -ne $launcher) {
            $interpreter = $launcher.Source
            $interpreterPrefix = @('-3')
        }
    }
}

if ($null -eq $interpreter) {
    throw 'Python 3 is required. Set HAIFA_PYTHON_EXECUTABLE or add python.exe to PATH.'
}

& $interpreter @interpreterPrefix $Script @ScriptArguments
