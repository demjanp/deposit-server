$ErrorActionPreference = 'Stop'
if ($args.Count -ne 0) { throw 'Cleanup-Stage0Evidence.ps1 takes no command-line arguments.' }

# Keep the reusable installation, pinned tool/dependency caches, Maven
# caches, and current stage checkpoints. Delete only paths classified as
# generated test output; unknown paths may contain recovery state.
$rootPath = [IO.Path]::GetFullPath('C:\data_processed\Deposit_v2\stage0').TrimEnd('\')
$repo = Split-Path $PSScriptRoot -Parent
$runPath = Join-Path $rootPath 'runs\stage0-host'
$runnerPath = Join-Path $rootPath 'runner'
$evidencePath = Join-Path $rootPath 'evidence'
$performancePath = Join-Path $rootPath 'performance'

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

 $cleanupBarrier=$null
try {
    $cleanupBarrier=[IO.File]::Open((Join-Path $rootPath 'cleanup-barrier.lock'),'OpenOrCreate','ReadWrite','None')
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
$checkpointSourceSnapshots = @()
foreach ($checkpointFile in @(Get-ChildItem -LiteralPath $rootPath -File -Force | Where-Object { $_.Name -match $checkpointPattern })) {
    try {
        $checkpoint = Get-Content -LiteralPath $checkpointFile.FullName -Raw | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "Cannot parse prerequisite checkpoint '$($checkpointFile.FullName)'; no cleanup was performed."
    }
    $snapshotProperty=$checkpoint.PSObject.Properties['sourceSnapshot']
    if($snapshotProperty -and $null -ne $snapshotProperty.Value) {
        $snapshot=$snapshotProperty.Value
        $pathProperty=$snapshot.PSObject.Properties['path']
        $inputsProperty=$snapshot.PSObject.Properties['inputs']
        if(!$pathProperty -or !$inputsProperty -or [string]::IsNullOrWhiteSpace([string]$pathProperty.Value) -or [string]$inputsProperty.Value -notmatch '^[A-Fa-f0-9]{64}$') {
            throw "Invalid source snapshot metadata in prerequisite checkpoint '$($checkpointFile.FullName)'; no cleanup was performed."
        }
        try {$snapshotPath=[IO.Path]::GetFullPath([string]$pathProperty.Value)} catch {throw "Invalid source snapshot path in prerequisite checkpoint '$($checkpointFile.FullName)'; no cleanup was performed."}
        $inputsRoot = [IO.Path]::GetFullPath((Join-Path $rootPath 'inputs')).TrimEnd('\') + '\'
        $expectedName = [string]$inputsProperty.Value + '.zip'
        if (!$snapshotPath.StartsWith($inputsRoot,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($snapshotPath) -ne $expectedName) {
            throw "Checkpoint source snapshot path does not match its source identity in '$($checkpointFile.FullName)'; no cleanup was performed."
        }
        if (Test-Path -LiteralPath $snapshotPath -PathType Leaf) { $checkpointSourceSnapshots += $snapshotPath }
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
    New-Item -ItemType Directory -Path $evidencePath -Force | Out-Null
    New-Item -ItemType Directory -Path $performancePath -Force | Out-Null
    $activeJava = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.IndexOf($rootPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
    })
    if ($activeJava.Count -gt 0) {
        throw 'A Java process is using the Stage 0 root; stop it before cleanup.'
    }
    $activeCodex = @(Get-CimInstance Win32_Process -Filter "Name='codex.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.IndexOf((Join-Path $rootPath 'runner'), [StringComparison]::OrdinalIgnoreCase) -ge 0
    })
    if ($activeCodex.Count -gt 0) {throw 'A Codex child process is using Stage 0 runner state; no cleanup was performed.'}

    # Classify only registered result namespaces. Manifests describe paths; result
    # contents are trusted and are never hashed for retention decisions.
    . (Join-Path $repo 'scripts\Inputs.ps1')
    $resultGroups = New-Object 'System.Collections.Generic.List[object]'
    $coveredResultPaths = @{}
    $protectedInputFiles = @($checkpointSourceSnapshots)
    $scanRoots = @(
        @{path=$evidencePath;kind='evidence'},
        @{path=(Join-Path $runPath 'evidence');kind='phase'},
        @{path=$runnerPath;kind='runner'},
        @{path=$performancePath;kind='performance'}
    )
    # Validate every candidate tree before reconciling or retiring any manifest.
    foreach($scan in $scanRoots) {if(Test-Path -LiteralPath $scan.path -PathType Container) {Assert-NoReparseTree $scan.path}}
    $inputsPath=Join-Path $rootPath 'inputs'
    if(Test-Path -LiteralPath $inputsPath -PathType Container) {Assert-NoReparseTree $inputsPath}

    # Adapt historical schema-1 Stage 9 group manifests without checking their
    # payload hashes. Their declared sibling paths remain one retention group.
    foreach($manifestFile in @(Get-ChildItem -LiteralPath $evidencePath -Filter 'attempt-manifest.json' -File -Recurse -Force -ErrorAction Stop)) {
        $old=Get-Content -LiteralPath $manifestFile.FullName -Raw | ConvertFrom-Json -ErrorAction Stop
        if($old.schemaVersion -ne 1 -or $old.attemptId -notmatch '^[A-Za-z0-9_-]+$' -or $old.status -notin @('accepted','failed','incomplete','running')) {
            throw "Unsupported or invalid legacy result manifest; no cleanup was performed: $($manifestFile.FullName)"
        }
        $directory=[IO.Path]::GetFullPath((Split-Path $manifestFile.FullName -Parent)).TrimEnd('\')
        if($directory -ne [IO.Path]::GetFullPath((Join-Path $evidencePath $old.attemptId)).TrimEnd('\')) {
            throw "Legacy result manifest is outside its declared result directory: $($manifestFile.FullName)"
        }
        $paths=@($directory)
        foreach($relative in @($old.ownedPaths)) {
            $owned=[IO.Path]::GetFullPath((Join-Path $rootPath ([string]$relative))).TrimEnd('\')
            if(!$owned.StartsWith($evidencePath+'\',[StringComparison]::OrdinalIgnoreCase) -or
               [IO.Path]::GetFullPath((Split-Path $owned -Parent)).TrimEnd('\') -ne $evidencePath -or
               [IO.Path]::GetFileName($owned) -notin @($old.attemptId,($old.attemptId+'-runtime'),($old.attemptId+'-runtime-live')) -or
               !(Test-Path -LiteralPath $owned -PathType Container)) {
                throw "Legacy result ownership is invalid; no cleanup was performed: $relative"
            }
            if($paths -notcontains $owned) {$paths+= $owned}
        }
        $localOwned=@{}
        foreach($owned in $paths) {
            $key=$owned.ToLowerInvariant()
            if($coveredResultPaths.ContainsKey($key) -or $localOwned.ContainsKey($key)) {throw "Conflicting legacy result ownership: $owned"}
            $localOwned[$key]=$true
            $coveredResultPaths[$key]=$true
        }
        $started=try {[DateTime]::Parse([string]$old.startedUtc).ToUniversalTime()} catch {throw "Invalid legacy result start time: $($manifestFile.FullName)"}
        $retention=if($old.status -eq 'accepted'){'legacy'}else{'retain'}
        $outcome=if($old.status -eq 'accepted'){'accepted'}elseif($old.status -eq 'failed'){'failed'}else{'incomplete'}
        $references=@()
        foreach($reference in @($old.requiredFiles)) {
            $refPath=[IO.Path]::GetFullPath((Join-Path $rootPath ([string]$reference.path)))
            if(!$refPath.StartsWith($rootPath+'\',[StringComparison]::OrdinalIgnoreCase)) {throw "Legacy result reference escapes Stage 0: $($reference.path)"}
            $inputsRoot=[IO.Path]::GetFullPath((Join-Path $rootPath 'inputs')).TrimEnd('\')+'\'
            if($refPath.StartsWith($inputsRoot,[StringComparison]::OrdinalIgnoreCase)) {
                if([IO.Path]::GetDirectoryName($refPath).TrimEnd('\') -ne $inputsRoot.TrimEnd('\') -or [IO.Path]::GetFileName($refPath) -notmatch '^[a-fA-F0-9]{64}\.zip$') {throw "Legacy source snapshot reference is not content-addressed: $($reference.path)"}
                $references+= $refPath
            } elseif(@($scanRoots | Where-Object {$refPath.StartsWith([IO.Path]::GetFullPath($_.path).TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)}).Count) {
                $references+= $refPath
            }
        }
        $resultGroups.Add(@{resultId=[string]$old.attemptId;ownerResultId=$null;kind='gate';manifestPath=$manifestFile.FullName;manifestVersion=1;paths=$paths;startedUtc=$started;lifecycle=$(if($old.status -eq 'running'){'interrupted'}else{'finished'});outcome=$outcome;retention=$retention;references=$references})
    }

    foreach($scan in $scanRoots) {
        if(!(Test-Path -LiteralPath $scan.path -PathType Container)) {continue}
        foreach($entry in @(Get-ChildItem -LiteralPath $scan.path -Force -ErrorAction Stop)) {
            if($scan.kind -eq 'runner' -and $entry.Name -in @('writer.lock','repair-counts.json','work-packages')) {continue}
            if(!$entry.PSIsContainer) {
                $filePath=[IO.Path]::GetFullPath($entry.FullName)
                $resultGroups.Add(@{resultId=$entry.Name;ownerResultId=$null;kind=$scan.kind;manifestPath=$null;manifestVersion=0;paths=@($filePath);startedUtc=[DateTime]::MinValue;lifecycle='interrupted';outcome='unknown';retention='legacy';references=@()})
                continue
            }
            $directory=[IO.Path]::GetFullPath($entry.FullName).TrimEnd('\')
            $directoryKey=$directory.ToLowerInvariant()
            if($coveredResultPaths.ContainsKey($directoryKey)) {
                if(Test-Path -LiteralPath (Join-Path $directory 'result-manifest.json') -PathType Leaf) {throw "Conflicting schema-1/schema-2 result ownership: $directory"}
                continue
            }
            $resultManifest=Join-Path $directory 'result-manifest.json'
            $oldManifest=Join-Path $directory 'attempt-manifest.json'
            if(Test-Path -LiteralPath $oldManifest -PathType Leaf) {throw "Legacy manifest is not in a supported evidence group: $oldManifest"}
            if(Test-Path -LiteralPath $resultManifest -PathType Leaf) {
                $m=Get-Content -LiteralPath $resultManifest -Raw | ConvertFrom-Json -ErrorAction Stop
                if($m.schemaVersion -ne 2 -or $m.resultId -notmatch '^[A-Za-z0-9_-]+$' -or
                   $m.kind -notin @('gate','environment','phase','live-runner','artifact-audit','runner','performance') -or
                   $m.lifecycle -notin @('running','finished','interrupted') -or
                   $m.outcome -notin @('accepted','passed','failed','incomplete','unknown') -or
                   $m.retention -notin @('retain','legacy') -or @($m.ownedPaths).Count -ne 1) {
                    throw "Unsupported or invalid result manifest; no cleanup was performed: $resultManifest"
                }
                $declared=[IO.Path]::GetFullPath((Join-Path $rootPath ([string]$m.ownedPaths[0]))).TrimEnd('\')
                if($declared -ne $directory -or !$declared.StartsWith([IO.Path]::GetFullPath($scan.path).TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) {
                    throw "Result ownership does not match its manifest location: $resultManifest"
                }
                try {$started=[DateTime]::Parse([string]$m.startedUtc).ToUniversalTime()} catch {throw "Invalid result start time: $resultManifest"}
                $lifecycle=[string]$m.lifecycle;$outcome=[string]$m.outcome;$retention=[string]$m.retention
                $manifestNeedsWrite=$false
                if($lifecycle -eq 'running') {
                    $lifecycle='interrupted';$outcome='incomplete';$retention='retain'
                    $manifestNeedsWrite=$true
                }
                $references=@()
                $inputsRoot=[IO.Path]::GetFullPath((Join-Path $rootPath 'inputs')).TrimEnd('\')+'\'
                $workPackageRoot=[IO.Path]::GetFullPath((Join-Path $runnerPath 'work-packages')).TrimEnd('\')+'\'
                $approvedResultPrefixes=@($scanRoots | ForEach-Object {[IO.Path]::GetFullPath($_.path).TrimEnd('\')+'\'})
                foreach($reference in @($m.requiredReferences)) {
                    $refPath=[IO.Path]::GetFullPath((Join-Path $rootPath ([string]$reference)))
                    if(!$refPath.StartsWith($rootPath+'\',[StringComparison]::OrdinalIgnoreCase)) {throw "Result reference escapes Stage 0: $reference"}
                    if($refPath.StartsWith($inputsRoot,[StringComparison]::OrdinalIgnoreCase)) {
                        if([IO.Path]::GetDirectoryName($refPath).TrimEnd('\') -ne $inputsRoot.TrimEnd('\') -or [IO.Path]::GetFileName($refPath) -notmatch '^[a-fA-F0-9]{64}\.zip$') {throw "Source snapshot reference is not content-addressed: $reference"}
                    } elseif(!$refPath.StartsWith($workPackageRoot,[StringComparison]::OrdinalIgnoreCase) -and
                             !(@($approvedResultPrefixes | Where-Object {$refPath.StartsWith($_,[StringComparison]::OrdinalIgnoreCase)}).Count)) {
                        throw "Result reference is outside approved dependency roots: $reference"
                    }
                    $references+= $refPath
                }
                $resultGroups.Add(@{resultId=[string]$m.resultId;ownerResultId=[string]$m.ownerResultId;kind=[string]$m.kind;manifestPath=$resultManifest;manifestVersion=2;manifestNeedsWrite=$manifestNeedsWrite;paths=@($directory);startedUtc=$started;lifecycle=$lifecycle;outcome=$outcome;retention=$retention;references=$references})
            } else {
                $resultGroups.Add(@{resultId=$entry.Name;ownerResultId=$null;kind=$scan.kind;manifestPath=$null;manifestVersion=0;paths=@($directory);startedUtc=[DateTime]::MinValue;lifecycle='interrupted';outcome='unknown';retention='legacy';references=@()})
            }
        }
    }

    # Keep only the newest unsuccessful result. Its explicit replacement makes
    # older retained failures legacy, including results from earlier schemas.
    $latestUnsuccessful=@($resultGroups | Where-Object {$_.retention -eq 'retain' -and $_.outcome -in @('failed','incomplete','unknown')} |
        Sort-Object -Property @{Expression={$_.startedUtc};Descending=$true} | Select-Object -First 1)
    $latestOwnerId=$null
    if($latestUnsuccessful.Count) {
        $latestOwnerId=if($latestUnsuccessful[0].ownerResultId){[string]$latestUnsuccessful[0].ownerResultId}else{[string]$latestUnsuccessful[0].resultId}
        $retainedMembers=@($resultGroups | Where-Object {$_.resultId -eq $latestOwnerId -or $_.ownerResultId -eq $latestOwnerId})
        foreach($member in $retainedMembers) {$member.retention='retain'}
    }
    # Older unsuccessful results are eligible only after their explicit
    # replacement is known. Dependencies of the surviving retained group are
    # resolved below before any result can enter the deletion list.
    foreach($group in $resultGroups) {
        $groupOwner=if($group.ownerResultId){[string]$group.ownerResultId}else{[string]$group.resultId}
        $keep=$latestUnsuccessful.Count -and $groupOwner -eq $latestOwnerId
        if($group.retention -eq 'retain' -and !$keep) {
            $group.retention='legacy'
        }
    }
    $protectedInputFiles=@($checkpointSourceSnapshots)
    $dependencyChanged=$true
    while($dependencyChanged) {
        $dependencyChanged=$false
        $retainedGroups=@($resultGroups | Where-Object {$_.retention -eq 'retain'})
        foreach($retained in $retainedGroups) {
            $retainedOwner=if($retained.ownerResultId){[string]$retained.ownerResultId}else{[string]$retained.resultId}
            foreach($reference in $retained.references) {
                $referencePath=[IO.Path]::GetFullPath($reference)
                $inputsPrefix=[IO.Path]::GetFullPath((Join-Path $rootPath 'inputs')).TrimEnd('\')+'\'
                $workPackagesPrefix=[IO.Path]::GetFullPath((Join-Path $runnerPath 'work-packages')).TrimEnd('\')+'\'
                if($referencePath.StartsWith($inputsPrefix,[StringComparison]::OrdinalIgnoreCase) -or $referencePath.StartsWith($workPackagesPrefix,[StringComparison]::OrdinalIgnoreCase)) {
                    $protectedInputFiles+= $referencePath
                    continue
                }
                foreach($dependency in $resultGroups) {
                    $dependencyOwner=if($dependency.ownerResultId){[string]$dependency.ownerResultId}else{[string]$dependency.resultId}
                    if($dependencyOwner -eq $retainedOwner) {continue}
                    $ownsReference=@($dependency.paths | Where-Object {
                        $ownedPath=[IO.Path]::GetFullPath($_).TrimEnd('\')
                        $referencePath.Equals($ownedPath,[StringComparison]::OrdinalIgnoreCase) -or $referencePath.StartsWith($ownedPath+'\',[StringComparison]::OrdinalIgnoreCase)
                    }).Count -gt 0
                    if($ownsReference -and $dependency.retention -ne 'retain') {
                        foreach($member in $resultGroups) {
                            $memberOwner=if($member.ownerResultId){[string]$member.ownerResultId}else{[string]$member.resultId}
                            if($memberOwner -eq $dependencyOwner) {$member.retention='retain';if($member.manifestVersion -eq 2){$member.manifestNeedsWrite=$true}}
                        }
                        $dependencyChanged=$true
                    }
                }
            }
        }
    }
    $deletionGroups=New-Object 'System.Collections.Generic.List[object]'
    foreach($group in $resultGroups) {
        if($group.retention -eq 'legacy') {
            if($group.manifestVersion -eq 2) {$group.manifestNeedsWrite=$true}
            $deletionGroups.Add($group)
        }
    }
    # Build the complete deletion list and inspect every target before the
    # first removal. This prevents traversal through links and avoids partial
    # cleanup caused by a late path-safety failure.
    $keepRootDirectories = @('runs', 'cache', 'tools', 'maven-repository', 'maven-user', 'runner', 'evidence', 'performance', 'inputs')
    $keepRootFiles = @('gate.lock', 'cleanup-barrier.lock') + $checkpointNames
    $rootTargets = @()
    foreach ($entry in @(Get-ChildItem -LiteralPath $rootPath -Force -ErrorAction Stop)) {
        if ($entry.PSIsContainer) {
            if ($keepRootDirectories -contains $entry.Name) { continue }
        } elseif ($keepRootFiles -contains $entry.Name) { continue }
        Write-Warning "Retaining unclassified Stage 0 root path: $($entry.FullName)"
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
        $evidencePath,
        $performancePath
    )) {
        if (Test-Path -LiteralPath $protectedPath) { Assert-NoReparseTree $protectedPath }
    }
    $runsTargets = @()
    foreach ($entry in @(Get-ChildItem -LiteralPath $runsPath -Force -ErrorAction Stop)) {
        if ($entry.Name -eq 'stage0-host') { continue }
        Write-Warning "Retaining unclassified run path: $($entry.FullName)"
    }
    $hostRunTargets = @()
    foreach ($entry in @(Get-ChildItem -LiteralPath $runPath -Force -ErrorAction Stop)) {
        if ($entry.Name -in @('neo4j', 'owner.json', 'phase.lock','evidence')) { continue }
        if ($entry.Name -notin @('tmp', 'checkpoint.json', 'checkpoint.json.previous')) {
            Write-Warning "Retaining unclassified stage0-host path: $($entry.FullName)"
            continue
        }
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
        if ($entry.Name -in @('writer.lock','repair-counts.json','work-packages')) { continue }
    }
    $evidenceTargets = @()
    $resultInputReferences=@()
    foreach($group in $deletionGroups) {
        foreach($path in $group.paths) {
            if(!(Test-Path -LiteralPath $path)) {continue}
            $target=Assert-UnderRoot $path
            Assert-NoReparseTree $target
            if(!$target.StartsWith($runnerPath+'\',[StringComparison]::OrdinalIgnoreCase) -and $evidenceTargets -notcontains $target) {$evidenceTargets+= $target}
        }
        foreach($reference in $group.references) {
            $referencePath=[IO.Path]::GetFullPath($reference)
            $inputsPrefix=[IO.Path]::GetFullPath((Join-Path $rootPath 'inputs')).TrimEnd('\')+'\'
            if($referencePath.StartsWith($inputsPrefix,[StringComparison]::OrdinalIgnoreCase)) {$resultInputReferences+= $referencePath}
        }
    }

    $inputTargets = @()
    if (Test-Path -LiteralPath $inputsPath -PathType Container) {
        Assert-NoReparseTree $inputsPath
        foreach ($snapshotPath in @($resultInputReferences | Sort-Object -Unique)) {
            if ($protectedInputFiles -contains $snapshotPath) { continue }
            if (Test-Path -LiteralPath $snapshotPath -PathType Leaf) {
                $inputTargets += Assert-UnderRoot $snapshotPath
            }
        }
    }

    $workPackageRoot=Join-Path $runnerPath 'work-packages'
    if(Test-Path -LiteralPath $workPackageRoot -PathType Container) {
        foreach($entry in @(Get-ChildItem -LiteralPath $workPackageRoot -Force -ErrorAction Stop)) {
            $path=[IO.Path]::GetFullPath($entry.FullName).TrimEnd('\')
            $isProtected=@($protectedInputFiles | Where-Object {([IO.Path]::GetFullPath($_)).StartsWith($path+'\',[StringComparison]::OrdinalIgnoreCase) -or ([IO.Path]::GetFullPath($_) -eq $path)}).Count -gt 0
            if($isProtected) {continue}
            $target=Assert-UnderRoot $path;Assert-NoReparseTree $target;$runnerTargets+= $target
        }
    }
    foreach($group in $deletionGroups) {
        foreach($path in $group.paths) {
            if(!$path.StartsWith($runnerPath+'\',[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $path)) {continue}
            $target=Assert-UnderRoot $path;Assert-NoReparseTree $target;$runnerTargets+= $target
        }
    }

    # Evidence, input snapshots, old builds, diagnostic outputs, runner history,
    # stale runtime versions and unpinned artifacts are regenerated when needed.
    # Maven caches and the accepted checkpoints remain for fast cumulative runs.
    $targets = @($rootTargets + $runsTargets + $hostRunTargets + $cacheTargets + $toolTargets + $runnerTargets + $evidenceTargets + $inputTargets | Sort-Object -Unique)
    # Publish lifecycle/retention reconciliation only after every selected
    # deletion path has passed ownership and reparse-point validation.
    foreach($group in @($resultGroups | Where-Object {$_.manifestVersion -eq 2 -and $_.manifestNeedsWrite})) {
        $m=Get-Content -LiteralPath $group.manifestPath -Raw | ConvertFrom-Json -ErrorAction Stop
        $requiredReferences=if($group.retention -eq 'legacy') {@()} else {@($m.requiredReferences)}
        $finishedUtc=if($group.lifecycle -eq 'running') {[string]$m.finishedUtc} elseif($m.lifecycle -eq 'running') {[DateTime]::UtcNow.ToString('o')} else {[string]$m.finishedUtc}
        $updated=@{resultId=[string]$m.resultId;ownerResultId=[string]$m.ownerResultId;kind=[string]$m.kind;lifecycle=[string]$group.lifecycle;outcome=[string]$group.outcome;retention=[string]$group.retention;startedUtc=[string]$m.startedUtc;finishedUtc=$finishedUtc;sourceIdentity=[string]$m.sourceIdentity;requiredReferences=$requiredReferences}
        Write-Stage0ResultManifest $rootPath $group.paths[0] $updated | Out-Null
    }
    foreach ($target in $targets) {
        if(Test-Path -LiteralPath $target) {Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop}
    }

    New-Item -ItemType Directory -Path $evidencePath -Force | Out-Null
    Write-Output "Stage 0 cleanup complete. Removed $($targets.Count) obsolete path trees."
    Write-Output 'Retained the owned stage0-host installation, accepted stage checkpoints, pinned tools/artifacts, Maven caches, and latest unsuccessful manifested result.'
} finally {
    foreach ($heldLock in $heldLocks) { $heldLock.Dispose() }
}
} finally {if($cleanupBarrier){$cleanupBarrier.Dispose()}}
