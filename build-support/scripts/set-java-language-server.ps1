# Copyright 2026 Haifa Project
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.

<#
.SYNOPSIS
Pauses, resumes, or reports the VS Code Java language server for this workspace.

.EXAMPLE
.\build-support\scripts\set-java-language-server.ps1 stop

.EXAMPLE
.\build-support\scripts\set-java-language-server.ps1 start

.EXAMPLE
.\build-support\scripts\set-java-language-server.ps1 status
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$pythonScript = Join-Path $PSScriptRoot 'java_language_server.py'
$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
$pythonArguments = @()
if ($null -eq $pythonCommand) {
    $pythonCommand = Get-Command py -ErrorAction SilentlyContinue
    $pythonArguments = @('-3')
}
if ($null -eq $pythonCommand) {
    throw 'Python 3 is required by the Java language server controller.'
}

$pythonArguments += $pythonScript
$pythonArguments += $args

& $pythonCommand.Source @pythonArguments
exit $LASTEXITCODE
