[CmdletBinding()]
param([string]$Root = (Join-Path $PSScriptRoot '..\..\test_harness'))

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
Assert-HarnessRoot $Root
$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\')
if (!(Test-Path -LiteralPath $rootPath -PathType Container)) {
    throw "test harness root does not exist: $rootPath"
}

# This is a manually run cleanup. Stop if an owned server may still be using
# generated files; never infer cleanup permission from a test result.
$installation = Join-Path $rootPath 'runs\test-host\neo4j'
if (Test-Path -LiteralPath $installation -PathType Container) {
    $running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains($rootPath) -and
        $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'
    }
    if ($running) { throw 'Stop the test harness Neo4j server before manually cleaning test evidence.' }
}

function Assert-NoReparsePoint([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Cleanup refuses reparse points: $Path"
    }
}

function Remove-GeneratedTree([string]$Path) {
    Assert-NoReparsePoint $Path
    foreach ($item in Get-ChildItem -LiteralPath $Path -Force -Recurse) {
        Assert-NoReparsePoint $item.FullName
    }
    Remove-Item -LiteralPath $Path -Recurse -Force
}

# These directories contain downloaded tools, dependencies, the installed
# server, compiled packages and the database/resource fixtures used by tests.
# Evidence and historical run records are deliberately absent from this list.
$keepRoot = @('cache','tools','maven-repository','maven-user','build','runs','licenses')
$generatedRootDirectories = @('evidence','inputs','runner','performance','results','logs')
foreach ($item in Get-ChildItem -LiteralPath $rootPath -Force) {
    Assert-NoReparsePoint $item.FullName
    if ($item.Name -in $keepRoot -or $item.Name -eq 'cleanup-barrier.lock') { continue }
    if ($item.PSIsContainer -and $item.Name -in $generatedRootDirectories) {
        Remove-GeneratedTree $item.FullName
    } elseif ($item.PSIsContainer) {
        Write-Warning "Preserving unclassified root directory: $($item.FullName)"
    } elseif ($item.Name -in @('build-receipt.json','gate.lock')) {
        Remove-Item -LiteralPath $item.FullName -Force
    } else {
        Write-Warning "Preserving unclassified root file: $($item.FullName)"
    }
}

$tools = Join-Path $rootPath 'tools'
if (Test-Path -LiteralPath $tools -PathType Container) {
    foreach ($marker in Get-ChildItem -LiteralPath $tools -File -Filter '*.sha256' -Force) {
        Assert-NoReparsePoint $marker.FullName
        Remove-Item -LiteralPath $marker.FullName -Force
    }
}

$build = Join-Path $rootPath 'build'
if (Test-Path -LiteralPath $build -PathType Container) {
    foreach ($generated in Get-ChildItem -LiteralPath $build -Directory -Recurse -Force | Where-Object { $_.Name -in @('surefire-reports','failsafe-reports') } | Sort-Object { $_.FullName.Length } -Descending) {
        if (Test-Path -LiteralPath $generated.FullName) { Remove-GeneratedTree $generated.FullName }
    }
    foreach ($file in Get-ChildItem -LiteralPath $build -File -Recurse -Force | Where-Object { $_.Name -in @('build-metadata.json','release-manifest.json','distribution-manifest.json','runtime-inventory.json','dependency-tree.txt') -or $_.Name -like 'TEST-*.xml' }) {
        Assert-NoReparsePoint $file.FullName
        Remove-Item -LiteralPath $file.FullName -Force
    }
}

$runs = Join-Path $rootPath 'runs'
if (Test-Path -LiteralPath $runs -PathType Container) {
    Assert-NoReparsePoint $runs
    foreach ($run in Get-ChildItem -LiteralPath $runs -Force) {
        Assert-NoReparsePoint $run.FullName
        if (!$run.PSIsContainer -or $run.Name -ne 'test-host') {
            if ($run.PSIsContainer) { Remove-GeneratedTree $run.FullName }
            else { Remove-Item -LiteralPath $run.FullName -Force }
            continue
        }

        $preservedDirectories = @('neo4j','resources','exports','distribution','secrets')
        $preservedFiles = @('owner.json','process.json','phase.lock','fixture-uuid-baseline.tsv','fixture-uuid-baseline.tsv.geometry')
        foreach ($entry in Get-ChildItem -LiteralPath $run.FullName -Force) {
            Assert-NoReparsePoint $entry.FullName
            if ($entry.Name -in $preservedDirectories -or $entry.Name -in $preservedFiles -or $entry.Name -eq 'tmp') { continue }
            if ($entry.PSIsContainer) { Remove-GeneratedTree $entry.FullName }
            else { Remove-Item -LiteralPath $entry.FullName -Force }
        }

        # Keep named fixtures needed by recovery and persistence scenarios;
        # remove reports, copied logs, checkpoints, receipts and transient data.
        $runFiles = Get-ChildItem -LiteralPath $run.FullName -File -Force -Recurse
        foreach ($file in $runFiles) {
            Assert-NoReparsePoint $file.FullName
            $relative = $file.FullName.Substring($run.FullName.Length + 1).Replace('\','/')
            $keep = $relative -in @(
                        'typed-data-fixture.properties','authorization-fixture.properties','batch-fixture.properties',
                        'resource-fixture.properties','history-fixture.properties','resource-crash.properties',
                        'history-crash.properties','query-recovery.properties','storage-recovery.properties',
                        'prepared-action-recovery.properties','pattern-recovery.properties','clone-recovery.properties',
                        'import-export-recovery.properties','fixture-uuid-baseline.tsv','fixture-uuid-baseline.tsv.geometry',
                        'secrets/typed-data-session.txt','secrets/storage-session.txt','secrets/operations-drain-fixture.properties',
                        'secrets/browser-session.properties','neo4j/deposit-secrets/deposit-bootstrap.secret',
                        'exports/storage-incomplete.export') -or
                    $relative -in @('owner.json','process.json') -or
                    ($relative -match '^distribution/' -and $relative -notmatch '^distribution/(?:release-manifest|distribution-manifest|runtime-inventory)\.json$') -or
                    $relative -match '^neo4j/(?:bin|conf|lib|plugins|tools|data|certificates|licenses)/'
            if (!$keep) { Remove-Item -LiteralPath $file.FullName -Force }
        }
        $tmp = Join-Path $run.FullName 'tmp'
        if (Test-Path -LiteralPath $tmp -PathType Container) {
            foreach ($entry in Get-ChildItem -LiteralPath $tmp -Force) {
                if ($entry.PSIsContainer) { Remove-GeneratedTree $entry.FullName }
                else { Remove-Item -LiteralPath $entry.FullName -Force }
            }
        }
        $logs = Join-Path $installation 'logs'
        if (Test-Path -LiteralPath $logs -PathType Container) {
            foreach ($entry in Get-ChildItem -LiteralPath $logs -Force) {
                if ($entry.PSIsContainer) { Remove-GeneratedTree $entry.FullName }
                else { Remove-Item -LiteralPath $entry.FullName -Force }
            }
        }
    }
}

$testLogs = Join-Path $rootPath 'logs'
if (Test-Path -LiteralPath $testLogs -PathType Container) {
    Assert-NoReparsePoint $testLogs
    foreach ($log in Get-ChildItem -LiteralPath $testLogs -File -Filter '*.log' -Force) {
        Assert-NoReparsePoint $log.FullName
        Remove-Item -LiteralPath $log.FullName -Force
    }
}

Write-Output 'Removed generated test evidence and historical run records. Preserved test inputs, installed tools, runtime data, fixtures and built packages.'
