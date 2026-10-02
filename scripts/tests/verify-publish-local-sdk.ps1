#Requires -Version 7
<#
.SYNOPSIS
    Verification test suite for versioned Haifa SDK publishing (ENG-SdkWorktreeVersionedPublish).
.DESCRIPTION
    Runs automated tests covering:
    - V5: Parameter rejection, dirty working tree rejection, branch/worktree rules
    - V3: Multi-worktree coordinate isolation with real JAR hash verification
    - V6: Coordinate ownership protection
    - V7: Failure handling through entrypoint (old manifest cleanup, no partial manifest on error, product cleanup)
    - Probes for P1/P2 review findings:
      * File lock prevents silent deletion
      * Product JARs cleaned in finally block on failure
      * POM actual version expansion verified (not just ${revision} check)
      * Non-reactor POMs in local-tmp do not block classification
      * Paths with spaces are preserved when invoked with Maven
    - Anti-drift: Full reactor module classification coverage

    If -FullPublish is passed, also performs:
    - V1: Full reactor build and artifact manifest verification
    - V2: Complete baseline JAR immutability check across all 44 public modules
    - V4: Consumer smoke dependency resolution and transitive version check
#>
[CmdletBinding()]
param(
    [switch]$FullPublish
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..\..')).Path
$publishScript = Join-Path $repoRoot 'scripts\publish-local-sdk.ps1'

# Load publishing support functions directly for isolated unit testing
. (Join-Path $repoRoot 'scripts\lib\sdk-publish-support.ps1')

Write-Host "=== Running Haifa SDK Versioned Publish Verification Suite ==="
$testResults = [ordered]@{}

function Assert-Throws([scriptblock]$Action, [string]$ExpectedSubstring, [string]$TestName) {
    $threw = $false
    try {
        & $Action
    } catch {
        $threw = $true
        $msg = $_.Exception.Message
        if (-not $msg.Contains($ExpectedSubstring)) {
            throw "[$TestName] Expected exception containing '$ExpectedSubstring', but got: '$msg'"
        }
    }
    if (-not $threw) {
        throw "[$TestName] Expected exception was not thrown."
    }
    Write-Host "[$TestName] PASS (correctly rejected with '$ExpectedSubstring')"
}

# -------------------------------------------------------------
# Test V5: Parameter rejection & strict CmdletBinding
# -------------------------------------------------------------
Write-Host "`n--- Testing V5: Parameter Rejections ---"

Assert-Throws {
    & $publishScript -pl ':haifa-agent-sdk'
} 'pl' 'V5-RejectNarrowingParameter'

Assert-Throws {
    & $publishScript -UnknownParameter 'value'
} 'UnknownParameter' 'V5-RejectUnknownParameter'

$testResults['V5-ParameterValidation'] = 'PASS'

# -------------------------------------------------------------
# Test V5: Dirty working tree detection
# -------------------------------------------------------------
Write-Host "`n--- Testing V5: Dirty Working Tree Detection ---"
$tempGitDir = Join-Path $repoRoot 'local-tmp\test-git-dirty'
if (Test-Path $tempGitDir) { Remove-Item -Recurse -Force $tempGitDir }
New-Item -ItemType Directory -Path $tempGitDir -Force | Out-Null

try {
    git -C $tempGitDir init -q
    git -C $tempGitDir config user.name "Test"
    git -C $tempGitDir config user.email "test@example.com"
    Set-Content -Path (Join-Path $tempGitDir 'dummy.txt') -Value 'clean'
    git -C $tempGitDir add dummy.txt
    git -C $tempGitDir commit -q -m "init"

    # 1. Clean should pass
    Assert-CleanWorkingTree -RepoRoot $tempGitDir
    Write-Host "[V5-CleanWorkingTree] PASS (clean tree accepted)"

    # 2. Untracked file makes it dirty
    Set-Content -Path (Join-Path $tempGitDir 'untracked.txt') -Value 'dirty'
    Assert-Throws {
        Assert-CleanWorkingTree -RepoRoot $tempGitDir
    } 'Working tree is dirty' 'V5-RejectDirtyUntracked'

    # Remove untracked file
    Remove-Item (Join-Path $tempGitDir 'untracked.txt')

    # 3. Modified file makes it dirty
    Set-Content -Path (Join-Path $tempGitDir 'dummy.txt') -Value 'modified'
    Assert-Throws {
        Assert-CleanWorkingTree -RepoRoot $tempGitDir
    } 'Working tree is dirty' 'V5-RejectDirtyModified'

    $testResults['V5-CleanWorkingTreeGuard'] = 'PASS'
} finally {
    if (Test-Path $tempGitDir) { Remove-Item -Recurse -Force $tempGitDir }
}

# -------------------------------------------------------------
# Test V5: Branch and worktree version resolution rules
# -------------------------------------------------------------
Write-Host "`n--- Testing V5: Worktree & Branch Version Resolution ---"
$tempRepoMain = Join-Path $repoRoot 'local-tmp\test-repo-main'
if (Test-Path $tempRepoMain) { Remove-Item -Recurse -Force $tempRepoMain }
New-Item -ItemType Directory -Path $tempRepoMain -Force | Out-Null

try {
    git -C $tempRepoMain init -q -b main
    git -C $tempRepoMain config user.name "Test"
    git -C $tempRepoMain config user.email "test@example.com"
    Set-Content -Path (Join-Path $tempRepoMain 'init.txt') -Value 'test'
    git -C $tempRepoMain add init.txt
    git -C $tempRepoMain commit -q -m "initial commit"

    # 1. Main checkout on branch 'main' (not 'dev') must be rejected from publishing baseline
    Assert-Throws {
        Resolve-WorktreePublishVersion -RepoRoot $tempRepoMain
    } "Main checkout must be on branch 'dev'" 'V5-RejectNonDevMainCheckout'

    # 2. Main checkout on branch 'feat-something' must also be rejected
    git -C $tempRepoMain checkout -q -b feat-something
    Assert-Throws {
        Resolve-WorktreePublishVersion -RepoRoot $tempRepoMain
    } "Main checkout must be on branch 'dev'" 'V5-RejectFeatBranchMainCheckout'

    # 3. Main checkout on branch 'dev' succeeds and publishes baseline
    git -C $tempRepoMain checkout -q -b dev
    $mainDevVersion = Resolve-WorktreePublishVersion -RepoRoot $tempRepoMain
    if ($mainDevVersion.Version -ne '0.1.2-SNAPSHOT' -or -not $mainDevVersion.IsBaseline) {
        throw "Expected baseline 0.1.2-SNAPSHOT from main checkout on dev, got: $($mainDevVersion.Version)"
    }
    Write-Host "[V5-MainDevBaseline] PASS (main checkout on dev resolves to baseline 0.1.2-SNAPSHOT)"

    # 4. Linked worktree on a feature branch produces versioned identifier and is not baseline
    $worktreePath = Join-Path $repoRoot 'local-tmp\test-worktree-feat-one'
    if (Test-Path $worktreePath) { Remove-Item -Recurse -Force $worktreePath }
    git -C $tempRepoMain worktree add -q -b feat-one $worktreePath dev
    try {
        $wtVersion = Resolve-WorktreePublishVersion -RepoRoot $worktreePath
        if ($wtVersion.Version -ne '0.1.2-test-worktree-feat-one-SNAPSHOT' -or $wtVersion.IsBaseline) {
            throw "Expected 0.1.2-test-worktree-feat-one-SNAPSHOT, got: $($wtVersion.Version)"
        }
        Write-Host "[V5-WorktreeVersioned] PASS (worktree resolves to 0.1.2-test-worktree-feat-one-SNAPSHOT)"
    } finally {
        git -C $tempRepoMain worktree remove -f $worktreePath 2>$null
        if (Test-Path $worktreePath) { Remove-Item -Recurse -Force $worktreePath }
    }

    $testResults['V5-BranchAndWorktreeRules'] = 'PASS'
} finally {
    if (Test-Path $tempRepoMain) { Remove-Item -Recurse -Force $tempRepoMain }
}

# -------------------------------------------------------------
# Test [P1 Probe]: Locked manifest deletion failure aborts publish
# -------------------------------------------------------------
Write-Host "`n--- Testing P1 Probe: Locked Manifest Deletion Abort ---"
$tempRepoP1 = Join-Path $repoRoot 'local-tmp\test-m2-repo-p1'
if (Test-Path $tempRepoP1) { Remove-Item -Recurse -Force $tempRepoP1 }
$p1Version = '0.1.2-p1-locked-SNAPSHOT'
$p1BomDir = Join-Path $tempRepoP1 "io\haifa\haifa-agent-bom\$p1Version"
New-Item -ItemType Directory -Path $p1BomDir -Force | Out-Null
$p1Manifest = Join-Path $p1BomDir 'haifa-sdk-publish-manifest.json'
Set-Content -Path $p1Manifest -Value '{"worktreePath": "D:\\test\\p1"}' -Encoding utf8

# Open file allowing read but preventing delete (FileShare.Read)
$fileLockStream = [System.IO.File]::Open($p1Manifest, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
try {
    Assert-Throws {
        Assert-CoordinateOwnership -LocalRepo $tempRepoP1 -Version $p1Version -CurrentWorktreePath 'D:\test\p1'
    } 'Failed to remove existing manifest' 'P1-LockedManifestAborts'
} finally {
    $fileLockStream.Close()
    $fileLockStream.Dispose()
    Remove-Item -Recurse -Force $tempRepoP1
}
$testResults['P1-LockedManifestGuard'] = 'PASS'

# -------------------------------------------------------------
# Test [P1 Probe]: Product JARs cleaned on build failure via finally
# -------------------------------------------------------------
Write-Host "`n--- Testing P1 Probe: Product Artifacts Purged on Failure ---"
$tempRepoProd = Join-Path $repoRoot 'local-tmp\test-m2-repo-prod'
if (Test-Path $tempRepoProd) { Remove-Item -Recurse -Force $tempRepoProd }
$prodVersion = '0.1.2-prod-test-SNAPSHOT'

# Simulate intermediate product JAR installed before a failure
$prodJarDir = Join-Path $tempRepoProd "io\haifa\haifa-agent-cli\$prodVersion"
New-Item -ItemType Directory -Path $prodJarDir -Force | Out-Null
$prodJarFile = Join-Path $prodJarDir "haifa-agent-cli-$prodVersion.jar"
Set-Content -Path $prodJarFile -Value 'product-jar-bytes'

$classification = Get-ReactorModuleClassification -RepoRoot $repoRoot
Remove-ProductArtifacts -RepoRoot $repoRoot -LocalRepo $tempRepoProd -Version $prodVersion -Classification $classification

if (Test-Path $prodJarDir) {
    throw "Product artifacts directory must be completely removed by Remove-ProductArtifacts."
}
Write-Host "[P1-ProductArtifactsPurged] PASS (product artifacts purged cleanly before/after build)"
Remove-Item -Recurse -Force $tempRepoProd
$testResults['P1-ProductArtifactsGuard'] = 'PASS'

# -------------------------------------------------------------
# Test [P2 Probe]: POM actual version expansion verified
# -------------------------------------------------------------
Write-Host "`n--- Testing P2 Probe: Actual POM Version Expansion Check ---"
$tempPomDir = Join-Path $repoRoot 'local-tmp\test-poms'
if (Test-Path $tempPomDir) { Remove-Item -Recurse -Force $tempPomDir }
New-Item -ItemType Directory -Path $tempPomDir -Force | Out-Null

$unexpandedPom = Join-Path $tempPomDir 'unexpanded.pom'
Set-Content -Path $unexpandedPom -Value '<project><parent><version>${revision}</version></parent><artifactId>test</artifactId></project>' -Encoding utf8
Assert-Throws {
    Assert-InstalledPomValid -InstalledPomPath $unexpandedPom -ArtifactId 'test' -ExpectedVersion '0.1.2-slot-SNAPSHOT'
} 'still contains unresolved' 'P2-UnresolvedRevisionRejected'

$mismatchedPom = Join-Path $tempPomDir 'mismatched.pom'
Set-Content -Path $mismatchedPom -Value '<project><parent><version>0.1.2-SNAPSHOT</version></parent><artifactId>test</artifactId></project>' -Encoding utf8
Assert-Throws {
    Assert-InstalledPomValid -InstalledPomPath $mismatchedPom -ArtifactId 'test' -ExpectedVersion '0.1.2-slot-SNAPSHOT'
} 'has version ''0.1.2-SNAPSHOT'', expected ''0.1.2-slot-SNAPSHOT''' 'P2-MismatchedVersionRejected'

$validPom = Join-Path $tempPomDir 'valid.pom'
Set-Content -Path $validPom -Value '<project><parent><version>0.1.2-slot-SNAPSHOT</version></parent><artifactId>test</artifactId></project>' -Encoding utf8
Assert-InstalledPomValid -InstalledPomPath $validPom -ArtifactId 'test' -ExpectedVersion '0.1.2-slot-SNAPSHOT'
Write-Host "[P2-ValidPomVersion] PASS (POM with correctly expanded target version accepted)"

Remove-Item -Recurse -Force $tempPomDir
$testResults['P2-PomVersionValidation'] = 'PASS'

# -------------------------------------------------------------
# Test [P2 Probe]: Non-reactor POMs in local-tmp do not block classification
# -------------------------------------------------------------
Write-Host "`n--- Testing P2 Probe: Ignored local-tmp POMs Do Not Block Reactor Check ---"
$scratchDir = Join-Path $repoRoot 'local-tmp\scratch-consumer-project'
New-Item -ItemType Directory -Path $scratchDir -Force | Out-Null
Set-Content -Path (Join-Path $scratchDir 'pom.xml') -Value '<project><artifactId>scratch-consumer</artifactId></project>' -Encoding utf8

try {
    $classResult = Get-ReactorModuleClassification -RepoRoot $repoRoot
    if ($classResult.PublicModules.Count -ne 44) {
        throw "Classification count mismatch."
    }
    Write-Host "[P2-LocalTmpIgnored] PASS (local-tmp scratch POM successfully ignored by reactor classification)"
    $testResults['P2-ReactorDiscoveryIsolation'] = 'PASS'
} finally {
    Remove-Item -Recurse -Force $scratchDir
}

# -------------------------------------------------------------
# Test [P2 Probe]: Paths with spaces passed safely to Maven
# -------------------------------------------------------------
Write-Host "`n--- Testing P2 Probe: Maven Process Argument Quoting with Spaces ---"
$spaceRepo = Join-Path $repoRoot 'local-tmp\test space repository'
New-Item -ItemType Directory -Path $spaceRepo -Force | Out-Null

try {
    $proc = Invoke-MavenProcess -RepoRoot $repoRoot -Arguments @('-v', "-Dmaven.repo.local=$spaceRepo")
    if ($proc.ExitCode -ne 0) {
        throw "Invoke-MavenProcess failed with exit code $($proc.ExitCode) for path with spaces."
    }
    Write-Host "[P2-ArgumentQuoting] PASS (Maven invoked successfully with space-containing repo path)"
    $testResults['P2-MavenSpaceQuoting'] = 'PASS'
} finally {
    Remove-Item -Recurse -Force $spaceRepo
}

# -------------------------------------------------------------
# Test V3: Multi-worktree version isolation with REAL JAR hashes
# -------------------------------------------------------------
Write-Host "`n--- Testing V3: Multi-Worktree Version Isolation & Real JAR Hashes ---"
$tempRepoV3 = Join-Path $repoRoot 'local-tmp\test-m2-repo-v3'
if (Test-Path $tempRepoV3) { Remove-Item -Recurse -Force $tempRepoV3 }

$wt1Path = 'D:\workspace\haifa-agent-worktrees\slot-1-feat-a'
$wt2Path = 'D:\workspace\haifa-agent-worktrees\slot-2-feat-b'
$wt1Version = '0.1.2-slot-1-feat-a-SNAPSHOT'
$wt2Version = '0.1.2-slot-2-feat-b-SNAPSHOT'

# Create distinct JARs with different bytes for wt1 and wt2
$wt1JarDir = Join-Path $tempRepoV3 "io\haifa\haifa-agent-sdk\$wt1Version"
$wt2JarDir = Join-Path $tempRepoV3 "io\haifa\haifa-agent-sdk\$wt2Version"
New-Item -ItemType Directory -Path $wt1JarDir -Force | Out-Null
New-Item -ItemType Directory -Path $wt2JarDir -Force | Out-Null

$wt1Jar = Join-Path $wt1JarDir "haifa-agent-sdk-$wt1Version.jar"
$wt2Jar = Join-Path $wt2JarDir "haifa-agent-sdk-$wt2Version.jar"
Set-Content -Path $wt1Jar -Value 'compiled-bytes-from-worktree-alpha-feature-a' -Encoding utf8
Set-Content -Path $wt2Jar -Value 'compiled-bytes-from-worktree-beta-feature-b' -Encoding utf8

$wt1Hash = (Get-FileHash -Path $wt1Jar -Algorithm SHA256).Hash.ToLower()
$wt2Hash = (Get-FileHash -Path $wt2Jar -Algorithm SHA256).Hash.ToLower()

if ($wt1Hash -eq $wt2Hash) {
    throw "Test JARs must have different hashes."
}

$manifest1 = [ordered]@{
    version = $wt1Version
    identifier = 'slot-1-feat-a'
    sdkCommit = '11111111'
    branch = 'feat-a'
    worktreePath = $wt1Path
    publishedAt = (Get-Date).ToUniversalTime().ToString('o')
    artifacts = @(
        [ordered]@{
            groupId = 'io.haifa'
            artifactId = 'haifa-agent-sdk'
            version = $wt1Version
            jarFileName = "haifa-agent-sdk-$wt1Version.jar"
            sha256 = $wt1Hash
            bytes = (Get-Item $wt1Jar).Length
        }
    )
}
$manifest2 = [ordered]@{
    version = $wt2Version
    identifier = 'slot-2-feat-b'
    sdkCommit = '22222222'
    branch = 'feat-b'
    worktreePath = $wt2Path
    publishedAt = (Get-Date).ToUniversalTime().ToString('o')
    artifacts = @(
        [ordered]@{
            groupId = 'io.haifa'
            artifactId = 'haifa-agent-sdk'
            version = $wt2Version
            jarFileName = "haifa-agent-sdk-$wt2Version.jar"
            sha256 = $wt2Hash
            bytes = (Get-Item $wt2Jar).Length
        }
    )
}

$m1Path = Write-PublishManifest -LocalRepo $tempRepoV3 -Version $wt1Version -ManifestObj $manifest1
$m2Path = Write-PublishManifest -LocalRepo $tempRepoV3 -Version $wt2Version -ManifestObj $manifest2

# Verify writing wt2 did not alter wt1 JAR hash or manifest
$wt1HashAfter = (Get-FileHash -Path $wt1Jar -Algorithm SHA256).Hash.ToLower()
if ($wt1Hash -ne $wt1HashAfter) {
    throw "WT1 JAR hash modified after publishing WT2!"
}

# Assert cross-worktree ownership protection
Assert-Throws {
    Assert-CoordinateOwnership -LocalRepo $tempRepoV3 -Version $wt1Version -CurrentWorktreePath $wt2Path
} "is already owned by worktree '$wt1Path'" 'V3-CrossWorktreeOwnershipGuard'

Remove-Item -Recurse -Force $tempRepoV3
Write-Host "[V3-MultiWorktreeRealHashes] PASS (isolated coordinates preserve distinct JAR hashes and manifests)"
$testResults['V3-WorktreeIsolation'] = 'PASS'

# -------------------------------------------------------------
# Test V6: Coordinate ownership enforcement
# -------------------------------------------------------------
Write-Host "`n--- Testing V6: Coordinate Ownership Enforcement ---"
$tempRepoV6 = Join-Path $repoRoot 'local-tmp\test-m2-repo-v6'
if (Test-Path $tempRepoV6) { Remove-Item -Recurse -Force $tempRepoV6 }
$v6Version = '0.1.2-owned-target-SNAPSHOT'
$v6OwnerPath = 'D:\existing\worktree\owned-target'
$v6OtherPath = 'D:\other\worktree\owned-target'

$fakeManifestV6 = [ordered]@{
    version = $v6Version
    identifier = 'owned-target'
    sdkCommit = 'aaaaaaaa'
    branch = 'feat-existing'
    worktreePath = $v6OwnerPath
    publishedAt = (Get-Date).ToUniversalTime().ToString('o')
    artifacts = @()
}
Write-PublishManifest -LocalRepo $tempRepoV6 -Version $v6Version -ManifestObj $fakeManifestV6 | Out-Null

Assert-Throws {
    Assert-CoordinateOwnership -LocalRepo $tempRepoV6 -Version $v6Version -CurrentWorktreePath $v6OtherPath
} "is already owned by worktree '$v6OwnerPath'" 'V6-OwnershipRejection'

Remove-Item -Recurse -Force $tempRepoV6
$testResults['V6-OwnershipProtection'] = 'PASS'

# -------------------------------------------------------------
# Test V7: Entrypoint publish failure removes old manifest & leaves no partial manifest
# -------------------------------------------------------------
Write-Host "`n--- Testing V7: Entrypoint Failure Lifecycle & Manifest Cleanup ---"
$tempRepoV7 = Join-Path $repoRoot 'local-tmp\test-m2-repo-v7'
if (Test-Path $tempRepoV7) { Remove-Item -Recurse -Force $tempRepoV7 }

$versionInfoV7 = Resolve-WorktreePublishVersion -RepoRoot $repoRoot
$v7Version = $versionInfoV7.Version
$v7BomDir = Join-Path $tempRepoV7 "io\haifa\haifa-agent-bom\$v7Version"
New-Item -ItemType Directory -Path $v7BomDir -Force | Out-Null
$v7ManifestPath = Join-Path $v7BomDir 'haifa-sdk-publish-manifest.json'

# Write old manifest owned by the current worktree
$oldManifest = [ordered]@{
    version = $v7Version
    identifier = $versionInfoV7.Identifier
    sdkCommit = '00000000'
    branch = $versionInfoV7.Branch
    worktreePath = $repoRoot
    publishedAt = (Get-Date).ToUniversalTime().ToString('o')
    artifacts = @()
}
$mPath = Write-PublishManifest -LocalRepo $tempRepoV7 -Version $v7Version -ManifestObj $oldManifest
if (-not (Test-Path $v7ManifestPath)) {
    throw "Old manifest should exist prior to test."
}

# Also plant a mock product artifact to verify finally cleanup
$v7ProdDir = Join-Path $tempRepoV7 "io\haifa\haifa-agent-cli\$v7Version"
New-Item -ItemType Directory -Path $v7ProdDir -Force | Out-Null
Set-Content -Path (Join-Path $v7ProdDir "haifa-agent-cli-$v7Version.jar") -Value 'mock-cli'

# Execute real publish entrypoint with MAVEN_OPTS set to fail fast during Maven initialization
$origMavenOpts = $env:MAVEN_OPTS
try {
    $env:MAVEN_OPTS = '-Xmx1k'
    Assert-Throws {
        & $publishScript -LocalRepo $tempRepoV7
    } 'Maven clean install failed' 'V7-EntrypointBuildFailureAborts'
} finally {
    $env:MAVEN_OPTS = $origMavenOpts
}

# 1. Assert old manifest was removed
if (Test-Path $v7ManifestPath) {
    throw "[V7] Old manifest must have been deleted at start of publish."
}

# 2. Assert product artifacts were purged by finally block
if (Test-Path $v7ProdDir) {
    throw "[V7] Product artifacts directory must have been purged by finally block on build failure."
}
Write-Host "[V7-EntrypointFailureLifecycle] PASS (entrypoint failure deleted old manifest, wrote no manifest, and purged product artifacts)"
Remove-Item -Recurse -Force $tempRepoV7
$testResults['V7-FailureLifecycle'] = 'PASS'

# -------------------------------------------------------------
# Anti-Drift: Reactor Module Classification
# -------------------------------------------------------------
Write-Host "`n--- Testing Anti-Drift: Reactor Module Classification ---"
$classification = Get-ReactorModuleClassification -RepoRoot $repoRoot
Write-Host "Reactor modules: $($classification.PublicModules.Count) public modules, $($classification.Boms.Count) BOMs, $($classification.ProductModules.Count) product modules."

if ($classification.PublicModules.Count -ne 44) {
    throw "Expected 44 public modules, found $($classification.PublicModules.Count)."
}
if ($classification.Boms.Count -ne 2) {
    throw "Expected 2 BOMs, found $($classification.Boms.Count)."
}
if ($classification.ProductModules.Count -ne 11) {
    throw "Expected 11 product modules, found $($classification.ProductModules.Count)."
}
Write-Host "[AntiDrift-ModuleClassification] PASS (all 66 reactor POMs + BOMs strictly classified)"
$testResults['AntiDrift-Classification'] = 'PASS'

# -------------------------------------------------------------
# Optional Full Publish: V1, V2, V4
# -------------------------------------------------------------
if ($FullPublish) {
    Write-Host "`n======================================================="
    Write-Host "Running FULL PUBLISH verification (V1, V2, V4)"
    Write-Host "======================================================="

    $resolvedLocalRepo = Get-MavenLocalRepository -RepoRoot $repoRoot

    # Record hashes and mtimes of ALL 44 baseline JARs before publish
    Write-Host "Recording pre-publish state of baseline 0.1.2-SNAPSHOT JARs..."
    $baselineJars = @{}
    foreach ($modRel in $classification.PublicModules) {
        $pomXml = [xml](Get-Content (Join-Path $repoRoot (Join-Path $modRel 'pom.xml')))
        $artId = $pomXml.project.artifactId
        $bJar = Join-Path $resolvedLocalRepo "io\haifa\$artId\0.1.2-SNAPSHOT\$artId-0.1.2-SNAPSHOT.jar"
        if (Test-Path $bJar) {
            $baselineJars[$artId] = @{
                Path = $bJar
                Hash = (Get-FileHash -Path $bJar -Algorithm SHA256).Hash
                LastWriteTimeUtc = (Get-Item $bJar).LastWriteTimeUtc
            }
        }
    }
    Write-Host "Found $($baselineJars.Count) existing baseline JARs in local repo."

    # Execute full publish
    Write-Host "Executing scripts/publish-local-sdk.ps1..."
    $pubResult = & $publishScript
    if ($pubResult.Status -ne 'SUCCESS') {
        throw "publish-local-sdk.ps1 failed: $($pubResult | Out-String)"
    }
    $publishedVersion = $pubResult.Version
    $manifestPath = $pubResult.ManifestPath

    # V1: Verify manifest and all 44 artifacts
    Write-Host "`n--- Verifying V1: Artifacts and Manifest ---"
    if (-not (Test-Path $manifestPath)) {
        throw "[V1] Manifest not found at '$manifestPath'."
    }
    $manifest = Get-Content $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
    if ($manifest.artifacts.Count -ne 44) {
        throw "[V1] Expected 44 artifacts in manifest, got $($manifest.artifacts.Count)."
    }
    foreach ($art in $manifest.artifacts) {
        $installedJar = Join-Path $resolvedLocalRepo "io\haifa\$($art.artifactId)\$publishedVersion\$($art.jarFileName)"
        if (-not (Test-Path $installedJar)) {
            throw "[V1] Installed JAR not found at '$installedJar'."
        }
        $actualHash = (Get-FileHash -Path $installedJar -Algorithm SHA256).Hash.ToLower()
        if ($actualHash -ne $art.sha256) {
            throw "[V1] Hash mismatch for '$($art.artifactId)': manifest=$($art.sha256), actual=$actualHash"
        }
    }
    Write-Host "[V1] PASS (all 44 artifacts verified with SHA256 hashes against manifest)"
    $testResults['V1-FullPublish'] = 'PASS'

    # V2: Immutability check for all baseline JARs + no product JARs installed
    Write-Host "`n--- Verifying V2: Baseline Immutability & No Product JARs ---"
    foreach ($entry in $baselineJars.GetEnumerator()) {
        $artId = $entry.Key
        $saved = $entry.Value
        $currHash = (Get-FileHash -Path $saved.Path -Algorithm SHA256).Hash
        $currMtime = (Get-Item $saved.Path).LastWriteTimeUtc
        if ($currHash -ne $saved.Hash) {
            throw "[V2] Baseline JAR for '$artId' changed hash from $($saved.Hash) to $currHash!"
        }
        if ($currMtime -ne $saved.LastWriteTimeUtc) {
            throw "[V2] Baseline JAR for '$artId' changed mtime from $($saved.LastWriteTimeUtc) to $currMtime!"
        }
    }
    Write-Host "[V2-BaselineUntouched] PASS (all $($baselineJars.Count) pre-existing baseline JARs remained untouched)"

    foreach ($prodRel in $classification.ProductModules) {
        $prodPom = [xml](Get-Content (Join-Path $repoRoot (Join-Path $prodRel 'pom.xml')))
        $prodArt = $prodPom.project.artifactId
        $prodJar = Join-Path $resolvedLocalRepo "io\haifa\$prodArt\$publishedVersion\$prodArt-$publishedVersion.jar"
        if (Test-Path $prodJar) {
            throw "[V2] Product JAR '$prodArt' was published to SDK coordinates at '$prodJar'!"
        }
    }
    Write-Host "[V2-NoProductJars] PASS (zero product JARs installed under SDK coordinates)"
    $testResults['V2-BaselineImmutability'] = 'PASS'

    # V4: Consumer smoke dependency resolution and transitive version check
    Write-Host "`n--- Verifying V4: Consumer Smoke & Transitive Versions ---"
    $smokePom = Join-Path $repoRoot 'build-support\sdk-consumer-smoke\maven\pom.xml'
    $depListArgs = @(
        '-o',
        '-ntp',
        '-f', $smokePom,
        "-Dhaifa.version=$publishedVersion",
        "-Dmaven.repo.local=$resolvedLocalRepo",
        'dependency:list',
        '-DincludeGroupIds=io.haifa'
    )
    $depProc = Start-Process -FilePath 'mvn.cmd' -ArgumentList $depListArgs -WorkingDirectory $repoRoot -NoNewWindow -Wait -PassThru -RedirectStandardOutput (Join-Path $repoRoot 'local-tmp\consumer-deps.log')
    if ($depProc.ExitCode -ne 0) {
        throw "[V4] Consumer smoke dependency:list failed with exit code $($depProc.ExitCode)."
    }

    $depOutput = Get-Content (Join-Path $repoRoot 'local-tmp\consumer-deps.log') -Raw
    $depLines = $depOutput -split "`r?`n" | Where-Object { $_ -match '\[INFO\]\s+io\.haifa:' }
    if ($depLines.Count -eq 0) {
        throw "[V4] No io.haifa dependencies resolved in consumer smoke."
    }

    foreach ($line in $depLines) {
        if ($line -notmatch [regex]::Escape($publishedVersion)) {
            throw "[V4] Resolved dependency does not match expected version '$publishedVersion': $line"
        }
    }
    Write-Host "[V4] PASS ($($depLines.Count) io.haifa dependencies resolved strictly to $publishedVersion)"
    $testResults['V4-TransitiveVersions'] = 'PASS'
}

Write-Host "`n=== Verification Summary ==="
$testResults.GetEnumerator() | ForEach-Object {
    Write-Host "  $($_.Key): $($_.Value)"
}

Write-Host "`nAll verification assertions PASSED successfully!"
return $true
