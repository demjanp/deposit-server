[CmdletBinding()]
param(
    [ValidateSet('Run','Setup')][string]$Phase='Run',
    [switch]$SetupOnly,
    [switch]$ListOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
$root=Get-HarnessRoot
$repo=Get-DepositServerRoot
$port=17770
$run=Join-Path $root 'runs\stage0-host'
$normal=@('Stage1CoreIT','Stage2CoreIT','Stage3CoreIT','Stage4CoreIT','Stage5CoreIT','Stage55CoreIT','Stage6CoreIT','Stage65CoreIT','Stage7CoreIT','Stage8CoreIT','Stage9FormatIT','Stage10CoreIT','Stage11ScientificWorkflowIT',
    'Stage11ImportIT#persistentCloneSharesFreshResourcesAndRestoresHistoryOnlyContent+cloneRetainsResourcesReferencedOnlyByAClosedEpoch+analyzedImportAndPatternFormAttachResourcesInReceivingNamespace+successfulCloneCallerRollbackLeavesNoReceivingGraphOrResourceRecords',
    'Stage11BrowserIT#invalidLoginAndPasswordResetRejectOldBrowserSession+corruptContentReturnsUnavailableAndClientCancellationDoesNotBlockLaterReads','DistributionIT')
$seed='Stage1CoreIT,Stage2CoreIT#namespaceDeletionPreconditionsRollbackAndReplay,Stage3CoreIT#persistBatchForGracefulAndForcedRecovery,Stage4CoreIT#persistRecoveryFixture,Stage5CoreIT#persistRecoveryFixture,Stage55RecoveryIT#seed,Stage6RecoveryIT#seed,Stage65RecoveryIT#seed,Stage7RecoveryIT#seed,Stage8RecoveryIT#seed,Stage9RecoveryIT#seed,Stage10RestartIT#seed'
$verify='Stage1RecoveryIT,Stage2RecoveryIT,Stage3RecoveryIT,Stage4RecoveryIT,Stage5RecoveryIT,Stage55RecoveryIT#verify,Stage6RecoveryIT#verify,Stage65RecoveryIT#verify,Stage7RecoveryIT#verify,Stage8RecoveryIT#verify,Stage9RecoveryIT#verify,Stage10RestartIT#verify'
$resourcePoints=@('collection-before','chunk-flushed','publication-before','publication-after','ready-before-commit','staging-cleanup-before','staging-cleanup-after','collection-after','collection-partial')
# Include unchanged-domain probes as well as the seven host-sensitive regressions.
$probes='RuntimeIT#rejectsInvalidAuthentication,EnvironmentIT#nativeRuntimeAndApoc+independentClientSessions,Stage4ProbeIT,Stage5ProbeIT,Stage55ProbeIT,Stage9CoreIT,Stage11ImportIT#probeRejectsCapabilityMisuseWithoutPersistingGraph+probePublicationFailureRollsBackEntireCloneThenRetrySucceeds,Stage11BrowserIT#probeExpiredBrowserSessionRequiresNewAuthentication'
if ($ListOnly) {
    Write-Output "Harness: $root"
    Write-Output 'Setup: Provision, build (-DskipTests), package, fresh local Install, TLS fixtures'
    Write-Output 'Local lifecycle: Stage1LifecycleIT'
    $normal | ForEach-Object { Write-Output "Normal selection: $_" }
    Write-Output 'Independent Python decoding: generated-whole.zip and generated-subgraph.zip'
    foreach($transition in @('Restart','KillRestart')) { Write-Output "$transition seed: $seed"; Write-Output "$transition verify: $verify" }
    Write-Output 'Service: clean provisioning; fresh-account probes; graceful shutdown; probe selection; three history crash scenarios'
    Write-Output "Service probes: $probes"
    Write-Output ('Resource crash boundaries: '+($resourcePoints -join ', '))
    Write-Output 'Listener: failed initialization, occupied port, missing Spatial/config/TLS, direct TLS, normal verification'
    Write-Output 'Finally: terminate owned children/runtime; stop and uninstall owned service'
    return
}
if ($SetupOnly) { $Phase='Setup' }
if ($Phase -eq 'Run') {
    $principal=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'The full integration suite includes temporary Windows service scenarios. Run PowerShell as Administrator, or use -SetupOnly to provision without tests.' }
}
. (Join-Path $PSScriptRoot 'IntegrationHarnessSecrets.ps1')
. (Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1') -LibraryOnly
$private=Join-Path $root 'private\suite'
Set-OwnerOnlyDirectory $private
$suiteLockPath=Join-Path $private 'suite.lock'
Assert-HarnessPlainPath $suiteLockPath
try { $suiteLock=[IO.File]::Open($suiteLockPath,'OpenOrCreate','ReadWrite','None') } catch { throw 'Another integration suite invocation is active.' }
$invocation=[Guid]::NewGuid().ToString('N')
$journalPath=Join-Path $private 'active.json'
$logs=Join-Path $root ('results\'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'-'+$invocation.Substring(0,8))
$saved=@{}
$watchdog=$null
$failure=$null
$journalCreated=$false
function Quote-Suite([string]$Value) { "'"+$Value.Replace("'","''")+"'" }
function Invoke-SuiteChild([string]$Script,[object[]]$Arguments,[string]$Label,[int]$TimeoutSeconds=1800) {
    $identity=[Guid]::NewGuid().ToString('N')
    $gate=Join-Path $private ($identity+'.gate')
    $pieces=foreach($arg in $Arguments) {
        if ($arg -is [string[]]) { '@('+ (($arg | ForEach-Object { Quote-Suite $_ }) -join ',') +')' }
        elseif ([string]$arg -match '^-[A-Za-z][A-Za-z0-9]*$') { [string]$arg }
        else { Quote-Suite ([string]$arg) }
    }
    $command='$ErrorActionPreference="Stop"; while (!(Test-Path -LiteralPath '+(Quote-Suite $gate)+')) { Start-Sleep -Milliseconds 100 }; & '+(Quote-Suite $Script)+' '+($pieces -join ' ')+'; if ($null -ne $LASTEXITCODE) { exit $LASTEXITCODE }'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $child=Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs ($Label+'.stdout.log')) -RedirectStandardError (Join-Path $logs ($Label+'.stderr.log'))
    try {
        $jobName='Local\DepositIT-'+$invocation+'-'+$identity
        [DepositSuiteJobs]::Create($jobName,$child.Id)
        $entry=[pscustomobject]@{pid=$child.Id;startTicks=$child.StartTime.ToUniversalTime().Ticks;label=$Label;jobName=$jobName;jobAcknowledged=$false}
        Update-SuiteJournal $journalPath { param($j) $j.children=@($j.children)+@($entry) }
        $ackDeadline=[DateTime]::UtcNow.AddSeconds(30)
        do{
            $ack=@((Read-SuiteJournal $journalPath).children|Where-Object jobName -CEQ $jobName)
            if($ack.Count -eq 1 -and $ack[0].jobAcknowledged){break}
            if($watchdog.HasExited -or [DateTime]::UtcNow -gt $ackDeadline){throw 'Watchdog did not hold the child containment job; scenario was not launched'}
            Start-Sleep -Milliseconds 100
        }while($true)
        [IO.File]::WriteAllText($gate,'go')
        Write-Host "Integration harness: $Label"
        if (!$child.WaitForExit($TimeoutSeconds*1000)) { Stop-SuiteChildTree $entry -PreserveRuntime; throw "$Label timed out after $TimeoutSeconds seconds" }
        $child.Refresh()
        if ($child.ExitCode -ne 0) { throw "$Label failed ($($child.ExitCode)); see $logs" }
    } finally {
        # A failed assignment/ACK must never release the gated child. There is
        # no PID-tree fallback for containment failure.
        if(!$child.HasExited -and !(Test-Path -LiteralPath $gate)){$child.Kill();$null=$child.WaitForExit(5000)}
        if (Test-Path -LiteralPath $gate) { Remove-Item -LiteralPath $gate -Force }
        $child.Dispose()
    }
}
function Invoke-SuiteStage([string]$Name,[object[]]$Extra=@()) { Invoke-SuiteChild (Join-Path $PSScriptRoot 'Stage0.ps1') (@('-Phase',$Name,'-Root',$root,'-Port',[string]$port)+$Extra) ('stage0-'+$Name+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6)) 3600 }
function Invoke-SuiteSelection([string]$Selection,[string]$Label) { Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-Tests.ps1') @('-Kind','Integration','-Test',$Selection,'-Root',$root,'-Port',[string]$port,'-LogPath',(Join-Path $logs ($Label+'.log'))) $Label 1800 }
function Assert-OwnedTree([string]$Path) {
    Assert-HarnessPlainPath $Path
    if ((Test-Path -LiteralPath $Path) -and @(Get-ChildItem -LiteralPath $Path -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw "Reparse point within generated tree: $Path" }
}
function Preserve-GeneratedTree([string]$Path,[string]$Label) {
    if (!(Test-Path -LiteralPath $Path)) { return }
    Assert-OwnedTree $Path
    $destination=Join-Path $root ('preserved\'+$invocation+'\'+$Label)
    Assert-HarnessPlainPath $destination
    New-Item -ItemType Directory -Force (Split-Path $destination -Parent) | Out-Null
    Move-Item -LiteralPath $Path -Destination $destination
}
function Invoke-SuiteNative([string]$File,[string[]]$Arguments,[string]$Label) {
    # Run through a tracked PowerShell child; encoded arguments preserve spaces.
    Invoke-SuiteChild $File $Arguments $Label 900
}
try {
    if (Test-Path -LiteralPath $journalPath) {
        $prior=Read-SuiteJournal $journalPath
        if (Get-SuiteProcess ([long]$prior.runnerPid) ([long]$prior.runnerStartTicks)) { throw 'A previous suite owner is still running' }
        if ($prior.state -ne 'cleaned') {
            Write-Warning "Previous invocation cleanup state: $($prior.state). Retrying owned cleanup."
            Invoke-SuiteCleanup $journalPath ([string]$prior.invocationId)
        }
        Move-Item -LiteralPath $journalPath -Destination (Join-Path $private ('journal-'+$prior.invocationId+'.json'))
    }
    New-Item -ItemType Directory -Force $logs,(Join-Path $root 'tmp'),(Join-Path $root 'cache\selenium') | Out-Null
    Set-OwnerOnlyDirectory $logs
    foreach($key in @('TEMP','TMP','STAGE0_ROOT','DEPOSIT_SUITE_JOURNAL','SE_CACHE_PATH','SE_AVOID_STATS','SE_BROWSER_PATH','STAGE7_PACKAGE_OUTPUT','PYTHONPYCACHEPREFIX','PYTHONUSERBASE','MAVEN_USER_HOME','MAVEN_OPTS')) { $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process') }
    $env:TEMP=Join-Path $root 'tmp';$env:TMP=$env:TEMP
    $env:STAGE0_ROOT=$root;$env:DEPOSIT_SUITE_JOURNAL=$journalPath
    $env:SE_CACHE_PATH=Join-Path $root 'cache\selenium';$env:SE_AVOID_STATS='true'
    $env:PYTHONPYCACHEPREFIX=Join-Path $root 'cache\python';$env:PYTHONUSERBASE=Join-Path $root 'tools\python-user'
    $env:MAVEN_USER_HOME=Join-Path $root 'maven-user'
    $env:MAVEN_OPTS=($env:MAVEN_OPTS+' -Djava.io.tmpdir="'+$env:TEMP+'"').Trim()
    $journal=[ordered]@{schemaVersion=1;invocationId=$invocation;ownerSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;runnerPid=$PID;runnerStartTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks;neo4jHome=(Join-Path $run 'neo4j');runtimeOwned=$false;serviceName=$null;serviceAccount=$null;installationPhase='none';allowedInitialServiceAccount='LocalSystem';children=@();state='preparing';terminalCleanup=$false;watchdogAcknowledged=$false;watchdogPid=$null;watchdogStartTicks=$null;cleanupError=$null;cleanupDiagnostics=@();cleanedUtc=$null;logs=$logs}
    [IO.File]::WriteAllText($journalPath,($journal | ConvertTo-Json -Depth 10),(New-Object Text.UTF8Encoding($false)))
    $journalCreated=$true
    $watchdogCommand='& '+(Quote-Suite (Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1'))+' -JournalPath '+(Quote-Suite $journalPath)
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($watchdogCommand))
    $watchdog=Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs 'watchdog.stdout.log') -RedirectStandardError (Join-Path $logs 'watchdog.stderr.log')
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    while (!(Read-SuiteJournal $journalPath).watchdogAcknowledged) { if ($watchdog.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Cleanup watchdog did not acknowledge; no service will be installed' }; Start-Sleep -Milliseconds 100 }
    # Previous runtime is retained outside the repo. Reject active registrations
    # instead of modifying any service that is not owned by this invocation.
    $statePath=Join-Path $run 'distribution\deposit.install.json'
    if (Test-Path -LiteralPath $statePath) {
        $old=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        if (Get-Service -Name $old.serviceName -ErrorAction SilentlyContinue) { throw 'An unjournaled harness service is registered. Remove it from its installation before rebuilding.' }
    }
    $active=@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains((Join-Path $run 'neo4j')) })
    if ($active.Count) { throw 'The previous harness runtime is still in use; stop it before rebuilding' }
    Preserve-GeneratedTree $run 'stage0-host'
    Update-SuiteJournal $journalPath {param($j) $j.runtimeOwned=$true}
    Preserve-GeneratedTree (Join-Path $root 'build\distribution') 'distribution'
    Invoke-SuiteStage 'Provision'
    Invoke-SuiteStage 'Build'
    Invoke-SuiteStage 'Package'
    # Package also creates a verified distribution copy. Install extracts the
    # archive with CreateNew, so retain that copy away from its extraction target.
    Preserve-GeneratedTree (Join-Path $run 'distribution') 'packaged-copy'
    $archive=Join-Path $root 'build\deposit-server-0.0.0-stage65.zip'
    Invoke-SuiteStage 'Install' @('-ReleasePackage',$archive)
    $toolManifest=Get-Content -LiteralPath (Join-Path $repo 'runtime\integration-tools.json') -Raw | ConvertFrom-Json
    $python=Join-Path $root $toolManifest.pythonExe
    $firefox=Join-Path $root $toolManifest.firefoxExe
    if (!(Test-Path -LiteralPath $python -PathType Leaf) -or !(Test-Path -LiteralPath $firefox -PathType Leaf)) { throw 'Provision did not prepare the pinned Python/Firefox tools' }
    $env:SE_BROWSER_PATH=$firefox
    $tls=Join-Path $root 'private\tls'
    Set-OwnerOnlyDirectory $tls
    $tlsPasswordPath=Join-Path $tls 'stage11-download-test.password'
    $tlsStore=Join-Path $tls 'stage11-download-test.p12'
    if (!(Test-Path -LiteralPath $tlsStore)) {
        $bytes=New-Object byte[] 32;$random=[Security.Cryptography.RandomNumberGenerator]::Create();try{$random.GetBytes($bytes)}finally{$random.Dispose()}
        [IO.File]::WriteAllText($tlsPasswordPath,[Convert]::ToBase64String($bytes),(New-Object Text.UTF8Encoding($false)))
        $manifest=Get-Content -LiteralPath (Join-Path $repo 'runtime\artifacts.json') -Raw | ConvertFrom-Json
        $keytool=Join-Path (Join-Path $root $manifest.javaHome) 'bin\keytool.exe'
        Invoke-SuiteNative $keytool @('-genkeypair','-alias','stage11','-keyalg','RSA','-keysize','2048','-validity','3650','-storetype','PKCS12','-keystore',$tlsStore,'-storepass:file',$tlsPasswordPath,'-dname','CN=localhost','-ext','SAN=dns:localhost,ip:127.0.0.1','-noprompt') 'tls-fixture'
    }
    if ($Phase -eq 'Run') {
        Update-SuiteJournal $journalPath { param($j) $j.state='running' }
        Invoke-SuiteStage 'Start'
        Invoke-SuiteSelection 'Stage1LifecycleIT' 'local-lifecycle'
        $env:STAGE7_PACKAGE_OUTPUT=Join-Path $logs 'generated-whole.zip'
        $index=0
        foreach($selection in $normal) { $index++; Invoke-SuiteSelection $selection ('normal-'+$index+'-'+($selection.Split('#')[0])) }
        foreach($package in @('generated-whole.zip','generated-subgraph.zip')) { Invoke-SuiteNative $python @((Join-Path $PSScriptRoot 'Test-Stage7Package.py'),(Join-Path $logs $package)) ('decode-'+$package) }
        Remove-Item Env:\STAGE7_PACKAGE_OUTPUT -ErrorAction SilentlyContinue
        foreach($transition in @('Restart','KillRestart')) { Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-TestScenario.ps1') @('-SeedTest',$seed,'-VerifyTest',$verify,'-Transition',$transition,'-Root',$root,'-Port',[string]$port,'-LogPath',(Join-Path $logs ('recovery-'+$transition+'.log')),'-TestTimeoutSeconds','900') ('recovery-'+$transition) 2400 }
        Invoke-SuiteStage 'Stop'
        $state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $name=[string]$state.serviceName;$account='NT SERVICE\'+$name
        if ($name -notmatch '^DepositNeo4j-[a-f0-9]{8}$') { throw 'Unexpected generated test service name' }
        Update-SuiteJournal $journalPath { param($j) $j.serviceName=$name; $j.serviceAccount=$account; $j.installationPhase='installing' }
        $launcher=Join-Path $run 'distribution\deposit.bat'
        Invoke-SuiteNative $launcher @('install-service') 'install-temporary-service'
        Update-SuiteJournal $journalPath {param($j) $j.installationPhase='configured'}
        Set-Service -Name $name -StartupType Manual
        & "$env:SystemRoot\System32\sc.exe" failure $name reset= 0 actions= '""' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not disable test-service recovery' }
        Invoke-SuiteStage 'Start'
        Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-Stage11CleanProvisionScenario.ps1') @('-ReleasePackage',$archive,'-Port',[string]$port,'-LogPath',(Join-Path $logs 'clean-provision.log')) 'clean-provision' 3600
        Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-Stage11CleanProvisionScenario.ps1') @('-ReleasePackage',$archive,'-Port',[string]$port,'-LogPath',(Join-Path $logs 'fresh-account.log'),'-FreshAccountProbe') 'fresh-account' 3600
        Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-Stage11ServiceShutdownScenario.ps1') @('-Root',$root,'-Port',[string]$port,'-LogPath',(Join-Path $logs 'service-shutdown.log')) 'service-shutdown' 1800
        $crashArguments=@('-Root',$root,'-Port',[string]$port,'-LogPath',(Join-Path $logs 'service-crash.log'),'-ProbeTest',$probes,'-HistoryRecoveryChecks','-CrashPoint',$resourcePoints[0],'-AdditionalCrashPoints')
        $crashArguments+=,([string[]]$resourcePoints[1..8])
        Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-Stage11ServiceCrashScenario.ps1') $crashArguments 'service-crash' 10800
        $listenerLogs=Join-Path $logs 'listener';New-Item -ItemType Directory -Path $listenerLogs | Out-Null
        Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-Stage11ListenerScenario.ps1') @('-LogDirectory',$listenerLogs,'-TlsMaterialDirectory',$tls) 'listener' 2400
    }
} catch { $failure=$_.Exception.Message } finally {
    if ($journalCreated) { try { Invoke-SuiteCleanup $journalPath $invocation } catch { $failure=($failure+' Cleanup failed: '+$_.Exception.Message).Trim() } }
    if ($watchdog) { if (!$watchdog.WaitForExit(10000)) { Write-Warning 'Watchdog remains active until the runner exits; retained journal records cleanup status.' }; $watchdog.Dispose() }
    foreach($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
    $suiteLock.Dispose()
    [DepositSuiteJobs]::Release()
}
if ($failure) { Write-Error "$failure Logs: $logs"; exit 1 }
Write-Output "PASS: integration harness $Phase. Logs: $logs"
