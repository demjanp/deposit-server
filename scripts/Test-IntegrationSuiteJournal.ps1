[CmdletBinding()]
param()

$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
. (Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1') -LibraryOnly

$fixture=Join-Path (Join-Path (Get-HarnessRoot) 'private\suite') ('journal-acl-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
Set-OwnerOnlyDirectory $fixture
function Assert-Journal([string]$Path,[int]$ExpectedCount) {
    [DepositSuiteJournalFiles]::Validate($Path)
    $journal=Read-SuiteJournal $Path
    if([int]$journal.count -ne $ExpectedCount){throw "Expected journal count $ExpectedCount, found $($journal.count)"}
}

try {
    $journalPath=Join-Path $fixture 'active.json'
    Write-SuiteJournal $journalPath '{"count":0}'
    Assert-Journal $journalPath 0
    Update-SuiteJournal $journalPath { param($journal) $journal.count=[int]$journal.count+1 }
    Assert-Journal $journalPath 1
    Update-SuiteJournal $journalPath { param($journal) $journal.count=[int]$journal.count+1 }
    Assert-Journal $journalPath 2

    $legacyPath=Join-Path $fixture 'legacy.json'
    [IO.File]::WriteAllText($legacyPath,'{"count":0}',(New-Object Text.UTF8Encoding($false)))
    Update-SuiteJournal $legacyPath { param($journal) $journal.count=[int]$journal.count+1 }
    Assert-Journal $legacyPath 1

    $before=[IO.File]::ReadAllText($journalPath)
    $collisionRejected=$false
    try { Write-SuiteJournal $journalPath '{"count":99}' }
    catch [IO.IOException] { $collisionRejected=$true }
    if(!$collisionRejected){throw 'Journal creation replaced an existing destination'}
    if([IO.File]::ReadAllText($journalPath) -cne $before){throw 'Journal creation changed an existing destination'}
    Write-Output 'PASS: suite journal creation, replacement, legacy repair, and collision checks'
} finally {
    if([IO.Directory]::Exists($fixture)){[IO.Directory]::Delete($fixture,$true)}
}
