#Requires -Version 7
<#
.SYNOPSIS
    Shared support functions for versioned Haifa SDK publishing and validation.
.DESCRIPTION
    Provides reusable logic for:
    - Resolving publishing version and enforcing branch/worktree rules
    - Querying and validating Maven local repository paths
    - Verifying coordinate ownership across worktrees
    - Validating reactor module classification to prevent drift
    - Verifying published artifacts and writing atomic release manifests
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-CleanWorkingTree {
    param([string]$RepoRoot)

    $status = (git -C $RepoRoot status --porcelain)
    if (-not [string]::IsNullOrWhiteSpace($status)) {
        throw "Working tree is dirty at '$RepoRoot'. Only clean working trees can be published. Commit or stash changes first."
    }
}

function Resolve-WorktreePublishVersion {
    param([string]$RepoRoot)

    $sdkCommit = (git -C $RepoRoot rev-parse HEAD).Trim()
    $branch = (git -C $RepoRoot branch --show-current).Trim()

    $gitDir = (git -C $RepoRoot rev-parse --git-dir).Trim()
    $gitCommonDir = (git -C $RepoRoot rev-parse --git-common-dir).Trim()

    $resolvedGitDir = if ([System.IO.Path]::IsPathRooted($gitDir)) { (Resolve-Path $gitDir).Path } else { (Resolve-Path (Join-Path $RepoRoot $gitDir)).Path }
    $resolvedCommonDir = if ([System.IO.Path]::IsPathRooted($gitCommonDir)) { (Resolve-Path $gitCommonDir).Path } else { (Resolve-Path (Join-Path $RepoRoot $gitCommonDir)).Path }
    $isMainCheckout = ($resolvedGitDir -eq $resolvedCommonDir -or $resolvedGitDir -eq (Join-Path $RepoRoot '.git'))

    if ($isMainCheckout) {
        if ($branch -ne 'dev') {
            throw "Main checkout must be on branch 'dev' to publish baseline 0.1.2-SNAPSHOT (current branch: '$branch')."
        }
        $identifier = 'baseline'
        $version = '0.1.2-SNAPSHOT'
    } else {
        $identifier = Split-Path -Leaf $RepoRoot
        if ($identifier -notmatch '^[A-Za-z0-9.-]+$') {
            throw "Worktree identifier '$identifier' contains illegal characters. Only [A-Za-z0-9.-] are permitted."
        }
        if ($identifier -eq 'dev' -or $identifier -eq 'main' -or $identifier -eq 'baseline') {
            throw "Worktree '$identifier' cannot publish to baseline coordinates."
        }
        $version = "0.1.2-$identifier-SNAPSHOT"
    }

    return [PSCustomObject]@{
        Version = $version
        Identifier = $identifier
        IsBaseline = $isMainCheckout
        SdkCommit = $sdkCommit
        Branch = $branch
        WorktreePath = $RepoRoot
    }
}

function Get-MavenLocalRepository {
    param(
        [string]$RepoRoot,
        [string]$ExplicitRepo = $null
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitRepo)) {
        return (Resolve-Path $ExplicitRepo).Path
    }

    # Query Maven settings for the active local repository
    try {
        $out = & mvn.cmd help:evaluate "-Dexpression=settings.localRepository" -q -DforceStdout
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($out)) {
            $trimmed = $out.Trim()
            if (Test-Path $trimmed) {
                return (Resolve-Path $trimmed).Path
            }
            return $trimmed
        }
    } catch {}

    if (Test-Path 'D:\DevCache\maven\repository') {
        return (Resolve-Path 'D:\DevCache\maven\repository').Path
    }

    return [System.IO.Path]::Combine($HOME, '.m2', 'repository')
}

function Assert-CoordinateOwnership {
    param(
        [string]$LocalRepo,
        [string]$Version,
        [string]$CurrentWorktreePath
    )

    $bomDir = Join-Path $LocalRepo "io\haifa\haifa-agent-bom\$Version"
    $manifestPath = Join-Path $bomDir 'haifa-sdk-publish-manifest.json'

    if (Test-Path $manifestPath) {
        $raw = $null
        try {
            $raw = Get-Content $manifestPath -Raw -Encoding utf8
        } catch {
            throw "Failed to inspect existing manifest at '$manifestPath': $($_.Exception.Message)"
        }
        $existing = $raw | ConvertFrom-Json
        $ownerPath = $existing.worktreePath

        $ownerNorm = if ($ownerPath -and (Test-Path $ownerPath)) { (Resolve-Path $ownerPath).Path } else { $ownerPath }
        $currentNorm = if (Test-Path $CurrentWorktreePath) { (Resolve-Path $CurrentWorktreePath).Path } else { $CurrentWorktreePath }

        if ($ownerNorm -and ($ownerNorm -ne $currentNorm)) {
            throw "Coordinate '$Version' is already owned by worktree '$ownerPath'. Cannot publish from '$CurrentWorktreePath'."
        }

        # Clear previous manifest before new publication begins
        try {
            Remove-Item -Force $manifestPath -ErrorAction Stop
        } catch {
            throw "Failed to remove existing manifest at '$manifestPath': $($_.Exception.Message)"
        }
        if (Test-Path $manifestPath) {
            throw "Failed to remove existing manifest at '$manifestPath'. The file may be locked by another process."
        }
    }
}

function Remove-ProductArtifacts {
    param(
        [string]$RepoRoot,
        [string]$LocalRepo,
        [string]$Version,
        [PSCustomObject]$Classification
    )

    foreach ($prodDir in $Classification.ProductModules) {
        $pomPath = Join-Path $RepoRoot (Join-Path $prodDir 'pom.xml')
        if (Test-Path $pomPath) {
            [xml]$pXml = Get-Content $pomPath
            $pArtifactId = $pXml.project.artifactId
            $targetDir = Join-Path $LocalRepo "io\haifa\$pArtifactId\$Version"
            if (Test-Path $targetDir) {
                Remove-Item -Recurse -Force $targetDir -ErrorAction SilentlyContinue
                if (Test-Path $targetDir) {
                    throw "Failed to remove product artifacts directory at '$targetDir'."
                }
            }
        }
    }
}

function Invoke-MavenProcess {
    param(
        [string]$RepoRoot,
        [string[]]$Arguments
    )

    $formattedArgs = @()
    foreach ($arg in $Arguments) {
        if ($arg.Contains(' ') -and -not ($arg.StartsWith('"') -and $arg.EndsWith('"'))) {
            if ($arg.Contains('=')) {
                $idx = $arg.IndexOf('=')
                $key = $arg.Substring(0, $idx)
                $val = $arg.Substring($idx + 1).Trim('"')
                $formattedArgs += "$key=`"$val`""
            } else {
                $formattedArgs += "`"$arg`""
            }
        } else {
            $formattedArgs += $arg
        }
    }

    $proc = Start-Process -FilePath 'mvn.cmd' -ArgumentList $formattedArgs -WorkingDirectory $RepoRoot -NoNewWindow -Wait -PassThru
    return $proc
}

function Get-ReactorModuleClassification {
    param([string]$RepoRoot)

    $publicModuleDirs = @(
        'haifa-agent-contract',
        'haifa-agent-kernel\haifa-agent-artifact',
        'haifa-agent-kernel\haifa-agent-common',
        'haifa-agent-kernel\haifa-agent-context',
        'haifa-agent-kernel\haifa-agent-core',
        'haifa-agent-kernel\haifa-agent-project-api',
        'haifa-agent-kernel\haifa-agent-project-core',
        'haifa-agent-kernel\haifa-agent-project-host',
        'haifa-agent-kernel\haifa-agent-runtime-api',
        'haifa-agent-kernel\haifa-agent-runtime-core',
        'haifa-agent-execution\haifa-agent-execution-api',
        'haifa-agent-execution\haifa-agent-execution-core',
        'haifa-agent-execution\haifa-agent-execution-host',
        'haifa-agent-execution\haifa-agent-sandbox-api',
        'haifa-agent-execution\haifa-agent-sandbox-host',
        'haifa-agent-capabilities\haifa-agent-credential-api',
        'haifa-agent-capabilities\haifa-agent-credential-core',
        'haifa-agent-capabilities\haifa-agent-memory-api',
        'haifa-agent-capabilities\haifa-agent-memory-core',
        'haifa-agent-capabilities\haifa-agent-model-api',
        'haifa-agent-capabilities\haifa-agent-model-core',
        'haifa-agent-capabilities\haifa-agent-policy-api',
        'haifa-agent-capabilities\haifa-agent-policy-core',
        'haifa-agent-capabilities\haifa-agent-skill-api',
        'haifa-agent-capabilities\haifa-agent-skill-base',
        'haifa-agent-capabilities\haifa-agent-skill-core',
        'haifa-agent-capabilities\haifa-agent-tool-api',
        'haifa-agent-capabilities\haifa-agent-tool-core',
        'haifa-agent-sdk',
        'haifa-agent-integrations\haifa-agent-git',
        'haifa-agent-integrations\haifa-agent-google-gemini',
        'haifa-agent-integrations\haifa-agent-local-model-auth',
        'haifa-agent-integrations\haifa-agent-local-model-auth-antigravity-local-compat',
        'haifa-agent-integrations\haifa-agent-local-model-auth-codex-local-compat',
        'haifa-agent-integrations\haifa-agent-mcp',
        'haifa-agent-integrations\haifa-agent-model-anthropic',
        'haifa-agent-integrations\haifa-agent-model-openai-compatible',
        'haifa-agent-integrations\haifa-agent-store-jsonl',
        'haifa-agent-integrations\haifa-agent-store-sqlite',
        'haifa-agent-integrations\haifa-agent-transport-http',
        'haifa-agent-integrations\haifa-agent-web',
        'haifa-agent-sdk-starter',
        'haifa-agent-spring\haifa-agent-spring-boot-autoconfigure',
        'haifa-agent-spring\haifa-agent-spring-boot-starter'
    )

    $bomDirs = @(
        'build-support\haifa-agent-bom',
        'build-support\haifa-agent-spring-bom'
    )

    $productModuleDirs = @(
        'haifa-agent-applications\haifa-agent-cli',
        'haifa-agent-applications\haifa-agent-coding-agent',
        'haifa-agent-applications\haifa-agent-coding-terminal',
        'haifa-agent-applications\haifa-agent-personal-assistant-application',
        'haifa-agent-applications\haifa-agent-personal-assistant-server',
        'haifa-agent-applications\haifa-agent-sdk-example',
        'haifa-agent-applications\haifa-agent-runtime-demo',
        'haifa-agent-testing\haifa-agent-test-fixtures',
        'haifa-agent-testing\haifa-agent-integration-tests',
        'haifa-agent-testing\haifa-agent-e2e-tests',
        'haifa-agent-testing\haifa-agent-autonomous-delivery'
    )

    $aggregatorPomDirs = @(
        '.',
        'haifa-agent-kernel',
        'haifa-agent-execution',
        'haifa-agent-capabilities',
        'haifa-agent-integrations',
        'haifa-agent-spring',
        'build-support',
        'haifa-agent-applications',
        'haifa-agent-testing'
    )

    $allKnown = @{}
    foreach ($p in $publicModuleDirs) { $allKnown[(Resolve-Path (Join-Path $RepoRoot $p)).Path] = 'public' }
    foreach ($b in $bomDirs) { $allKnown[(Resolve-Path (Join-Path $RepoRoot $b)).Path] = 'bom' }
    foreach ($m in $productModuleDirs) { $allKnown[(Resolve-Path (Join-Path $RepoRoot $m)).Path] = 'product' }
    foreach ($a in $aggregatorPomDirs) { $allKnown[(Resolve-Path (Join-Path $RepoRoot $a)).Path] = 'aggregator' }

    # Dynamically discover reactor modules by traversing <modules> from root pom.xml
    function Discover-ReactorPoms([string]$CurrentPom) {
        $found = @($CurrentPom)
        [xml]$pXml = Get-Content $CurrentPom
        $parentDir = Split-Path -Parent $CurrentPom
        $modNodes = $pXml.SelectNodes('/*[local-name()="project"]/*[local-name()="modules"]/*[local-name()="module"]')
        if ($modNodes -and $modNodes.Count -gt 0) {
            foreach ($mNode in $modNodes) {
                $subDir = Join-Path $parentDir $mNode.InnerText.Trim()
                $subPom = Join-Path $subDir 'pom.xml'
                if (Test-Path $subPom) {
                    $found += Discover-ReactorPoms $subPom
                }
            }
        }
        return $found
    }

    $reactorPoms = Discover-ReactorPoms (Join-Path $RepoRoot 'pom.xml')

    foreach ($pomPath in $reactorPoms) {
        $dir = (Resolve-Path (Split-Path -Parent $pomPath)).Path
        if (-not $allKnown.ContainsKey($dir)) {
            [xml]$xml = Get-Content $pomPath
            $artNode = $xml.SelectSingleNode('/*[local-name()="project"]/*[local-name()="artifactId"]')
            $art = if ($artNode) { $artNode.InnerText.Trim() } else { 'unknown' }
            throw "Unclassified reactor module '$art' detected at '$dir'. Must be registered in either public or product module list."
        }
    }

    return [PSCustomObject]@{
        PublicModules = $publicModuleDirs
        Boms = $bomDirs
        ProductModules = $productModuleDirs
    }
}

function Assert-InstalledPomValid {
    param(
        [string]$InstalledPomPath,
        [string]$ArtifactId,
        [string]$ExpectedVersion
    )

    if (-not (Test-Path $InstalledPomPath)) {
        throw "Installed POM not found for '$ArtifactId' at '$InstalledPomPath'."
    }
    $pomContent = Get-Content $InstalledPomPath -Raw -Encoding utf8
    if ($pomContent.Contains('${revision}')) {
        throw "Installed POM for '$ArtifactId' still contains unresolved `${revision}`."
    }

    [xml]$xml = $pomContent
    $versionNode = $xml.SelectSingleNode('/*[local-name()="project"]/*[local-name()="version"]')
    $parentVersionNode = $xml.SelectSingleNode('/*[local-name()="project"]/*[local-name()="parent"]/*[local-name()="version"]')

    $resolvedVersion = if ($versionNode) { $versionNode.InnerText.Trim() } elseif ($parentVersionNode) { $parentVersionNode.InnerText.Trim() } else { $null }

    if ($resolvedVersion -ne $ExpectedVersion) {
        throw "Installed POM for '$ArtifactId' has version '$resolvedVersion', expected '$ExpectedVersion'."
    }
    if ($parentVersionNode) {
        $parentVer = $parentVersionNode.InnerText.Trim()
        if ($parentVer -ne $ExpectedVersion) {
            throw "Installed POM for '$ArtifactId' has parent version '$parentVer', expected '$ExpectedVersion'."
        }
    }
}

function Verify-InstalledArtifacts {
    param(
        [string]$RepoRoot,
        [string]$LocalRepo,
        [string]$Version,
        [PSCustomObject]$Classification
    )

    # Verify BOM POMs exist, do not retain ${revision}, and declare target version
    foreach ($bRel in $Classification.Boms) {
        $bPomPath = Join-Path $RepoRoot (Join-Path $bRel 'pom.xml')
        [xml]$bXml = Get-Content $bPomPath
        $bArtifactId = $bXml.project.artifactId
        $installedPom = Join-Path $LocalRepo "io\haifa\$bArtifactId\$Version\$bArtifactId-$Version.pom"
        Assert-InstalledPomValid -InstalledPomPath $installedPom -ArtifactId $bArtifactId -ExpectedVersion $Version
    }

    $publishedArtifacts = @()

    foreach ($relDir in $Classification.PublicModules) {
        $modPath = Join-Path $RepoRoot $relDir
        $pomFile = Join-Path $modPath 'pom.xml'
        if (-not (Test-Path $pomFile)) {
            throw "Module pom.xml not found at '$pomFile'."
        }
        [xml]$modXml = Get-Content $pomFile
        $artifactId = $modXml.project.artifactId

        $installedPom = Join-Path $LocalRepo "io\haifa\$artifactId\$Version\$artifactId-$Version.pom"
        Assert-InstalledPomValid -InstalledPomPath $installedPom -ArtifactId $artifactId -ExpectedVersion $Version

        $installedJar = Join-Path $LocalRepo "io\haifa\$artifactId\$Version\$artifactId-$Version.jar"
        if (-not (Test-Path $installedJar)) {
            throw "Installed JAR not found for '$artifactId' at '$installedJar'."
        }

        $targetJar = Join-Path $modPath "target\$artifactId-$Version.jar"
        if (-not (Test-Path $targetJar)) {
            throw "Target JAR not found at '$targetJar'."
        }

        $targetHash = (Get-FileHash -Path $targetJar -Algorithm SHA256).Hash.ToLower()
        $installedHash = (Get-FileHash -Path $installedJar -Algorithm SHA256).Hash.ToLower()
        if ($targetHash -ne $installedHash) {
            throw "SHA-256 hash mismatch for '$artifactId': target is $targetHash, installed is $installedHash."
        }

        $fileItem = Get-Item $installedJar
        $publishedArtifacts += [ordered]@{
            groupId = 'io.haifa'
            artifactId = $artifactId
            version = $Version
            jarFileName = "$artifactId-$Version.jar"
            sha256 = $installedHash
            bytes = $fileItem.Length
        }
    }

    return $publishedArtifacts
}

function Write-PublishManifest {
    param(
        [string]$LocalRepo,
        [string]$Version,
        [PSCustomObject]$ManifestObj
    )

    $bomDir = Join-Path $LocalRepo "io\haifa\haifa-agent-bom\$Version"
    if (-not (Test-Path $bomDir)) {
        New-Item -ItemType Directory -Path $bomDir -Force | Out-Null
    }

    $manifestPath = Join-Path $bomDir 'haifa-sdk-publish-manifest.json'
    $tmpPath = "$manifestPath.tmp"

    $json = $ManifestObj | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($tmpPath, $json, [System.Text.UTF8Encoding]::new($false))
    Move-Item -Force $tmpPath $manifestPath

    return $manifestPath
}
