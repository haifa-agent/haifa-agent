[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string] $DeepSeekKeyFile = 'D:\workspace\ss-deepseek.txt',
    [ValidateSet('deepseek-chat-pro', 'deepseek-chat-flash', 'deepseek-responses-flash')]
    [string] $DefaultModelId = 'deepseek-chat-flash',
    [string] $AliyunIqsKeyFile = 'D:\workspace\ss-aliyun-iqs.txt',
    [string] $ContinuationKeyFile = 'D:\workspace\ss-haifa-personal-continuation.txt',
    [string] $UtilityMcpDirectory = 'D:\workspace\haifa\haifa-ai\haifa-ai-utility-mcp-server',
    [string] $UtilityMcpProxyUrl = 'http://127.0.0.1:2081',
    [string] $UtilityMcpProxyProviders = 'wikimedia',
    [string] $PersonalSkillRoot = 'D:\agents\hermes-agent\optional-skills\finance',
    [string] $TrustedScriptManifest = '',
    [switch] $Rebuild,
    [switch] $Stop,
    [switch] $Force,
    [ValidateRange(30, 600)]
    [int] $StartupTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Stop -and $Rebuild) {
    throw '-Stop and -Rebuild cannot be used together.'
}
if ($Force -and -not $Stop) {
    throw '-Force can only be used with -Stop.'
}
if ($Stop -and -not $WhatIfPreference -and
    -not $PSCmdlet.ShouldProcess('Personal Assistant real environment', 'Stop validated services')) {
    return
}

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
$arguments = @(
    $pythonPrefix
    $pythonScript
    '--deepseek-key-file', $DeepSeekKeyFile
    '--default-model-id', $DefaultModelId
    '--aliyun-iqs-key-file', $AliyunIqsKeyFile
    '--continuation-key-file', $ContinuationKeyFile
    '--utility-mcp-directory', $UtilityMcpDirectory
    '--utility-mcp-proxy-url', $UtilityMcpProxyUrl
    '--utility-mcp-proxy-providers', $UtilityMcpProxyProviders
    '--personal-skill-root', $PersonalSkillRoot
    '--startup-timeout-seconds', [string] $StartupTimeoutSeconds
)
if (-not [string]::IsNullOrWhiteSpace($TrustedScriptManifest)) {
    $arguments += @('--trusted-script-manifest', $TrustedScriptManifest)
}
if ($Rebuild) { $arguments += '--rebuild' }
if ($Stop) { $arguments += '--stop' }
if ($Force) { $arguments += '--force' }
if ($WhatIfPreference) { $arguments += '--dry-run' }

& $pythonCommand.Definition @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Real environment command failed with exit code $LASTEXITCODE."
}
