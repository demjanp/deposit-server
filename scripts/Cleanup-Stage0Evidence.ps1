$ErrorActionPreference = 'Stop'
if ($args.Count -ne 0) { throw 'Cleanup-Stage0Evidence.ps1 takes no command-line arguments.' }

# Keep only the reusable installation, pinned tool/dependency caches, Maven
# caches, and current stage checkpoints. Everything else under this fixed root
# is disposable test output or historical work.
$rootPath = [IO.Path]::GetFullPath('C:\data_processed\Deposit_v2\stage0').TrimEnd('\')
$repo = Split-Path $PSScriptRoot -Parent
$runPath = Join-Path $rootPath 'runs\stage0-host'
$runnerPath = Join-Path $rootPath 'runner'
$evidencePath = Join-Path $rootPath 'evidence'

if ($rootPath -ne 'C:\data_processed\Deposit_v2\stage0') {
    throw "Refusing unexpected Stage 0 root: $rootPath"
}
if (!(Test-Path -LiteralPath $rootPath -PathType Container)) {
    throw "Stage 0 root does not exist: $rootPath"
}

function Assert-NoReparseAncestors([string]$Path) {
    $current = Get-Item -LiteralPath $Path -Force
    while ($null -ne $current) {
        if (($current.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing cleanup through reparse point: $($current.FullName)"
        }
        $current = $current.Parent
    }
}

function Assert-NoReparseTree([string]$Path) {
    $pending = New-Object 'System.Collections.Generic.Stack[string]'
    $pending.Push($Path)
    while ($pending.Count -gt 0) {
        $currentPath = $pending.Pop()
        $current = Get-Item -LiteralPath $currentPath -Force
        if (($current.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing cleanup of a tree containing a reparse point: $currentPath"
        }
        if ($current.PSIsContainer) {
            foreach ($child in Get-ChildItem -LiteralPath $currentPath -Force -ErrorAction Stop) {
                $pending.Push($child.FullName)
            }
        }
    }
}

function Assert-UnderRoot([string]$Path) {
    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if (!$fullPath.StartsWith($rootPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing path outside Stage 0 root: $fullPath"
    }
    return $fullPath
}

Assert-NoReparseAncestors $rootPath

if (!(Test-Path -LiteralPath (Join-Path $runPath 'owner.json') -PathType Leaf)) {
    throw "The owned stage0-host installation is missing; refusing broad cleanup: $runPath"
}
$owner = Get-Content -LiteralPath (Join-Path $runPath 'owner.json') -Raw | ConvertFrom-Json -ErrorAction Stop
if ($owner.runId -ne 'stage0-host' -or $owner.root -ne $rootPath) {
    throw 'The stage0-host ownership marker does not match the approved installation.'
}

# Current checkpoints are prerequisite inputs for future cumulative gates.
# Retain them regardless of age/status, but refuse to delete anything if one is
# unreadable. Their .previous/.partial copies are disposable recovery history.
$checkpointPattern = '^stage(?:0|[1-9]|5\.5|65|10|11)-checkpoint\.json$'
$checkpointNames = @()
foreach ($checkpointFile in @(Get-ChildItem -LiteralPath $rootPath -File -Force | Where-Object { $_.Name -match $checkpointPattern })) {
    try {
        $null = Get-Content -LiteralPath $checkpointFile.FullName -Raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "Cannot read prerequisite checkpoint '$($checkpointFile.FullName)'; no cleanup was performed."
    }
    $checkpointNames += $checkpointFile.Name
}
if ($checkpointNames -notcontains 'stage0-checkpoint.json') {
    throw 'The accepted Stage 0 checkpoint is missing; refusing broad cleanup.'
}
$stage0Checkpoint = Get-Content -LiteralPath (Join-Path $rootPath 'stage0-checkpoint.json') -Raw | ConvertFrom-Json -ErrorAction Stop
if ($stage0Checkpoint.status -ne 'accepted') {
    throw 'Stage 0 is not accepted; refusing to remove diagnostic/recovery evidence.'
}
if (!(Test-Path -LiteralPath (Join-Path $runPath 'neo4j') -PathType Container)) {
    throw 'The owned Neo4j installation is missing; refusing broad cleanup.'
}

# Validate all pinned assets before deleting anything. These are reusable build
# inputs for Stage 10/11, unlike prior outputs and evidence.
$manifestPath = Join-Path $repo 'runtime\artifacts.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -ErrorAction Stop
$licenseOverrides = Get-Content -LiteralPath (Join-Path $repo 'runtime\license-overrides.json') -Raw | ConvertFrom-Json -ErrorAction Stop
$locks = @(
    (Join-Path (Join-Path $repo 'runtime') $manifest.dependencyLock),
    (Join-Path (Join-Path $repo 'runtime') $manifest.ocflDependencyLock),
    (Join-Path (Join-Path $repo 'runtime') $manifest.formatDependencyLock)
)
$requiredCacheFiles = @($manifest.artifacts | ForEach-Object { [string]$_.file })
$expectedCacheHashes = @{}
foreach ($artifact in @($manifest.artifacts)) { $expectedCacheHashes[[string]$artifact.file] = [string]$artifact.sha256 }
$dependencyCacheNames = @('spatial-dependencies', 'ocfl-dependencies', 'format-dependencies')
for ($index = 0; $index -lt $locks.Count; $index++) {
    $lock = Get-Content -LiteralPath $locks[$index] -Raw | ConvertFrom-Json -ErrorAction Stop
    foreach ($dependency in @($lock.dependencies | Where-Object ship)) {
        $relative = Join-Path $dependencyCacheNames[$index] ([string]$dependency.file)
        $requiredCacheFiles += $relative
        $expectedCacheHashes[$relative] = [string]$dependency.sha256
    }
}
$requiredCacheFiles = @($requiredCacheFiles | Sort-Object -Unique)
foreach ($relative in $requiredCacheFiles) {
    $artifactPath = Join-Path (Join-Path $rootPath 'cache') $relative
    if (!(Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
        throw "A pinned build artifact is missing; no cleanup was performed: $artifactPath"
    }
    if ((Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash -ne $expectedCacheHashes[$relative]) {
        throw "A pinned build artifact failed its checksum; no cleanup was performed: $artifactPath"
    }
}
foreach ($license in $licenseOverrides.PSObject.Properties) {
    $licensePath = Join-Path (Join-Path $rootPath 'cache') ([string]$license.Value.file)
    if (!(Test-Path -LiteralPath $licensePath -PathType Leaf) -or
        (Get-FileHash -LiteralPath $licensePath -Algorithm SHA256).Hash -ne [string]$license.Value.sha256) {
        throw "A pinned license text is missing or changed; no cleanup was performed: $licensePath"
    }
}

$toolsPath = Join-Path $rootPath 'tools'
$mavenArtifact = $manifest.artifacts | Where-Object id -eq 'maven' | Select-Object -First 1
$requiredToolDirectories = @(
    (Split-Path ([string]$manifest.javaHome) -Leaf),
    (Split-Path ([string]$manifest.neo4jHome) -Leaf),
    ([string]$mavenArtifact.file -replace '-bin\.zip$', '')
)
foreach ($toolDirectory in $requiredToolDirectories) {
    if (!(Test-Path -LiteralPath (Join-Path $toolsPath $toolDirectory) -PathType Container)) {
        throw "A pinned build tool is missing; no cleanup was performed: $toolDirectory"
    }
}

# Refuse to overlap a gate, a Stage 0 phase, a saved Codex run, or any Java
# process launched from this test root.
New-Item -ItemType Directory -Path $runnerPath -Force | Out-Null
New-Item -ItemType Directory -Path $evidencePath -Force | Out-Null
$lockPaths = @(
    (Join-Path $rootPath 'gate.lock'),
    (Join-Path $runPath 'phase.lock'),
    (Join-Path $runnerPath 'writer.lock')
)
$heldLocks = New-Object 'System.Collections.Generic.List[System.IO.FileStream]'
try {
    foreach ($lockPath in $lockPaths) {
        $heldLocks.Add([IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None'))
    }
} catch {
    foreach ($heldLock in $heldLocks) { $heldLock.Dispose() }
    throw 'A Stage 0 gate, phase, or Codex runner is active; no cleanup was performed.'
}

try {
    $activeJava = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.IndexOf($rootPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
    })
    if ($activeJava.Count -gt 0) {
        throw 'A Java process is using the Stage 0 root; stop it before cleanup.'
    }

    # Build the complete deletion list and inspect every target before the
    # first removal. This prevents traversal through links and avoids partial
    # cleanup caused by a late path-safety failure.
    $keepRootDirectories = @('runs', 'cache', 'tools', 'maven-repository', 'maven-user', 'runner', 'evidence')
    $keepRootFiles = @('gate.lock') + $checkpointNames
    $rootTargets = @()
    foreach ($entry in @(Get-ChildItem -LiteralPath $rootPath -Force -ErrorAction Stop)) {
        if ($entry.PSIsContainer) {
            if ($keepRootDirectories -contains $entry.Name) { continue }
        } elseif ($keepRootFiles -contains $entry.Name) { continue }
        $target = Assert-UnderRoot $entry.FullName
        Assert-NoReparseTree $target
        $rootTargets += $target
    }

    $runsPath = Join-Path $rootPath 'runs'
    foreach ($protectedPath in @(
        $runsPath,
        $runPath,
        (Join-Path $rootPath 'cache'),
        $toolsPath,
        (Join-Path $rootPath 'maven-repository'),
        (Join-Path $rootPath 'maven-user'),
        $runnerPath,
        $evidencePath
    )) {
        if (Test-Path -LiteralPath $protectedPath) { Assert-NoReparseTree $protectedPath }
    }
    $runsTargets = @()
    foreach ($entry in @(Get-ChildItem -LiteralPath $runsPath -Force -ErrorAction Stop)) {
        if ($entry.Name -eq 'stage0-host') { continue }
        $target = Assert-UnderRoot $entry.FullName
        Assert-NoReparseTree $target
        $runsTargets += $target
    }
    $hostRunTargets = @()
    foreach ($entry in @(Get-ChildItem -LiteralPath $runPath -Force -ErrorAction Stop)) {
        if ($entry.Name -in @('neo4j', 'owner.json', 'phase.lock')) { continue }
        $target = Assert-UnderRoot $entry.FullName
        Assert-NoReparseTree $target
        $hostRunTargets += $target
    }

    $requiredCacheRootNames = @($manifest.artifacts | ForEach-Object { [string]$_.file }) + $dependencyCacheNames + @('licenses')
    $cacheTargets = @()
    foreach ($entry in @(Get-ChildItem -LiteralPath (Join-Path $rootPath 'cache') -Force -ErrorAction Stop)) {
        if ($requiredCacheRootNames -contains $entry.Name) { continue }
        $target = Assert-UnderRoot $entry.FullName
        Assert-NoReparseTree $target
        $cacheTargets += $target
    }
    foreach ($index in 0..($locks.Count - 1)) {
        $lock = Get-Content -LiteralPath $locks[$index] -Raw | ConvertFrom-Json -ErrorAction Stop
        $dependencyDirectory = Join-Path (Join-Path $rootPath 'cache') $dependencyCacheNames[$index]
        $keep = @($lock.dependencies | Where-Object ship | ForEach-Object { [string]$_.file })
        foreach ($entry in @(Get-ChildItem -LiteralPath $dependencyDirectory -Force -ErrorAction Stop)) {
            if ($keep -contains $entry.Name) { continue }
            $target = Assert-UnderRoot $entry.FullName
            Assert-NoReparseTree $target
            $cacheTargets += $target
        }
    }
    $licenseDirectory = Join-Path (Join-Path $rootPath 'cache') 'licenses'
    if (Test-Path -LiteralPath $licenseDirectory -PathType Container) {
        $requiredLicenseNames = @($licenseOverrides.PSObject.Properties | ForEach-Object { Split-Path ([string]$_.Value.file) -Leaf })
        foreach ($entry in @(Get-ChildItem -LiteralPath $licenseDirectory -Force -ErrorAction Stop)) {
            if ($requiredLicenseNames -contains $entry.Name) { continue }
            $target = Assert-UnderRoot $entry.FullName
            Assert-NoReparseTree $target
            $cacheTargets += $target
        }
    }

    $requiredToolNames = $requiredToolDirectories + @('neo4j.sha256', 'maven.sha256', 'temurin.sha256')
    $toolTargets = @()
    foreach ($entry in @(Get-ChildItem -LiteralPath $toolsPath -Force -ErrorAction Stop)) {
        if ($requiredToolNames -contains $entry.Name) { continue }
        $target = Assert-UnderRoot $entry.FullName
        Assert-NoReparseTree $target
        $toolTargets += $target
    }

    $runnerTargets = @()
    foreach ($entry in @(Get-ChildItem -LiteralPath $runnerPath -Force -ErrorAction Stop)) {
        if ($entry.Name -eq 'writer.lock') { continue }
        $target = Assert-UnderRoot $entry.FullName
        Assert-NoReparseTree $target
        $runnerTargets += $target
    }
    $evidenceTargets = @()
    foreach ($entry in @(Get-ChildItem -LiteralPath $evidencePath -Force -ErrorAction Stop)) {
        $target = Assert-UnderRoot $entry.FullName
        Assert-NoReparseTree $target
        $evidenceTargets += $target
    }

    # Evidence, input snapshots, old builds, diagnostic outputs, runner history,
    # stale runtime versions and unpinned artifacts are regenerated when needed.
    # Maven caches and the accepted checkpoints remain for fast cumulative runs.
    $targets = @($rootTargets + $runsTargets + $hostRunTargets + $cacheTargets + $toolTargets + $runnerTargets + $evidenceTargets)
    foreach ($target in $targets) {
        Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop
    }

    New-Item -ItemType Directory -Path $evidencePath -Force | Out-Null
    Write-Output "Stage 0 cleanup complete. Removed $($targets.Count) obsolete path trees."
    Write-Output 'Retained the owned stage0-host installation, accepted stage checkpoints, pinned tools/artifacts, and Maven caches.'
} finally {
    foreach ($heldLock in $heldLocks) { $heldLock.Dispose() }
}
