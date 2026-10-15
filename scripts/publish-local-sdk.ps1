#Requires -Version 7
<#
.SYNOPSIS
    Publishes the Haifa Agent SDK to the local Maven repository with versioned coordinates.
.DESCRIPTION
    Follows ENG-SdkWorktreeVersionedPublish rules:
    - Main checkout on 'dev': publishes baseline 0.1.2-SNAPSHOT.
    - Other worktrees: publishes 0.1.2-<identifier>-SNAPSHOT where <identifier> is the worktree directory name.
    - Requires clean working tree and runs 'clean install'.
    - Deletes old manifest before publish starts, verifies installed artifacts, and atomically writes haifa-sdk-publish-manifest.json.
#>
[CmdletBinding()]
param(
    [string]$LocalRepo = $null
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# Load shared publishing support functions
. (Join-Path $PSScriptRoot 'lib\sdk-publish-support.ps1')

# 1. Ensure working tree is clean
Assert-CleanWorkingTree -RepoRoot $repoRoot

# 2. Determine target version and enforce branch/worktree rules
$versionInfo = Resolve-WorktreePublishVersion -RepoRoot $repoRoot
$version = $versionInfo.Version
$identifier = $versionInfo.Identifier

# 3. Resolve active Maven local repository
$resolvedLocalRepo = Get-MavenLocalRepository -RepoRoot $repoRoot -ExplicitRepo $LocalRepo

# 4. Check coordinate ownership and remove any existing manifest before publish
Assert-CoordinateOwnership -LocalRepo $resolvedLocalRepo -Version $version -CurrentWorktreePath $repoRoot

# 5. Check module classification across reactor to prevent drift
$classification = Get-ReactorModuleClassification -RepoRoot $repoRoot

# 6. Ensure product JARs are cleaned before build starts
Remove-ProductArtifacts -RepoRoot $repoRoot -LocalRepo $resolvedLocalRepo -Version $version -Classification $classification

# 7. Build and publish full reactor with clean install
Write-Host "Publishing Haifa SDK version '$version' (commit: $($versionInfo.SdkCommit), worktree: $repoRoot)..."
$mvnArgs = @(
    '-o',
    '-ntp',
    "-Drevision=$version",
    '-DskipUnitTests=true',
    '-DskipITs=true',
    '-DskipTests',
    "-Dmaven.repo.local=$resolvedLocalRepo",
    'clean',
    'install'
)

try {
    $mvnProc = Invoke-MavenProcess -RepoRoot $repoRoot -Arguments $mvnArgs
    if ($mvnProc.ExitCode -ne 0) {
        throw "Maven clean install failed with exit code $($mvnProc.ExitCode)."
    }
} finally {
    # Always ensure product artifacts are removed from the version directory (on success and on failure)
    Remove-ProductArtifacts -RepoRoot $repoRoot -LocalRepo $resolvedLocalRepo -Version $version -Classification $classification
}

# 8. Post-publish verification: verify POMs (including actual version expansion), JAR hashes, and lengths
$publishedArtifacts = Verify-InstalledArtifacts -RepoRoot $repoRoot -LocalRepo $resolvedLocalRepo -Version $version -Classification $classification

# 9. Atomically write release manifest
$manifestObj = [ordered]@{
    version = $version
    identifier = $identifier
    sdkCommit = $versionInfo.SdkCommit
    branch = $versionInfo.Branch
    worktreePath = $repoRoot
    publishedAt = (Get-Date).ToUniversalTime().ToString('o')
    artifacts = $publishedArtifacts
}

$manifestPath = Write-PublishManifest -LocalRepo $resolvedLocalRepo -Version $version -ManifestObj $manifestObj

Write-Host "Published $version successfully. Manifest written to '$manifestPath' ($($publishedArtifacts.Count) artifacts)."
return [PSCustomObject]@{
    Status = 'SUCCESS'
    Version = $version
    Identifier = $identifier
    SdkCommit = $versionInfo.SdkCommit
    ManifestPath = $manifestPath
    ArtifactCount = $publishedArtifacts.Count
}
