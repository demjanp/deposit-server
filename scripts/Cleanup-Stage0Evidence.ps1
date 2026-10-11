param(
    [string]$Root = 'C:\data_processed\Deposit_v2\stage0',
    [ValidateRange(1, 3650)][int]$MinimumAgeDays = 30,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string[]]$AbandonResumePrefix = @(),
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\')
$evidencePath = [IO.Path]::GetFullPath((Join-Path $rootPath 'evidence')).TrimEnd('\')

if (!(Test-Path -LiteralPath $rootPath -PathType Container)) { throw "Stage 0 root does not exist: $rootPath" }
if (!(Test-Path -LiteralPath $evidencePath -PathType Container)) { throw "Evidence directory does not exist: $evidencePath" }

function Assert-NoReparseAncestors([string]$Path) {
    $current = Get-Item -LiteralPath $Path -Force
    while ($null -ne $current) {
        if (($current.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Refusing cleanup through reparse point: $($current.FullName)" }
        $current = $current.Parent
    }
}

Assert-NoReparseAncestors $rootPath
Assert-NoReparseAncestors $evidencePath
$gateLockPath = Join-Path $rootPath 'gate.lock'
if ((Test-Path -LiteralPath $gateLockPath) -and ((Get-Item -LiteralPath $gateLockPath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Refusing reparse-point gate lock: $gateLockPath" }

# Share the gate and runner locks so cleanup cannot overlap either writer.
$locks = New-Object 'System.Collections.Generic.List[System.IO.FileStream]'
try {
    $locks.Add([IO.File]::Open($gateLockPath, 'OpenOrCreate', 'ReadWrite', 'None'))
    $runnerDirectory = Join-Path $rootPath 'runner'
    if (Test-Path -LiteralPath $runnerDirectory -PathType Container) {
        if (((Get-Item -LiteralPath $runnerDirectory -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Refusing reparse-point runner directory: $runnerDirectory" }
        $runnerLockPath = Join-Path $runnerDirectory 'writer.lock'
        if ((Test-Path -LiteralPath $runnerLockPath) -and ((Get-Item -LiteralPath $runnerLockPath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Refusing reparse-point runner lock: $runnerLockPath" }
        $locks.Add([IO.File]::Open($runnerLockPath, 'OpenOrCreate', 'ReadWrite', 'None'))
    }
} catch {
    foreach ($heldLock in $locks) { $heldLock.Dispose() }
    throw 'A Stage 0 gate is running or the gate lock is unavailable. Run cleanup after the gate has finished.'
}

function Add-JsonStrings($Value, [System.Collections.Generic.List[string]]$Strings) {
    if ($null -eq $Value) { return }
    if ($Value -is [string]) { $Strings.Add($Value); return }
    if ($Value -is [System.Collections.IDictionary]) {
        foreach ($item in $Value.Values) { Add-JsonStrings $item $Strings }
        return
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        foreach ($item in $Value) { Add-JsonStrings $item $Strings }
        return
    }
    if ($Value -is [pscustomobject]) {
        foreach ($property in $Value.PSObject.Properties) { Add-JsonStrings $property.Value $Strings }
    }
}

function Add-ResumeReferences($Value, [System.Collections.Generic.List[string]]$Strings) {
    if ($null -eq $Value) { return }
    if ($Value -is [System.Collections.IDictionary]) {
        foreach ($key in $Value.Keys) {
            if ([string]$key -ieq 'resumeFrom' -and $Value[$key] -is [string]) { $Strings.Add($Value[$key]) }
            else { Add-ResumeReferences $Value[$key] $Strings }
        }
        return
    }
    if ($Value -is [pscustomobject]) {
        foreach ($property in $Value.PSObject.Properties) {
            if ($property.Name -ieq 'resumeFrom' -and $property.Value -is [string]) { $Strings.Add($property.Value) }
            else { Add-ResumeReferences $property.Value $Strings }
        }
        return
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        foreach ($item in $Value) { Add-ResumeReferences $item $Strings }
    }
}

function Add-GateReferences($Value, [System.Collections.Generic.List[string]]$Strings) {
    if ($null -eq $Value) { return }
    if ($Value -is [System.Collections.IDictionary]) {
        foreach ($key in $Value.Keys) {
            if ([string]$key -ieq 'gate' -and $Value[$key] -is [string]) { $Strings.Add($Value[$key]) }
            else { Add-GateReferences $Value[$key] $Strings }
        }
        return
    }
    if ($Value -is [pscustomobject]) {
        foreach ($property in $Value.PSObject.Properties) {
            if ($property.Name -ieq 'gate' -and $property.Value -is [string]) { $Strings.Add($property.Value) }
            else { Add-GateReferences $property.Value $Strings }
        }
        return
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        foreach ($item in $Value) { Add-GateReferences $item $Strings }
    }
}

function Get-ReferenceRecords {
    $records = New-Object 'System.Collections.Generic.List[object]'
    $metadataNames = @('checkpoint.json', 'cumulative-checkpoint.json', 'evidence-paths.json', 'results.json', 'state.json', 'repair-counts.json')
    $metadataFiles = @()

    # Root checkpoints and their .previous/.partial copies record accepted gates and resume state.
    $metadataFiles += Get-ChildItem -LiteralPath $rootPath -File -Force -ErrorAction Stop |
        Where-Object { $_.Name -match '^stage.*checkpoint\.json(?:\.(?:previous|partial))?$' }
    # Gate, runtime and runner metadata contain explicit links and resumable state.
    $metadataFiles += Get-ChildItem -LiteralPath $evidencePath -File -Recurse -Force -ErrorAction Stop |
        Where-Object { $_.Name -in $metadataNames }
    foreach ($directoryName in @('runs', 'runner')) {
        $directoryPath = Join-Path $rootPath $directoryName
        if (Test-Path -LiteralPath $directoryPath -PathType Container) {
            $metadataFiles += Get-ChildItem -LiteralPath $directoryPath -File -Recurse -Force -ErrorAction Stop |
                Where-Object { $_.Name -in $metadataNames -or $_.Name -like '*.result.json' }
        }
    }

    $hubDirectory = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'deposit-hub'
    foreach ($documentName in @('implementation_plan.md', 'implementation_status.md')) {
        $documentPath = Join-Path $hubDirectory $documentName
        if (!(Test-Path -LiteralPath $documentPath -PathType Leaf)) { throw "Required retention document is missing: $documentPath" }
        try {
            $documentText = Get-Content -LiteralPath $documentPath -Raw -ErrorAction Stop
            $records.Add([pscustomobject]@{ Source = $documentPath; Value = $documentText; Kind = 'document' })
        } catch { throw "Could not read retention document '$documentPath'; no evidence will be deleted." }
    }

    foreach ($file in $metadataFiles) {
        try {
            $json = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json -ErrorAction Stop
            $paths = New-Object 'System.Collections.Generic.List[string]'
            Add-JsonStrings $json $paths
            foreach ($path in $paths) { $records.Add([pscustomobject]@{ Source = $file.FullName; Value = $path; Kind = 'path' }) }
            $resumeReferences = New-Object 'System.Collections.Generic.List[string]'
            Add-ResumeReferences $json $resumeReferences
            foreach ($resumeReference in $resumeReferences) { $records.Add([pscustomobject]@{ Source = $file.FullName; Value = $resumeReference; Kind = 'resume' }) }
            $gateReferences = New-Object 'System.Collections.Generic.List[string]'
            Add-GateReferences $json $gateReferences
            foreach ($gateReference in $gateReferences) { $records.Add([pscustomobject]@{ Source = $file.FullName; Value = $gateReference; Kind = 'gate' }) }
        } catch {
            throw "Could not read cleanup reference metadata '$($file.FullName)'; no evidence will be deleted."
        }
    }
    return $records.ToArray()
}

function Test-ContainsReparsePoint([string]$Path) {
    $pending = New-Object 'System.Collections.Generic.Stack[string]'
    $pending.Push($Path)
    while ($pending.Count -gt 0) {
        $current = $pending.Pop()
        $item = Get-Item -LiteralPath $current -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { return $true }
        if ($item.PSIsContainer) {
            foreach ($child in Get-ChildItem -LiteralPath $current -Force -ErrorAction Stop) { $pending.Push($child.FullName) }
        }
    }
    return $false
}

try {
    $cutoff = [DateTime]::UtcNow.AddDays(-$MinimumAgeDays)
    $directories = @(Get-ChildItem -LiteralPath $evidencePath -Directory -Force | Sort-Object Name)
    $candidates = New-Object 'System.Collections.Generic.List[object]'
    $stage0Failures = @{}
    $stage0CheckpointFiles = @(Get-ChildItem -LiteralPath $rootPath -File -Force | Where-Object { $_.Name -match '^stage0-checkpoint\.json(?:\.(?:previous|partial))?$' })
    foreach ($file in $stage0CheckpointFiles) {
        try { $stage0Checkpoint = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json -ErrorAction Stop } catch { throw "Could not read '$($file.FullName)'; no evidence will be deleted." }
        if ($stage0Checkpoint.status -eq 'failed' -and $stage0Checkpoint.gate) {
            $updated = $file.LastWriteTimeUtc
            if ($stage0Checkpoint.updated) { try { $updated = [DateTime]::Parse($stage0Checkpoint.updated).ToUniversalTime() } catch { throw "Invalid timestamp in '$($file.FullName)'; no evidence will be deleted." } }
            $gateName = [string]$stage0Checkpoint.gate
            if (!$stage0Failures.ContainsKey($gateName) -or $updated -gt $stage0Failures[$gateName]) { $stage0Failures[$gateName] = $updated }
        }
    }

    for ($index = 0; $index -lt $directories.Count; $index++) {
        $directory = $directories[$index]
        Write-Progress -Activity 'Reviewing Stage 0 evidence' -Status "$($index + 1) of $($directories.Count): $($directory.Name)" -PercentComplete ([int](100 * ($index + 1) / [Math]::Max(1, $directories.Count)))

        $checkpointPath = Join-Path $directory.FullName 'checkpoint.json'
        $checkpoint = $null
        $updated = $null
        if (Test-Path -LiteralPath $checkpointPath -PathType Leaf) {
            try { $checkpoint = Get-Content -LiteralPath $checkpointPath -Raw | ConvertFrom-Json -ErrorAction Stop } catch {
                Write-Warning "Keeping '$($directory.Name)': checkpoint is unreadable."
                continue
            }
            if ($checkpoint.status -eq 'failed' -and $checkpoint.updated) {
                try { $updated = [DateTime]::Parse($checkpoint.updated).ToUniversalTime() } catch { continue }
            }
            if ($checkpoint.status -and $checkpoint.status -ne 'failed') { continue }
            if ([string]$checkpoint.stage -in @('6', '6.5') -and $directory.Name -notin $AbandonResumePrefix) { continue }
        }
        # Stage 0 records its failed status in the root checkpoint; its evidence folder has results.json instead.
        if ($null -eq $updated -and $stage0Failures.ContainsKey($directory.Name)) { $updated = $stage0Failures[$directory.Name] }
        if ($null -eq $updated) { continue }
        if ($updated -gt $cutoff) { continue }

        $candidates.Add([pscustomobject]@{ Directory = $directory; Checkpoint = $checkpoint; Updated = $updated })
    }
    Write-Progress -Activity 'Reviewing Stage 0 evidence' -Completed

    $referenceRecords = @(Get-ReferenceRecords)
    $logPath = Join-Path $rootPath 'evidence-cleanup.jsonl'
    $removed = 0
    for ($candidateIndex = 0; $candidateIndex -lt $candidates.Count; $candidateIndex++) {
        $candidate = $candidates[$candidateIndex]
        Write-Progress -Activity 'Cleaning failed Stage 0 evidence' -Status "$($candidateIndex + 1) of $($candidates.Count): $($candidate.Directory.Name)" -PercentComplete ([int](100 * ($candidateIndex + 1) / [Math]::Max(1, $candidates.Count)))
        $path = [IO.Path]::GetFullPath($candidate.Directory.FullName).TrimEnd('\')
        if (!$path.StartsWith($evidencePath + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing path outside evidence: $path" }

        $isReferenced = $false
        foreach ($record in $referenceRecords) {
            if ($record.Source.StartsWith($path + '\', [StringComparison]::OrdinalIgnoreCase)) { continue }
            $reference = $record.Value
            if ([string]::IsNullOrWhiteSpace($reference)) { continue }
            if ($record.Kind -eq 'resume' -and $reference.Equals($candidate.Directory.Name, [StringComparison]::OrdinalIgnoreCase)) {
                $isReferenced = $true
                break
            }
            if ($record.Kind -eq 'gate' -and $reference.Equals($candidate.Directory.Name, [StringComparison]::OrdinalIgnoreCase)) {
                $isReferenced = $true
                break
            }
            if ($record.Kind -eq 'document' -and $reference.IndexOf($candidate.Directory.Name, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $isReferenced = $true
                break
            }
            if ($record.Kind -ne 'path') { continue }
            try { $resolvedReference = [IO.Path]::GetFullPath($reference).TrimEnd('\') } catch { continue }
            if ($resolvedReference.Equals($path, [StringComparison]::OrdinalIgnoreCase) -or
                $resolvedReference.StartsWith($path + '\', [StringComparison]::OrdinalIgnoreCase)) {
                $isReferenced = $true
                break
            }
        }
        if ($isReferenced) { Write-Output "KEEP (referenced): $($candidate.Directory.Name)"; continue }
        if (Test-ContainsReparsePoint $path) { Write-Warning "Keeping '$($candidate.Directory.Name)': contains a reparse point."; continue }

        if ($Apply) {
            # Recheck containment immediately before deletion.
            $confirmPath = [IO.Path]::GetFullPath($path).TrimEnd('\')
            if (!$confirmPath.StartsWith($evidencePath + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing path outside evidence: $confirmPath" }
            Write-Output "Deleting: $($candidate.Directory.Name)"
            Remove-Item -LiteralPath $confirmPath -Recurse -Force -ErrorAction Stop
            $removed++
            $action = 'deleted'
        } else {
            $action = 'would delete'
        }
        $record = [pscustomobject]@{ time = [DateTime]::UtcNow.ToString('o'); path = $path; status = 'failed'; updated = $candidate.Updated.ToString('o'); action = $action }
        if ($Apply) { Add-Content -LiteralPath $logPath -Value ($record | ConvertTo-Json -Compress) -Encoding UTF8 }
        Write-Output "$($action.ToUpperInvariant()): $($candidate.Directory.Name) (failed $($candidate.Updated.ToString('u')))"
    }
    Write-Progress -Activity 'Cleaning failed Stage 0 evidence' -Completed
    if ($Apply) { Write-Output "Cleanup complete. Deleted $removed failed evidence folder(s). Log: $logPath" }
    else { Write-Output "Preview complete. Re-run with -Apply to delete eligible folders. No evidence was deleted." }
} finally {
    foreach ($heldLock in $locks) { $heldLock.Dispose() }
}
