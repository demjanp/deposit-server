Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
. (Join-Path $PSScriptRoot 'IntegrationHarnessSecrets.ps1')
. (Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1') -LibraryOnly

function Get-ValidatedIntegrationRunContext([string]$ExpectedRoot) {
    $journalPath=[Environment]::GetEnvironmentVariable('DEPOSIT_SUITE_JOURNAL','Process')
    if([string]::IsNullOrWhiteSpace($journalPath)){return $null}
    $root=[IO.Path]::GetFullPath($ExpectedRoot).TrimEnd('\')
    Assert-HarnessRoot $root
    $journalPath=[IO.Path]::GetFullPath($journalPath)
    Assert-HarnessPlainPath $journalPath
    [DepositSuiteJournalFiles]::Validate($journalPath)
    $journal=Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json -ErrorAction Stop
    $runner=$null;$watchdog=$null
    try {
        $runner=Get-Process -Id ([int]$journal.runnerPid) -ErrorAction Stop
        $current=Get-Process -Id $PID -ErrorAction Stop
        $watchdog=Get-Process -Id ([int]$journal.watchdogPid) -ErrorAction Stop
        $currentSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $expectedJournal=Join-Path $root 'private\suite\active.json'
        if($journalPath -ine $expectedJournal -or $journal.schemaVersion -ne 1 -or
            [string]$journal.ownerSid -cne $currentSid -or
            [string]$journal.invocationId -cnotmatch '^[a-f0-9]{32}$' -or
            [long]$journal.runnerStartTicks -ne $runner.StartTime.ToUniversalTime().Ticks -or
            [long]$journal.watchdogStartTicks -ne $watchdog.StartTime.ToUniversalTime().Ticks -or
            [IO.Path]::GetFullPath([string]$journal.neo4jHome) -ine [IO.Path]::GetFullPath((Join-Path $root 'runs\test-host\neo4j')) -or
            $journal.state -cnotin @('preparing','running') -or
            $journal.watchdogAcknowledged -isnot [bool] -or !$journal.watchdogAcknowledged -or
            [IO.Path]::GetFullPath([string]$journal.logs).StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase) -eq $false) {
            throw 'Integration run context is stale or invalid'
        }
        if([long]$current.StartTime.ToUniversalTime().Ticks -lt [long]$journal.runnerStartTicks) {throw 'Integration run child predates its owner'}
        $contained=$false
        foreach($child in @($journal.children)) {
            if($child.jobAcknowledged -isnot [bool] -or !$child.jobAcknowledged -or !$child.jobName){continue}
            try {
                if([DepositSuiteJobs]::Contains([string]$child.jobName,$current)) {$contained=$true;break}
            } catch { }
        }
        if(!$contained){throw 'Integration run context is not held by an acknowledged child-containment job'}
        return $journal
    } finally {if($runner){$runner.Dispose()};if($watchdog){$watchdog.Dispose()}}
}

