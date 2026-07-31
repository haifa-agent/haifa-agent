[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string] $DeepSeekKeyFile = 'D:\workspace\ss-deepseek.txt',
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

$frontendPort = 20000
$backendPort = 20001
$mcpPort = 20002
$allowedMcpTools = @(
    'location_search',
    'weather_current',
    'weather_forecast',
    'air_quality',
    'time_now',
    'time_convert',
    'currency_rate',
    'currency_convert',
    'holiday_list',
    'holiday_next',
    'workday_is_workday',
    'workday_add',
    'calculate',
    'unit_convert',
    'wikipedia_search',
    'wikipedia_summary',
    'microsoft_docs_search',
    'microsoft_docs_fetch',
    'microsoft_code_sample_search'
) -join ','

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$serverDirectory = Join-Path $repositoryRoot 'haifa-agent-applications\haifa-agent-personal-assistant-server'
$webDirectory = Join-Path $repositoryRoot 'haifa-agent-applications\haifa-agent-personal-assistant-web'
$runtimeDirectory = Join-Path $repositoryRoot 'local-tmp\personal-assistant-real'
$dataDirectory = Join-Path $runtimeDirectory 'data'
$logDirectory = Join-Path $runtimeDirectory 'logs'
$stateFile = Join-Path $runtimeDirectory 'last-start.json'
$stopStateFile = Join-Path $runtimeDirectory 'last-stop.json'
$mavenWrapper = Join-Path $repositoryRoot 'mvnw.cmd'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'

function Get-RequiredCommand {
    param([Parameter(Mandatory)][string] $Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "Required command '$Name' was not found on PATH."
    }
    return $command.Definition
}

function Test-HttpEndpoint {
    param([Parameter(Mandatory)][string] $Uri)

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 3
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 400
    } catch {
        return $false
    }
}

function Test-LocalPort {
    param([Parameter(Mandatory)][int] $Port)

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync('127.0.0.1', $Port)
        if (-not $connect.Wait(500)) {
            return $false
        }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Get-ListeningProcessId {
    param([Parameter(Mandatory)][int] $Port)

    try {
        $processId = Get-NetTCPConnection -LocalPort $Port -State Listen `
                -ErrorAction Stop |
                Select-Object -First 1 -ExpandProperty OwningProcess
        if ($null -ne $processId) {
            return $processId
        }
    } catch {
        # Some restricted Windows sessions cannot query MSFT_NetTCPConnection.
    }

    try {
        $pattern = "^\s*TCP\s+\S+:$Port\s+\S+\s+LISTENING\s+(\d+)\s*$"
        foreach ($line in (& netstat.exe -ano -p tcp)) {
            if ($line -match $pattern) {
                return [int] $Matches[1]
            }
        }
    } catch {
        # PID is diagnostic metadata only; endpoint health remains authoritative.
    }
    return $null
}

function Wait-ForHttpEndpoint {
    param(
        [Parameter(Mandatory)][string] $Name,
        [Parameter(Mandatory)][string] $Uri,
        [Parameter(Mandatory)][System.Diagnostics.Process] $Process,
        [Parameter(Mandatory)][int] $TimeoutSeconds,
        [Parameter(Mandatory)][string] $StandardOutputLog,
        [Parameter(Mandatory)][string] $StandardErrorLog
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($Process.HasExited) {
            $exitCode = 'unknown'
            try {
                $Process.WaitForExit()
                $Process.Refresh()
                if ($null -ne $Process.ExitCode) {
                    $exitCode = [string] $Process.ExitCode
                }
            } catch {
                # Preserve the log paths even if the process handle cannot expose an exit code.
            }
            throw "$Name exited with code $exitCode. Logs: $StandardOutputLog ; $StandardErrorLog"
        }
        if (Test-HttpEndpoint -Uri $Uri) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name did not become healthy within $TimeoutSeconds seconds. Logs: $StandardOutputLog ; $StandardErrorLog"
}

function Start-ProcessWithEnvironment {
    param(
        [Parameter(Mandatory)][string] $FilePath,
        [Parameter(Mandatory)][string[]] $ArgumentList,
        [Parameter(Mandatory)][string] $WorkingDirectory,
        [Parameter(Mandatory)][hashtable] $Environment,
        [Parameter(Mandatory)][string] $StandardOutputLog,
        [Parameter(Mandatory)][string] $StandardErrorLog
    )

    $previousValues = @{}
    foreach ($name in $Environment.Keys) {
        $previousValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, [string] $Environment[$name], 'Process')
    }

    try {
        return Start-Process -FilePath $FilePath `
            -ArgumentList $ArgumentList `
            -WorkingDirectory $WorkingDirectory `
            -WindowStyle Hidden `
            -RedirectStandardOutput $StandardOutputLog `
            -RedirectStandardError $StandardErrorLog `
            -PassThru
    } finally {
        foreach ($name in $Environment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previousValues[$name], 'Process')
        }
    }
}

function New-ContinuationKeyFile {
    param([Parameter(Mandatory)][string] $Path)

    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }

    $bytes = [byte[]]::new(32)
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    [Convert]::ToBase64String($bytes) |
        Set-Content -LiteralPath $Path -Encoding Ascii -NoNewline

    try {
        $acl = Get-Acl -LiteralPath $Path
        $acl.SetAccessRuleProtection($true, $false)
        $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
            $identity,
            [System.Security.AccessControl.FileSystemRights]::FullControl,
            [System.Security.AccessControl.AccessControlType]::Allow
        )
        $acl.SetAccessRule($rule)
        Set-Acl -LiteralPath $Path -AclObject $acl
    } catch {
        Write-Warning "The continuation key was created, but its ACL could not be restricted: $($_.Exception.Message)"
    }
}

function Get-ValidatedServiceProcess {
    param(
        [Parameter(Mandatory)][string] $Role,
        [Parameter(Mandatory)][int] $ProcessId,
        [Parameter(Mandatory)][string] $ExpectedProcessName,
        [Parameter(Mandatory)][string] $ExpectedCommandLineToken
    )

    try {
        $processInfo = & {
            $WhatIfPreference = $false
            Get-CimInstance Win32_Process `
                -Filter "ProcessId = $ProcessId" `
                -ErrorAction Stop
        }
    } catch {
        throw "Cannot inspect $Role PID $ProcessId. No process was stopped: $($_.Exception.Message)"
    }
    if ($null -eq $processInfo) {
        throw "$Role PID $ProcessId no longer exists. No process was stopped."
    }
    if (-not $processInfo.Name.Equals(
            $ExpectedProcessName,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw "$Role PID $ProcessId is '$($processInfo.Name)', expected '$ExpectedProcessName'. No process was stopped."
    }
    if ([string]::IsNullOrWhiteSpace($processInfo.CommandLine) -or
        $processInfo.CommandLine.IndexOf(
            $ExpectedCommandLineToken,
            [System.StringComparison]::OrdinalIgnoreCase
        ) -lt 0) {
        throw "$Role PID $ProcessId command line does not contain '$ExpectedCommandLineToken'. No process was stopped."
    }
    return $processInfo
}

function Wait-ForPortRelease {
    param(
        [Parameter(Mandatory)][int] $Port,
        [ValidateRange(1, 120)][int] $TimeoutSeconds = 30
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Test-LocalPort -Port $Port)) {
            return
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Port $Port was not released within $TimeoutSeconds seconds."
}

if ($Stop -and $Rebuild) {
    throw '-Stop and -Rebuild cannot be used together.'
}
if ($Force -and -not $Stop) {
    throw '-Force can only be used with -Stop.'
}

if ($Stop) {
    $startRecords = @()
    if (Test-Path -LiteralPath $stateFile -PathType Leaf) {
        try {
            $parsedStartRecords = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
            $startRecords = @($parsedStartRecords)
        } catch {
            if (-not $Force) {
                throw
            }
            Write-Warning "Startup state file could not be read. Force stop will use the current port listeners: $stateFile"
        }
    } elseif ($Force) {
        Write-Warning "Startup state file was not found. Force stop will use the current port listeners: $stateFile"
    } else {
        throw "Startup state file was not found: $stateFile. No process was stopped."
    }

    $serviceDefinitions = @(
        [pscustomobject]@{
            Role = 'personal-web'
            Port = $frontendPort
            ProcessName = 'node.exe'
            CommandLineToken = $webDirectory
        },
        [pscustomobject]@{
            Role = 'personal-backend'
            Port = $backendPort
            ProcessName = 'java.exe'
            CommandLineToken = $serverDirectory
        },
        [pscustomobject]@{
            Role = 'utility-mcp'
            Port = $mcpPort
            ProcessName = 'java.exe'
            CommandLineToken = 'org.wrj.haifa.ai.utilitymcp.UtilityMcpServerApplication'
        }
    )

    $stopTargets = [System.Collections.Generic.List[object]]::new()
    $stopResults = [System.Collections.Generic.List[object]]::new()
    foreach ($definition in $serviceDefinitions) {
        $record = $startRecords |
            Where-Object Role -EQ $definition.Role |
            Select-Object -First 1
        $listeningProcessId = Get-ListeningProcessId -Port $definition.Port
        if ($null -eq $listeningProcessId) {
            $stopResults.Add([pscustomobject]@{
                    Role = $definition.Role
                    Status = 'already-stopped'
                    Pid = $null
                    Port = $definition.Port
                })
            continue
        }
        if ($null -eq $record -or $null -eq $record.Pid) {
            if (-not $Force) {
                throw "No recorded PID exists for $($definition.Role), but port $($definition.Port) is listening. No process was stopped."
            }
            Write-Warning "No recorded PID exists for $($definition.Role). Force stop will target current listener PID $listeningProcessId on port $($definition.Port)."
        } elseif ([int] $record.Pid -ne [int] $listeningProcessId) {
            if (-not $Force) {
                throw "$($definition.Role) port $($definition.Port) belongs to PID $listeningProcessId, but state records PID $($record.Pid). No process was stopped."
            }
            Write-Warning "$($definition.Role) port $($definition.Port) belongs to PID $listeningProcessId, but state records PID $($record.Pid). Force stop will target the current listener."
        }

        $processInfo = $null
        try {
            $processInfo = Get-ValidatedServiceProcess `
                -Role $definition.Role `
                -ProcessId $listeningProcessId `
                -ExpectedProcessName $definition.ProcessName `
                -ExpectedCommandLineToken $definition.CommandLineToken
        } catch {
            if (-not $Force) {
                throw
            }
            Write-Warning "$($_.Exception.Message) Force stop will target current listener PID $listeningProcessId on port $($definition.Port)."
        }
        $stopTargets.Add([pscustomobject]@{
                Role = $definition.Role
                Port = $definition.Port
                Pid = [int] $listeningProcessId
                Process = $processInfo
            })
    }

    foreach ($target in $stopTargets) {
        $description = "$($target.Role) PID $($target.Pid) on port $($target.Port)"
        $stopAction = if ($Force) {
            'Force-stop current Personal Assistant port listener'
        } else {
            'Stop validated Personal Assistant service'
        }
        if ($PSCmdlet.ShouldProcess($description, $stopAction)) {
            Stop-Process -Id $target.Pid -Force:$Force -ErrorAction Stop
            Wait-ForPortRelease -Port $target.Port
            $stopResults.Add([pscustomobject]@{
                    Role = $target.Role
                    Status = if ($Force) { 'force-stopped' } else { 'stopped' }
                    Pid = $target.Pid
                    Port = $target.Port
                })
        }
    }

    if (-not $WhatIfPreference) {
        $stopResults |
            ConvertTo-Json -Depth 4 |
            Set-Content -LiteralPath $stopStateFile -Encoding UTF8
    }

    Write-Host ''
    Write-Host 'Personal Assistant stop validation completed.'
    if ($stopResults.Count -gt 0) {
        $stopResults | Format-Table Role, Status, Pid, Port -AutoSize
    }
    if ($WhatIfPreference) {
        Write-Host 'WhatIf was enabled; no process was stopped.'
    } else {
        Write-Host "Stop state: $stopStateFile"
    }
    exit 0
}

if (-not (Test-Path -LiteralPath $DeepSeekKeyFile -PathType Leaf)) {
    throw "DeepSeek key file was not found: $DeepSeekKeyFile"
}
if (-not (Test-Path -LiteralPath $AliyunIqsKeyFile -PathType Leaf)) {
    throw "Aliyun IQS key file was not found: $AliyunIqsKeyFile"
}
if (-not (Test-Path -LiteralPath $PersonalSkillRoot -PathType Container)) {
    throw "Personal Skill root was not found: $PersonalSkillRoot"
}
$personalSkillPackages = @(
    Get-ChildItem -LiteralPath $PersonalSkillRoot -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'SKILL.md') -PathType Leaf }
)
if ($personalSkillPackages.Count -eq 0) {
    throw "Personal Skill root contains no immediate child with SKILL.md: $PersonalSkillRoot"
}
if (-not (Test-Path -LiteralPath $UtilityMcpDirectory -PathType Container)) {
    throw "Utility MCP directory was not found: $UtilityMcpDirectory"
}
if (-not (Test-Path -LiteralPath (Join-Path $UtilityMcpDirectory 'pom.xml') -PathType Leaf)) {
    throw "Utility MCP pom.xml was not found under: $UtilityMcpDirectory"
}
if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
    throw "Maven wrapper was not found: $mavenWrapper"
}

$deepSeekApiKey = (Get-Content -LiteralPath $DeepSeekKeyFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($deepSeekApiKey)) {
    throw "DeepSeek key file is empty: $DeepSeekKeyFile"
}
$aliyunIqsApiKey = (Get-Content -LiteralPath $AliyunIqsKeyFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($aliyunIqsApiKey)) {
    throw "Aliyun IQS key file is empty: $AliyunIqsKeyFile"
}
$personalSkillRoot = (Resolve-Path -LiteralPath $PersonalSkillRoot).Path
$trustedScriptManifestPath = ''
if (-not [string]::IsNullOrWhiteSpace($TrustedScriptManifest)) {
    $trustedScriptManifestPath = (Resolve-Path -LiteralPath $TrustedScriptManifest).Path
    if (-not (Test-Path -LiteralPath $trustedScriptManifestPath -PathType Leaf)) {
        throw "Trusted script manifest is not a file: $trustedScriptManifestPath"
    }
}

if (-not (Test-Path -LiteralPath $ContinuationKeyFile -PathType Leaf)) {
    New-ContinuationKeyFile -Path $ContinuationKeyFile
    Write-Host "Created a persistent continuation key file: $ContinuationKeyFile"
}
$continuationKey = (Get-Content -LiteralPath $ContinuationKeyFile -Raw).Trim()
try {
    $decodedContinuationKey = [Convert]::FromBase64String($continuationKey)
} catch {
    throw "Continuation key file does not contain valid Base64: $ContinuationKeyFile"
}
if ($decodedContinuationKey.Length -ne 32) {
    throw "Continuation key must decode to exactly 32 bytes: $ContinuationKeyFile"
}

$java = Get-RequiredCommand -Name 'java.exe'
$node = Get-RequiredCommand -Name 'node.exe'
$npm = Get-RequiredCommand -Name 'npm.cmd'
$maven = Get-RequiredCommand -Name 'mvn.cmd'

New-Item -ItemType Directory -Force -Path $dataDirectory, $logDirectory | Out-Null

if ($Rebuild -and (
        (Test-LocalPort -Port $frontendPort) -or
        (Test-LocalPort -Port $backendPort) -or
        (Test-LocalPort -Port $mcpPort)
    )) {
    throw 'Rebuild requires ports 20000, 20001, and 20002 to be free. Stop the existing environment first.'
}

$serverJar = Get-ChildItem -LiteralPath (Join-Path $serverDirectory 'target') `
    -Filter 'haifa-agent-personal-assistant-server-*.jar' `
    -File `
    -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($Rebuild -or $null -eq $serverJar) {
    Write-Host 'Building the Personal Assistant backend...'
    & $mavenWrapper -pl ':haifa-agent-personal-assistant-server' -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) {
        throw "Backend build failed with exit code $LASTEXITCODE."
    }
    $serverJar = Get-ChildItem -LiteralPath (Join-Path $serverDirectory 'target') `
        -Filter 'haifa-agent-personal-assistant-server-*.jar' `
        -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

$serveScript = Join-Path $webDirectory 'node_modules\serve\build\main.js'
if (-not (Test-Path -LiteralPath $serveScript -PathType Leaf)) {
    Write-Host 'Installing locked frontend dependencies...'
    Push-Location $webDirectory
    try {
        & $npm ci
        if ($LASTEXITCODE -ne 0) {
            throw "npm ci failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
}

$frontendIndex = Join-Path $webDirectory 'dist\index.html'
if ($Rebuild -or -not (Test-Path -LiteralPath $frontendIndex -PathType Leaf)) {
    Write-Host 'Building the standalone Personal Assistant frontend...'
    $previousApiBaseUrl = [Environment]::GetEnvironmentVariable(
        'VITE_PERSONAL_ASSISTANT_API_BASE_URL',
        'Process'
    )
    [Environment]::SetEnvironmentVariable(
        'VITE_PERSONAL_ASSISTANT_API_BASE_URL',
        "http://127.0.0.1:$backendPort/api/v1",
        'Process'
    )
    Push-Location $webDirectory
    try {
        & $npm run build
        if ($LASTEXITCODE -ne 0) {
            throw "Frontend build failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
        [Environment]::SetEnvironmentVariable(
            'VITE_PERSONAL_ASSISTANT_API_BASE_URL',
            $previousApiBaseUrl,
            'Process'
        )
    }
}

$services = [System.Collections.Generic.List[object]]::new()

$mcpHealthUri = "http://127.0.0.1:$mcpPort/actuator/health"
if (Test-HttpEndpoint -Uri $mcpHealthUri) {
    $services.Add([pscustomobject]@{
            Role = 'utility-mcp'
            Status = 'reused'
            Pid = Get-ListeningProcessId -Port $mcpPort
            Url = $mcpHealthUri
            WorkDirectory = $UtilityMcpDirectory
            Stdout = $null
            Stderr = $null
        })
} elseif (Test-LocalPort -Port $mcpPort) {
    throw "Port $mcpPort is occupied, but Utility MCP health check failed. No process was stopped."
} else {
    $mcpStdout = Join-Path $logDirectory "utility-mcp-$timestamp.out.log"
    $mcpStderr = Join-Path $logDirectory "utility-mcp-$timestamp.err.log"
    $mcpProcess = Start-ProcessWithEnvironment `
        -FilePath $maven `
        -ArgumentList @('spring-boot:run') `
        -WorkingDirectory $UtilityMcpDirectory `
        -Environment @{
            UTILITY_MCP_PORT = [string] $mcpPort
            UTILITY_MCP_PROXY_URL = $UtilityMcpProxyUrl
            UTILITY_MCP_PROXY_PROVIDERS = $UtilityMcpProxyProviders
        } `
        -StandardOutputLog $mcpStdout `
        -StandardErrorLog $mcpStderr
    Wait-ForHttpEndpoint `
        -Name 'Utility MCP' `
        -Uri $mcpHealthUri `
        -Process $mcpProcess `
        -TimeoutSeconds $StartupTimeoutSeconds `
        -StandardOutputLog $mcpStdout `
        -StandardErrorLog $mcpStderr
    $services.Add([pscustomobject]@{
            Role = 'utility-mcp'
            Status = 'started'
            Pid = Get-ListeningProcessId -Port $mcpPort
            Url = $mcpHealthUri
            WorkDirectory = $UtilityMcpDirectory
            Stdout = $mcpStdout
            Stderr = $mcpStderr
        })
}

$backendHealthUri = "http://127.0.0.1:$backendPort/actuator/health"
if (Test-HttpEndpoint -Uri $backendHealthUri) {
    $services.Add([pscustomobject]@{
            Role = 'personal-backend'
            Status = 'reused'
            Pid = Get-ListeningProcessId -Port $backendPort
            Url = $backendHealthUri
            WorkDirectory = $serverDirectory
            Stdout = $null
            Stderr = $null
        })
} elseif (Test-LocalPort -Port $backendPort) {
    throw "Port $backendPort is occupied, but Personal Assistant health check failed. No process was stopped."
} else {
    $backendStdout = Join-Path $logDirectory "personal-backend-$timestamp.out.log"
    $backendStderr = Join-Path $logDirectory "personal-backend-$timestamp.err.log"
    $backendEnvironment = @{
        DEEPSEEK_API_KEY = $deepSeekApiKey
        ALIYUN_IQS_API_KEY = $aliyunIqsApiKey
        HAIFA_PERSONAL_CONTINUATION_KEY = $continuationKey
        HAIFA_PERSONAL_DATA_DIR = $dataDirectory
        HAIFA_PERSONAL_DEFAULT_MODEL_ID = 'deepseek-v4-flash'
        HAIFA_PERSONAL_MODELPROVIDERS_0_ID = 'deepseek'
        HAIFA_PERSONAL_MODELPROVIDERS_0_DISPLAYNAME = 'DeepSeek'
        HAIFA_PERSONAL_MODELPROVIDERS_0_MODE = 'remote'
        HAIFA_PERSONAL_MODELPROVIDERS_0_ALLOWDETERMINISTIC = 'false'
        HAIFA_PERSONAL_MODELPROVIDERS_0_ENDPOINT = 'https://api.deepseek.com'
        HAIFA_PERSONAL_MODELPROVIDERS_0_CREDENTIALREFERENCE = 'env://DEEPSEEK_API_KEY'
        HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_ID = 'deepseek-v4-pro'
        HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_DISPLAYNAME = 'DeepSeek V4 Pro'
        HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_0_PROVIDERMODELID = 'deepseek-v4-pro'
        HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_ID = 'deepseek-v4-flash'
        HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_DISPLAYNAME = 'DeepSeek V4 Flash'
        HAIFA_PERSONAL_MODELPROVIDERS_0_MODELS_1_PROVIDERMODELID = 'deepseek-v4-flash'
        HAIFA_PERSONAL_WEB_ENABLED = 'true'
        HAIFA_PERSONAL_WEB_CREDENTIAL = 'env://ALIYUN_IQS_API_KEY'
        HAIFA_PERSONAL_SKILL_ROOT = $personalSkillRoot
        HAIFA_PERSONAL_TRUSTED_SCRIPT_MANIFEST = $trustedScriptManifestPath
        HAIFA_PERSONAL_MCP_MODE = 'external'
        HAIFA_PERSONAL_MCP_ENDPOINT = "http://127.0.0.1:$mcpPort/mcp"
        HAIFA_PERSONAL_MCP_ALLOWED_TOOLS = $allowedMcpTools
        HAIFA_PERSONAL_MCP_ALIAS_NAMESPACE = 'utility'
        HAIFA_PERSONAL_MCP_SERVER_ID = 'haifa-utility'
        HAIFA_PERSONAL_MCP_DISPLAY_NAME = 'Haifa Utility MCP'
        HAIFA_PERSONAL_EXECUTION_TRUSTED_HOST_ENABLED = 'true'
    }
    $backendProcess = Start-ProcessWithEnvironment `
        -FilePath $java `
        -ArgumentList @('-jar', $serverJar.FullName) `
        -WorkingDirectory $serverDirectory `
        -Environment $backendEnvironment `
        -StandardOutputLog $backendStdout `
        -StandardErrorLog $backendStderr
    Wait-ForHttpEndpoint `
        -Name 'Personal Assistant backend' `
        -Uri $backendHealthUri `
        -Process $backendProcess `
        -TimeoutSeconds $StartupTimeoutSeconds `
        -StandardOutputLog $backendStdout `
        -StandardErrorLog $backendStderr
    $services.Add([pscustomobject]@{
            Role = 'personal-backend'
            Status = 'started'
            Pid = Get-ListeningProcessId -Port $backendPort
            Url = $backendHealthUri
            WorkDirectory = $serverDirectory
            Stdout = $backendStdout
            Stderr = $backendStderr
        })
}

$frontendUri = "http://127.0.0.1:$frontendPort/"
if (Test-HttpEndpoint -Uri $frontendUri) {
    $services.Add([pscustomobject]@{
            Role = 'personal-web'
            Status = 'reused'
            Pid = Get-ListeningProcessId -Port $frontendPort
            Url = $frontendUri
            WorkDirectory = $webDirectory
            Stdout = $null
            Stderr = $null
        })
} elseif (Test-LocalPort -Port $frontendPort) {
    throw "Port $frontendPort is occupied, but the Personal Assistant frontend check failed. No process was stopped."
} else {
    $frontendStdout = Join-Path $logDirectory "personal-web-$timestamp.out.log"
    $frontendStderr = Join-Path $logDirectory "personal-web-$timestamp.err.log"
    $frontendProcess = Start-Process -FilePath $node `
        -ArgumentList @(
            $serveScript,
            '-s',
            (Join-Path $webDirectory 'dist'),
            '-l',
            "tcp://127.0.0.1:$frontendPort",
            '--no-clipboard'
        ) `
        -WorkingDirectory $webDirectory `
        -WindowStyle Hidden `
        -RedirectStandardOutput $frontendStdout `
        -RedirectStandardError $frontendStderr `
        -PassThru
    Wait-ForHttpEndpoint `
        -Name 'Personal Assistant frontend' `
        -Uri $frontendUri `
        -Process $frontendProcess `
        -TimeoutSeconds $StartupTimeoutSeconds `
        -StandardOutputLog $frontendStdout `
        -StandardErrorLog $frontendStderr
    $services.Add([pscustomobject]@{
            Role = 'personal-web'
            Status = 'started'
            Pid = Get-ListeningProcessId -Port $frontendPort
            Url = $frontendUri
            WorkDirectory = $webDirectory
            Stdout = $frontendStdout
            Stderr = $frontendStderr
        })
}

$services |
    ConvertTo-Json -Depth 4 |
    Set-Content -LiteralPath $stateFile -Encoding UTF8

Write-Host ''
Write-Host 'Real Personal Assistant environment is ready.'
$services | Format-Table Role, Status, Pid, Url -AutoSize
Write-Host ''
Write-Host 'Work directories:'
Write-Host "  Repository:       $repositoryRoot"
Write-Host "  Personal Web:     $webDirectory"
Write-Host "  Personal Server:  $serverDirectory"
Write-Host "  Utility MCP:      $UtilityMcpDirectory"
Write-Host "  Utility Proxy:    $UtilityMcpProxyUrl ($UtilityMcpProxyProviders)"
Write-Host "  Personal Skills:  $personalSkillRoot"
if (-not [string]::IsNullOrWhiteSpace($trustedScriptManifestPath)) {
    Write-Host "  Trust Manifest:   $trustedScriptManifestPath"
}
Write-Host "  Runtime data:     $dataDirectory"
Write-Host "  Runtime logs:     $logDirectory"
Write-Host ''
Write-Host 'Access addresses:'
Write-Host "  Personal Web:     $frontendUri"
Write-Host "  Personal API:     http://127.0.0.1:$backendPort/api/v1"
Write-Host "  Backend health:   $backendHealthUri"
Write-Host "  Backend OpenAPI:  http://127.0.0.1:$backendPort/api/v1/openapi.json"
Write-Host "  Utility MCP:      http://127.0.0.1:$mcpPort/mcp"
Write-Host "  MCP health:       $mcpHealthUri"
Write-Host '  Web Tools:        web.search, web.fetch (Aliyun IQS)'
Write-Host ''
Write-Host "State: $stateFile"
Write-Host "Logs:  $logDirectory"
Write-Host 'Secrets were loaded into child process environment only and were not printed.'
