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
    . (Join-Path $PSScriptRoot 'IntegrationRunContext.ps1')
    $staleContextPath=Join-Path $fixture 'stale-run-context.json'
    $currentProcess=Get-Process -Id $PID
    $currentStartTicks=$currentProcess.StartTime.ToUniversalTime().Ticks
    $staleContext=[ordered]@{
        schemaVersion=1;invocationId=[Guid]::NewGuid().ToString('N')
        ownerSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        runnerPid=$PID;runnerStartTicks=$currentStartTicks
        watchdogPid=$PID;watchdogStartTicks=$currentStartTicks
        neo4jHome=(Join-Path (Get-HarnessRoot) 'runs\test-host\neo4j')
        state='running';watchdogAcknowledged=$true
        logs=(Join-Path (Get-HarnessRoot) 'results');children=@()
    }
    Write-SuiteJournal $staleContextPath (ConvertTo-Json $staleContext -Depth 8)
    $priorRunJournal=[Environment]::GetEnvironmentVariable('DEPOSIT_SUITE_JOURNAL','Process')
    [Environment]::SetEnvironmentVariable('DEPOSIT_SUITE_JOURNAL',$staleContextPath,'Process')
    try {
        $staleContextRejected=$false
        try { Get-ValidatedIntegrationRunContext (Get-HarnessRoot) | Out-Null }
        catch { $staleContextRejected=$_.Exception.Message -like '*stale or invalid*' }
    } finally { [Environment]::SetEnvironmentVariable('DEPOSIT_SUITE_JOURNAL',$priorRunJournal,'Process') }
    if(!$staleContextRejected){throw 'Stale or forged noncanonical run journal was accepted as a nested run context'}

    # Delete a discovered leaf immediately before the real attribute probe.
    # A debugger breakpoint provides deterministic fault injection without a
    # production test hook or an unrelated worker-startup timing assumption.
    $secretsScript=Join-Path $PSScriptRoot 'IntegrationHarnessSecrets.ps1'
    $secretsAst=[System.Management.Automation.Language.Parser]::ParseFile($secretsScript,[ref]$null,[ref]$null)
    $assertion=$secretsAst.Find({param($node)$node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-HarnessPlainPath'},$true)
    $probe=$assertion.Find({param($node)$node -is [System.Management.Automation.Language.InvokeMemberExpressionAst] -and $node.Member.Value -eq 'GetAttributes'},$true)
    if(!$probe){throw 'Could not locate the harness path attribute probe'}
    $script:disappearingPath=Join-Path $fixture 'disappearing.json'
    $script:disappearanceInjected=$false
    [IO.File]::WriteAllText($script:disappearingPath,'{}')
    $probeBreakpoint=Set-PSBreakpoint -Script $secretsScript -Line $probe.Extent.StartLineNumber -Action {
        if($current -eq $script:disappearingPath){
            [IO.File]::Delete($script:disappearingPath)
            $script:disappearanceInjected=$true
        }
    }
    try { Assert-HarnessPlainPath $script:disappearingPath }
    finally { Remove-PSBreakpoint $probeBreakpoint }
    if(!$script:disappearanceInjected -or [IO.File]::Exists($script:disappearingPath)){throw 'Disappearance regression did not exercise the attribute probe'}
    Assert-HarnessPlainPath (Join-Path $fixture 'missing-parent\missing.json')

    $outsideRejected=$false
    try { Assert-HarnessPlainPath ((Get-HarnessRoot)+'-outside\missing.json') }
    catch { $outsideRejected=$_.Exception.Message -like 'Path is outside the harness:*' }
    if(!$outsideRejected){throw 'Plain path assertion accepted a lexical harness escape'}

    $junctionTarget=Join-Path $fixture 'junction-target'
    $junctionPath=Join-Path $fixture 'junction'
    New-Item -ItemType Directory -Path $junctionTarget | Out-Null
    New-Item -ItemType Junction -Path $junctionPath -Target $junctionTarget | Out-Null
    try {
        foreach($dangling in @($false,$true)){
            if($dangling){[IO.Directory]::Delete($junctionTarget)}
            $reparseRejected=$false
            try { Assert-HarnessPlainPath (Join-Path $junctionPath 'missing-parent\missing.json') }
            catch { $reparseRejected=$_.Exception.Message -like 'Reparse point refused:*' }
            if(!$reparseRejected){throw "Plain path assertion accepted a missing descendant under a reparse ancestor (dangling=$dangling)"}
        }
    } finally { [IO.Directory]::Delete($junctionPath) }

    # Windows may allow metadata queries despite a ReadAttributes deny ACE.
    # Substitute only the OS probe in a copy of the production assertion to
    # deterministically verify that an access denial escapes its missing-path
    # catches; all path/security decisions remain the production function body.
    if(-not ('DepositDeniedAttributesProbe' -as [type])){
        Add-Type 'public static class DepositDeniedAttributesProbe { public static System.IO.FileAttributes GetAttributes(string path) { throw new System.UnauthorizedAccessException("Injected attribute denial"); } }'
    }
    $deniedAssertion=$assertion.Extent.Text.Replace('function Assert-HarnessPlainPath','function Assert-HarnessDeniedProbe').Replace('[IO.File]::GetAttributes($current)','[DepositDeniedAttributesProbe]::GetAttributes($current)')
    if($deniedAssertion -ceq $assertion.Extent.Text -or $deniedAssertion -notlike '*[[]DepositDeniedAttributesProbe[]]*'){throw 'Could not inject the attribute denial'}
    Invoke-Expression $deniedAssertion
    try {
        $deniedRejected=$false
        try { Assert-HarnessDeniedProbe (Join-Path $fixture 'denied.json') }
        catch [UnauthorizedAccessException] { $deniedRejected=$true }
        if(!$deniedRejected){throw 'Plain path assertion did not propagate an attribute access denial'}
    } finally { Remove-Item Function:\Assert-HarnessDeniedProbe }

    $journalPath=Join-Path $fixture 'active.json'
    Write-SuiteJournal $journalPath '{"count":0}'
    Assert-Journal $journalPath 0
    Update-SuiteJournal $journalPath { param($journal) $journal.count=[int]$journal.count+1 }
    Assert-Journal $journalPath 1
    Update-SuiteJournal $journalPath { param($journal) $journal.count=[int]$journal.count+1 }
    Assert-Journal $journalPath 2

    # Journal replacement is atomic in normal operation, but readers must
    # tolerate a short file-not-found window on filesystems that expose one.
    $gapPath=Join-Path $fixture 'active.during-replacement.json'
    $gapReady=Join-Path $fixture 'gap-ready'
    $script:gapRelease=Join-Path $fixture 'gap-release'
    $script:gapRetryObserved=$false
    $watchdogScript=Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1'
    $watchdogAst=[System.Management.Automation.Language.Parser]::ParseFile($watchdogScript,[ref]$null,[ref]$null)
    $readFunction=$watchdogAst.Find({param($node)$node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Read-SuiteJournal'},$true)
    $retrySleep=$readFunction.Find({param($node)$node -is [System.Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Start-Sleep'},$true)
    $gapBreakpoint=Set-PSBreakpoint -Script $watchdogScript -Line $retrySleep.Extent.StartLineNumber -Action {
        $script:gapRetryObserved=$true
        [IO.File]::WriteAllText($script:gapRelease,'release')
    }
    $gapJob=Start-Job -ArgumentList $journalPath,$gapPath,$gapReady,$script:gapRelease -ScriptBlock {
        param($Path,$GapPath,$Ready,$Release)
        $ErrorActionPreference='Stop'
        [IO.File]::Move($Path,$GapPath)
        try {
            [IO.File]::WriteAllText($Ready,'ready')
            $deadline=[DateTime]::UtcNow.AddSeconds(15)
            while(![IO.File]::Exists($Release)){
                if([DateTime]::UtcNow -ge $deadline){throw 'Reader never observed the journal gap'}
                Start-Sleep -Milliseconds 10
            }
        } finally { [IO.File]::Move($GapPath,$Path) }
    }
    try {
        $readyDeadline=[DateTime]::UtcNow.AddSeconds(15)
        while(![IO.File]::Exists($gapReady)){
            if($gapJob.State -eq 'Failed' -or [DateTime]::UtcNow -ge $readyDeadline){throw 'Journal gap worker did not become ready'}
            Start-Sleep -Milliseconds 20
        }
        if([IO.File]::Exists($journalPath)){throw 'Journal gap was not present at the start of the read'}
        $duringReplacement=Read-SuiteJournal $journalPath
        if(!$script:gapRetryObserved){throw 'Journal gap read never exercised the retry path'}
        if([int]$duringReplacement.count -ne 2){throw 'Journal read during replacement returned incomplete contents'}
        Wait-Job $gapJob -Timeout 10 | Out-Null
        Receive-Job $gapJob -ErrorAction Stop | Out-Null
        if($gapJob.State -ne 'Completed'){throw 'Journal gap worker did not finish'}
    } finally {
        Remove-PSBreakpoint $gapBreakpoint
        [IO.File]::WriteAllText($script:gapRelease,'release')
        Wait-Job $gapJob -Timeout 5 | Out-Null
        if($gapJob.State -ne 'Completed') { Stop-Job $gapJob -ErrorAction SilentlyContinue }
        Receive-Job $gapJob -ErrorAction SilentlyContinue | Out-Null
        Remove-Job $gapJob -Force -ErrorAction SilentlyContinue
        if(!(Test-Path -LiteralPath $journalPath) -and (Test-Path -LiteralPath $gapPath)){[IO.File]::Move($gapPath,$journalPath)}
    }
    Assert-Journal $journalPath 2

    # Exercise the actual writer lock and atomic replacement between separate
    # PowerShell processes, with the watchdog-style ACK observed by the runner.
    $concurrentPath=Join-Path $fixture 'concurrent.json'
    $workerReady=Join-Path $fixture 'writer-ready'
    Write-SuiteJournal $concurrentPath '{"request":0,"ack":0,"count":0}'
    $writerJob=Start-Job -ArgumentList $watchdogScript,$concurrentPath,$workerReady -ScriptBlock {
        param($Watchdog,$Path,$Ready)
        $ErrorActionPreference='Stop'
        . $Watchdog -LibraryOnly
        [IO.File]::WriteAllText($Ready,'ready')
        $deadline=[DateTime]::UtcNow.AddSeconds(40)
        while([DateTime]::UtcNow -lt $deadline){
            Update-SuiteJournal $Path {param($journal) $journal.ack=$journal.request; $journal.count=[int]$journal.count+1}
            if([int](Read-SuiteJournal $Path).ack -eq 25){return}
            Start-Sleep -Milliseconds 5
        }
        throw 'Journal ACK worker timed out'
    }
    try {
        $readyDeadline=[DateTime]::UtcNow.AddSeconds(15)
        while(![IO.File]::Exists($workerReady)){
            if($writerJob.State -eq 'Failed' -or [DateTime]::UtcNow -ge $readyDeadline){throw 'Concurrent writer did not become ready'}
            Start-Sleep -Milliseconds 20
        }
        $previousCount=0
        for($request=1;$request -le 25;$request++){
            Update-SuiteJournal $concurrentPath {param($journal) $journal.request=$request}
            $ackDeadline=[DateTime]::UtcNow.AddSeconds(10)
            do {
                $observed=Read-SuiteJournal $concurrentPath
                if([int]$observed.count -lt $previousCount -or [int]$observed.ack -gt [int]$observed.request){throw 'Concurrent journal read lost state or returned an impossible ACK'}
                $previousCount=[int]$observed.count
                if([int]$observed.ack -eq $request){break}
                if($writerJob.State -eq 'Failed' -or [DateTime]::UtcNow -ge $ackDeadline){throw "Journal ACK $request was not observed"}
                Start-Sleep -Milliseconds 5
            } while($true)
        }
        Wait-Job $writerJob -Timeout 10 | Out-Null
        Receive-Job $writerJob -ErrorAction Stop | Out-Null
        if($writerJob.State -ne 'Completed'){throw 'Concurrent writer did not finish'}
        [DepositSuiteJournalFiles]::Validate($concurrentPath)
    } finally {
        if($writerJob.State -notin @('Completed','Failed')){Stop-Job $writerJob}
        Remove-Job $writerJob -Force -ErrorAction SilentlyContinue
    }

    $missingPath=Join-Path $fixture 'missing.json'
    $missingStarted=[DateTime]::UtcNow
    $missingRejected=$false
    try { Read-SuiteJournal $missingPath | Out-Null }
    catch { $missingRejected=$true }
    $missingElapsed=([DateTime]::UtcNow-$missingStarted).TotalSeconds
    if(!$missingRejected -or $missingElapsed -lt 4 -or $missingElapsed -gt 8){throw "Persistent journal absence was not rejected within the bounded retry window ($missingElapsed seconds)"}

    $unprotectedPath=Join-Path $fixture 'unprotected.json'
    [IO.File]::WriteAllText($unprotectedPath,'{"count":0}',(New-Object Text.UTF8Encoding($false)))
    $unprotectedRejected=$false
    try { Update-SuiteJournal $unprotectedPath { param($journal) $journal.count=[int]$journal.count+1 } }
    catch [IO.IOException] { $unprotectedRejected=$true }
    if(!$unprotectedRejected){throw 'Journal update accepted an unprotected ACL'}
    if([IO.File]::ReadAllText($unprotectedPath) -cne '{"count":0}'){throw 'Rejected journal update changed untrusted contents'}

    $depositHelper=Join-Path (Get-DepositServerRoot) 'runtime\distribution\helpers\LauncherService.ps1'
    $ast=[System.Management.Automation.Language.Parser]::ParseFile($depositHelper,[ref]$null,[ref]$null)
    $setter=$ast.Find({param($node)$node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Register-ActiveSuiteServiceIntent'},$true)
    if(!$setter){throw 'Could not locate the production suite service-intent recorder'}
    Invoke-Expression $setter.Extent.Text
    $phasePath=Join-Path $fixture 'phase.json'
    Write-SuiteJournal $phasePath '{"installationPhase":"none","serviceName":null,"serviceAccount":null,"serviceRegistrations":[]}'
    $installationRoot=Join-Path (Get-HarnessRoot) 'runs\test-host'
    $state=[pscustomobject]@{serviceName='DepositNeo4j-0123abcd';neo4jHome=(Join-Path $installationRoot 'neo4j')}
    Register-ActiveSuiteServiceIntent $phasePath (Join-Path $installationRoot 'distribution') $state 'installing'
    $registered=Read-SuiteJournal $phasePath
    if($registered.installationPhase -ne 'installing' -or $registered.serviceRegistrations.Count -ne 1 -or $registered.serviceRegistrations[0].name -cne $state.serviceName){throw 'Service installation intent was not recorded before registration'}
    [DepositSuiteJournalFiles]::Validate($phasePath)
    Register-ActiveSuiteServiceIntent $phasePath (Join-Path $installationRoot 'distribution') $state 'configured'
    $registered=Read-SuiteJournal $phasePath
    if($registered.installationPhase -ne 'configured' -or $registered.serviceRegistrations.Count -ne 1 -or $registered.serviceRegistrations[0].phase -cne 'installing'){throw 'Repeated service phase update duplicated or lost the original identity record'}
    [DepositSuiteJournalFiles]::Validate($phasePath)

    $before=[IO.File]::ReadAllText($journalPath)
    $collisionRejected=$false
    try { Write-SuiteJournal $journalPath '{"count":99}' }
    catch [IO.IOException] { $collisionRejected=$true }
    if(!$collisionRejected){throw 'Journal creation replaced an existing destination'}
    if([IO.File]::ReadAllText($journalPath) -cne $before){throw 'Journal creation changed an existing destination'}
    Write-Output 'PASS: plain path disappearance and security guards, concurrent journal replacement/ACK, bounded absence, ACL rejection, collisions, and repeated service phase updates'
} finally {
    if([IO.Directory]::Exists($fixture)){[IO.Directory]::Delete($fixture,$true)}
}
