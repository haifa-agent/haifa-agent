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

& (Join-Path $PSScriptRoot 'invoke-python.ps1') `
    -Script (Join-Path $PSScriptRoot 'java_language_server.py') `
    -ScriptArguments $args
exit $LASTEXITCODE
