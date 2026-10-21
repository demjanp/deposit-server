[CmdletBinding()]
param(
    [ValidateSet('Run','Setup')][string]$Phase='Run',
    [switch]$SetupOnly,
    [switch]$ListOnly,
    [ValidateSet('Unit','Integration')][string]$SelectedKind,
    [ValidatePattern('^[A-Za-z0-9_#+,*-]+$')][string]$SelectedTest,
    [string]$SelectedLogPath,
    [ValidatePattern('^[A-Za-z0-9_#+,*-]+$')][string]$RecoverySeedTest,
    [ValidatePattern('^[A-Za-z0-9_#+,*-]+$')][string]$RecoveryVerifyTest,
    [ValidateSet('Restart','KillRestart','ProcessCrashRestart','StartupCrashRestart')][string]$RecoveryTransition,
    [ValidateSet('resource','history')][string]$RecoveryCrashSuite,
    [string]$RecoveryCrashPoint,
    [ValidateRange(30,3600)][int]$RecoveryTestTimeoutSeconds=900,
    [string]$RecoveryLogPath,
    [ValidateSet('CleanProvisioning','DownloadListener','LocalShutdownDrain','ServiceShutdown','ServiceCrashRecovery')][string]$SelectedScenario,
    [string]$ScenarioLogPath,
    [switch]$FreshAccountProbe,
    [ValidatePattern('^[A-Za-z0-9_#+,*-]*$')][string]$ScenarioProbeTest='DataImportExportIT',
    [ValidateSet('chunk-flushed','publication-before','publication-after','ready-before-commit','staging-cleanup-before','staging-cleanup-after','collection-before','collection-after','collection-partial')][string]$ScenarioCrashPoint='collection-before',
    [switch]$ScenarioHistoryRecoveryChecks,
    [ValidateSet('chunk-flushed','publication-before','publication-after','ready-before-commit','staging-cleanup-before','staging-cleanup-after','collection-before','collection-after','collection-partial')][string[]]$ScenarioAdditionalCrashPoints=@(),
    [ValidateRange(1024,65535)][int]$Port=17770,
    [string]$Root=(Join-Path $PSScriptRoot '..\..\test_harness')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
$root=[IO.Path]::GetFullPath($Root).TrimEnd('\')
Assert-HarnessRoot $root
$repo=Get-DepositServerRoot
$port=$Port
$run=Join-Path $root 'runs\test-host'
$normal=@('TypedDataModelIT','AccountAuthorizationIT','BatchMutationIT','ResourceAndGeometryIT','RevisionHistoryIT','ImmutableResourceStorageIT','QueryLanguageIT','PreparedActionReplayIT','PatternMatchingIT','NamespaceCloneAnalysisIT','TabularAndSpatialFormatsIT','BrowserSessionIT','ScientificWorkflowIT',
    'CloneAndImportProbeIT#persistentCloneSharesFreshResourcesAndRestoresHistoryOnlyContent+cloneRetainsResourcesReferencedOnlyByAClosedEpoch+analyzedImportAndPatternFormAttachResourcesInReceivingNamespace+successfulCloneCallerRollbackLeavesNoReceivingGraphOrResourceRecords',
    'BrowserFailureIT#invalidLoginAndPasswordResetRejectOldBrowserSession+corruptContentReturnsUnavailableAndClientCancellationDoesNotBlockLaterReads','DistributionIT')
$seed='TypedDataModelIT,AccountAuthorizationIT#namespaceDeletionPreconditionsRollbackAndReplay,BatchMutationIT#persistBatchForGracefulAndForcedRecovery,ResourceAndGeometryIT#persistRecoveryFixture,RevisionHistoryIT#persistRecoveryFixture,StorageRecoveryIT#seed,QueryRecoveryIT#seed,ActionReplayRecoveryIT#seed,PatternRecoveryIT#seed,CloneRecoveryIT#seed,ImportExportRecoveryIT#seed,BrowserSessionRecoveryIT#seed'
$verify='TypedDataRecoveryIT,AuthorizationRecoveryIT,BatchRecoveryIT,ResourceRecoveryIT,HistoryRecoveryIT,StorageRecoveryIT#verify,QueryRecoveryIT#verify,ActionReplayRecoveryIT#verify,PatternRecoveryIT#verify,CloneRecoveryIT#verify,ImportExportRecoveryIT#verify,BrowserSessionRecoveryIT#verify'
$resourcePoints=@('collection-before','chunk-flushed','publication-before','publication-after','ready-before-commit','staging-cleanup-before','staging-cleanup-after','collection-after','collection-partial')
# Include unchanged-domain probes as well as the seven host-sensitive regressions.
$probes='RuntimeIT#rejectsInvalidAuthentication,EnvironmentIT#nativeRuntimeAndApoc+independentClientSessions,ResourceFailureProbeIT,HistoryFailureProbeIT,StorageCapacityProbeIT,DataImportExportIT,CloneAndImportProbeIT#probeRejectsCapabilityMisuseWithoutPersistingGraph+probePublicationFailureRollsBackEntireCloneThenRetrySucceeds,BrowserFailureIT#probeExpiredBrowserSessionRequiresNewAuthentication'
if ($ListOnly) {
    Write-Output "Harness: $root"
    Write-Output 'Setup: Provision, build (-DskipTests), package, fresh local Install, TLS fixtures'
    Write-Output 'Local lifecycle: AuthenticationNamespaceLifecycleIT'
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
if([bool]$SelectedKind -ne [bool]$SelectedTest){throw 'SelectedKind and SelectedTest must be supplied together.'}
if([bool]$RecoverySeedTest -ne [bool]$RecoveryVerifyTest -or [bool]$RecoverySeedTest -ne [bool]$RecoveryTransition){throw 'Recovery seed, verification, and transition must be supplied together.'}
$selectionCount=0
if($SelectedKind){$selectionCount++}
if($RecoverySeedTest){$selectionCount++}
if($SelectedScenario){$selectionCount++}
if($selectionCount -gt 1){throw 'Select one independent test or scenario per run.'}
if($SelectedLogPath){$SelectedLogPath=[IO.Path]::GetFullPath($SelectedLogPath);Assert-HarnessPlainPath $SelectedLogPath}
if($RecoveryLogPath){$RecoveryLogPath=[IO.Path]::GetFullPath($RecoveryLogPath);Assert-HarnessPlainPath $RecoveryLogPath}
if($ScenarioLogPath){$ScenarioLogPath=[IO.Path]::GetFullPath($ScenarioLogPath);Assert-HarnessPlainPath $ScenarioLogPath}
if ($Phase -eq 'Run' -and !$SelectedKind -and !$RecoverySeedTest -and !$SelectedScenario) {
    $principal=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'The full integration suite includes temporary Windows service scenarios. Run PowerShell as Administrator, or use -SetupOnly to provision without tests.' }
}
if($SelectedScenario -in @('CleanProvisioning','DownloadListener','ServiceShutdown','ServiceCrashRecovery')) {
    $principal=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if(!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'This standalone scenario installs a temporary Windows service. Run PowerShell as Administrator; no harness state was reset.'}
}
. (Join-Path $PSScriptRoot 'IntegrationHarnessSecrets.ps1')
. (Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1') -LibraryOnly
. (Join-Path $PSScriptRoot 'helpers\IntegrationSuiteChild.ps1')
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

function Invoke-SuiteChild([string]$Script,[object[]]$Arguments,[string]$Label,[int]$TimeoutSeconds=1800) {
    Invoke-ContainedSuiteChild $Script $Arguments $Label $TimeoutSeconds $journalPath $private $logs $invocation $watchdog
}
function Invoke-SuitePhase([string]$Name,[object[]]$Extra=@()) { Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-TestHarnessPhase.ps1') (@('-Phase',$Name,'-Root',$root,'-Port',[string]$port)+$Extra) ('harness-phase-'+$Name+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6)) 3600 }
function Invoke-SuiteSelection([string]$Selection,[string]$Label,[string]$Kind='Integration') { Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-Tests.ps1') @('-Kind',$Kind,'-Test',$Selection,'-Root',$root,'-Port',[string]$port,'-LogPath',(Join-Path $logs ($Label+'.log'))) $Label 1800 }
function Assert-OwnedTree([string]$Path) {
    Assert-HarnessPlainPath $Path
    if ((Test-Path -LiteralPath $Path) -and @(Get-ChildItem -LiteralPath $Path -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw "Reparse point within generated tree: $Path" }
}
function Remove-OwnedTree([string]$Path) {
    Assert-OwnedTree $Path
    Remove-Item -LiteralPath $Path -Recurse -Force
}
function Reset-HarnessMutableState {
    $keep=@('cache','tools','maven-repository','maven-user','licenses','private')
    $mutable=@('build','diagnostic-build','logs','preserved','results','runs','tmp','evidence','inputs','runner','performance','quarantine')
    $generatedFiles=@('build-receipt.json','gate.lock','cleanup-barrier.lock')
    $rootEntries=@(Get-ChildItem -LiteralPath $root -Force)
    foreach($entry in $rootEntries) {
        Assert-HarnessPlainPath $entry.FullName
        if($entry.Name -in $keep) { continue }
        if($entry.Name -in $mutable) {Assert-OwnedTree $entry.FullName;continue}
        if(!$entry.PSIsContainer -and $entry.Name -in $generatedFiles) {continue}
        throw "Harness reset refuses an unclassified root item: $($entry.FullName)"
    }
    $privateRoot=Join-Path $root 'private'
    $privateEntries=if(Test-Path -LiteralPath $privateRoot){@(Get-ChildItem -LiteralPath $privateRoot -Force)}else{@()}
    foreach($entry in $privateEntries) {
        Assert-HarnessPlainPath $entry.FullName
        if($entry.Name -eq 'suite') { continue }
        Assert-OwnedTree $entry.FullName
    }
    $suiteEntries=if(Test-Path -LiteralPath $private){@(Get-ChildItem -LiteralPath $private -Force)}else{@()}
    foreach($entry in $suiteEntries) {
        Assert-HarnessPlainPath $entry.FullName
        if($entry.Name -in @('suite.lock','active.json','active.json.write.lock')) {continue}
        Assert-OwnedTree $entry.FullName
    }
    foreach($entry in $rootEntries) {
        if($entry.Name -in $mutable) {Remove-OwnedTree $entry.FullName}
        elseif(!$entry.PSIsContainer -and $entry.Name -in $generatedFiles) {Remove-Item -LiteralPath $entry.FullName -Force}
    }
    New-Item -ItemType Directory -Force $privateRoot | Out-Null
    foreach($entry in $privateEntries) {if($entry.Name -ne 'suite'){Remove-OwnedTree $entry.FullName}}
    New-Item -ItemType Directory -Force $private | Out-Null
    foreach($entry in $suiteEntries) {
        if($entry.Name -in @('suite.lock','active.json','active.json.write.lock')) {continue}
        Remove-OwnedTree $entry.FullName
    }
    foreach($path in @((Join-Path $root 'cache\selenium'),(Join-Path $root 'cache\python'),(Join-Path $root 'tmp'),(Join-Path $root 'results'))) {
        New-Item -ItemType Directory -Force $path | Out-Null
    }
    $pythonCache=Join-Path $root 'cache\python'
    if(Test-Path -LiteralPath $pythonCache){Remove-OwnedTree $pythonCache}
    New-Item -ItemType Directory -Force $pythonCache | Out-Null
    Set-OwnerOnlyDirectory $private
    foreach($oldJournal in @(($journalPath+'.write.lock'),$journalPath)) {
        if(Test-Path -LiteralPath $oldJournal) {Assert-HarnessPlainPath $oldJournal;Remove-Item -LiteralPath $oldJournal -Force}
    }
}
function Invoke-SuiteNative([string]$File,[string[]]$Arguments,[string]$Label) {
    # Run through a tracked PowerShell child; encoded arguments preserve spaces.
    Invoke-SuiteChild $File $Arguments $Label 900
}
function Enable-TestService {
    Invoke-SuitePhase 'Stop'
    Invoke-SuiteLocalRetirement $journalPath $invocation
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
    Invoke-SuitePhase 'Start'
}
try {
    if (Test-Path -LiteralPath $journalPath) {
        [DepositSuiteJournalFiles]::Validate($journalPath)
        $prior=Read-SuiteJournal $journalPath
        $currentSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        if ($prior.schemaVersion -ne 1 -or $prior.ownerSid -cne $currentSid -or [string]$prior.invocationId -cnotmatch '^[a-f0-9]{32}$') { throw 'Previous suite journal identity or schema is invalid' }
        if ($prior.state -ceq 'cleaned') {
            $completedAt=[DateTimeOffset]::MinValue
            $validTimestamp=[DateTimeOffset]::TryParse([string]$prior.cleanedUtc,[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::RoundtripKind,[ref]$completedAt)
            if ($prior.terminalCleanup -isnot [bool] -or !$prior.terminalCleanup -or $null -ne $prior.cleanupError -or !$validTimestamp) { throw 'Previous suite journal has an inconsistent completed state' }
        } elseif ($prior.state -cin @('preparing','running','cleaning','cleanup-failed')) {
            if (Get-SuiteProcess ([long]$prior.runnerPid) ([long]$prior.runnerStartTicks)) { throw 'A previous suite owner is still running' }
            Write-Warning "Previous invocation cleanup state: $($prior.state). Retrying owned cleanup."
            Invoke-SuiteCleanup $journalPath ([string]$prior.invocationId)
        } else {
            throw "Previous suite journal has an unknown state: $($prior.state)"
        }
        if([long]$prior.watchdogPid -gt 0 -and [long]$prior.watchdogStartTicks -gt 0) {
            $watchdogDeadline=[DateTime]::UtcNow.AddSeconds(15)
            do {
                $oldWatchdog=Get-SuiteProcess ([long]$prior.watchdogPid) ([long]$prior.watchdogStartTicks)
                if(!$oldWatchdog){break}
                $oldWatchdog.Dispose()
                Start-Sleep -Milliseconds 100
            } while([DateTime]::UtcNow -lt $watchdogDeadline)
            if($oldWatchdog){throw 'Previous run watchdog is still active; cleanup retains its journal and files.'}
        }
        # The prior cleanup is complete, so its authority can be discarded
        # with the mutable state. If cleanup failed, this point is never reached.
    }
    $previousState=Join-Path $run 'distribution\deposit.install.json'
    if(Test-Path -LiteralPath $previousState) {
        Assert-HarnessPlainPath $previousState
        $old=Get-Content -LiteralPath $previousState -Raw | ConvertFrom-Json
        if($old.serviceName -and (Get-Service -Name ([string]$old.serviceName) -ErrorAction SilentlyContinue)) {
            throw 'An unowned harness service is registered; cleanup refuses to delete its installation.'
        }
    }
    $active=@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains((Join-Path $run 'neo4j')) })
    if($active.Count) { throw 'A harness runtime process is still active; cleanup refuses to delete its files.' }
    $harnessServices=@(Get-CimInstance Win32_Service | Where-Object { $_.PathName -and ([string]$_.PathName).IndexOf($root,[StringComparison]::OrdinalIgnoreCase) -ge 0 })
    if($harnessServices.Count) { throw 'A service still points into the harness; cleanup refuses to delete its installation.' }
    Reset-HarnessMutableState
    New-Item -ItemType Directory -Force $logs,(Join-Path $root 'tmp'),(Join-Path $root 'cache\selenium') | Out-Null
    Set-OwnerOnlyDirectory $logs
    foreach($key in @('TEMP','TMP','DEPOSIT_TEST_ROOT','DEPOSIT_SUITE_JOURNAL','SE_CACHE_PATH','SE_AVOID_STATS','SE_BROWSER_PATH','SE_GECKODRIVER','DEPOSIT_TEST_PACKAGE_OUTPUT','PYTHONPYCACHEPREFIX','PYTHONUSERBASE','MAVEN_USER_HOME','MAVEN_OPTS')) { $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process') }
    $env:TEMP=Join-Path $root 'tmp';$env:TMP=$env:TEMP
    $env:DEPOSIT_TEST_ROOT=$root;$env:DEPOSIT_SUITE_JOURNAL=$journalPath
    $env:SE_CACHE_PATH=Join-Path $root 'cache\selenium';$env:SE_AVOID_STATS='true'
    $env:PYTHONPYCACHEPREFIX=Join-Path $root 'cache\python';$env:PYTHONUSERBASE=Join-Path $root 'tools\python-user'
    $env:MAVEN_USER_HOME=Join-Path $root 'maven-user'
    $env:MAVEN_OPTS=($env:MAVEN_OPTS+' -Djava.io.tmpdir="'+$env:TEMP+'"').Trim()
    $journal=[ordered]@{schemaVersion=1;invocationId=$invocation;ownerSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;runnerPid=$PID;runnerStartTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks;neo4jHome=(Join-Path $run 'neo4j');runtimeOwned=$false;serviceName=$null;serviceAccount=$null;serviceRegistrations=@();installationPhase='none';allowedInitialServiceAccount='LocalSystem';children=@();runtimeJobs=@();state='preparing';terminalCleanup=$false;watchdogAcknowledged=$false;watchdogPid=$null;watchdogStartTicks=$null;cleanupError=$null;cleanupDiagnostics=@();cleanedUtc=$null;logs=$logs}
    Write-SuiteJournal $journalPath ($journal | ConvertTo-Json -Depth 10)
    $journalCreated=$true
    $watchdogCommand='& '+(Quote-Suite (Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1'))+' -JournalPath '+(Quote-Suite $journalPath)
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($watchdogCommand))
    $watchdog=Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs 'watchdog.stdout.log') -RedirectStandardError (Join-Path $logs 'watchdog.stderr.log')
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    while (!(Read-SuiteJournal $journalPath).watchdogAcknowledged) { if ($watchdog.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Cleanup watchdog did not acknowledge; no service will be installed' }; Start-Sleep -Milliseconds 100 }
    $statePath=Join-Path $run 'distribution\deposit.install.json'
    Invoke-SuitePhase 'Provision'
    if($SelectedKind -ne 'Unit') {
        Invoke-SuitePhase 'Build'
        $archive=Join-Path $root 'build\deposit-server-0.0.0-dev.zip'
        Invoke-SuitePhase 'Package'
        Update-SuiteJournal $journalPath {param($j) $j.runtimeOwned=$true}
        Invoke-SuitePhase 'Install' @('-ReleasePackage',$archive)
    }
    $toolManifest=Get-Content -LiteralPath (Join-Path $repo 'runtime\integration-tools.json') -Raw | ConvertFrom-Json
    $python=Join-Path $root $toolManifest.pythonExe
    $firefox=Join-Path $root $toolManifest.firefoxExe
    $geckodriver=Join-Path $root $toolManifest.geckodriverExe
    if (!(Test-Path -LiteralPath $python -PathType Leaf) -or !(Test-Path -LiteralPath $firefox -PathType Leaf) -or !(Test-Path -LiteralPath $geckodriver -PathType Leaf)) { throw 'Provision did not prepare the pinned Python/Firefox/geckodriver tools' }
    $env:SE_BROWSER_PATH=$firefox
    $env:SE_GECKODRIVER=$geckodriver
    $tls=Join-Path $root 'private\tls'
    Set-OwnerOnlyDirectory $tls
    $tlsPasswordPath=Join-Path $tls 'operations-download-test.password'
    $tlsStore=Join-Path $tls 'operations-download-test.p12'
    if (!(Test-Path -LiteralPath $tlsStore)) {
        $bytes=New-Object byte[] 32;$random=[Security.Cryptography.RandomNumberGenerator]::Create();try{$random.GetBytes($bytes)}finally{$random.Dispose()}
        [IO.File]::WriteAllText($tlsPasswordPath,[Convert]::ToBase64String($bytes),(New-Object Text.UTF8Encoding($false)))
        $manifest=Get-Content -LiteralPath (Join-Path $repo 'runtime\artifacts.json') -Raw | ConvertFrom-Json
        $keytool=Join-Path (Join-Path $root $manifest.javaHome) 'bin\keytool.exe'
        Invoke-SuiteNative $keytool @('-genkeypair','-alias','operations','-keyalg','RSA','-keysize','2048','-validity','3650','-storetype','PKCS12','-keystore',$tlsStore,'-storepass:file',$tlsPasswordPath,'-dname','CN=localhost','-ext','SAN=dns:localhost,ip:127.0.0.1','-noprompt') 'tls-fixture'
    }
    if($SelectedKind -eq 'Unit') {
        Update-SuiteJournal $journalPath {param($j) $j.state='running'}
        $label='selected-unit-'+$SelectedTest.Split('#')[0]
        $testLog=if($SelectedLogPath){$SelectedLogPath}else{Join-Path $logs ($label+'.log')}
        $arguments=@('-Kind','Unit','-Test',$SelectedTest,'-Root',$root,'-LogPath',$testLog)
        Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-Tests.ps1') $arguments $label 3600
    } elseif($SelectedKind -eq 'Integration') {
        Update-SuiteJournal $journalPath {param($j) $j.state='running'}
        Invoke-SuitePhase 'Start'
        $env:SE_BROWSER_PATH=$firefox;$env:SE_GECKODRIVER=$geckodriver
        $label='selected-integration-'+$SelectedTest.Split('#')[0]
        if($SelectedLogPath){$arguments=@('-Kind','Integration','-Test',$SelectedTest,'-Root',$root,'-Port',[string]$port,'-LogPath',$SelectedLogPath);Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-Tests.ps1') $arguments $label 3600}
        else{Invoke-SuiteSelection $SelectedTest $label 'Integration'}
        Invoke-SuitePhase 'Stop'
    } elseif($RecoverySeedTest) {
        Update-SuiteJournal $journalPath {param($j) $j.state='running'}
        $scenarioLog=if($RecoveryLogPath){$RecoveryLogPath}else{Join-Path $logs 'recovery-scenario.log'}
        $arguments=@('-SeedTest',$RecoverySeedTest,'-VerifyTest',$RecoveryVerifyTest,'-Transition',$RecoveryTransition,'-Root',$root,'-Port',[string]$port,'-LogPath',$scenarioLog,'-TestTimeoutSeconds',[string]$RecoveryTestTimeoutSeconds)
        if($RecoveryCrashSuite){$arguments+=@('-CrashSuite',$RecoveryCrashSuite)}
        if($RecoveryCrashPoint){$arguments+=@('-CrashPoint',$RecoveryCrashPoint)}
        Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-TestScenario.ps1') $arguments 'recovery-scenario' 7200
    } elseif($SelectedScenario) {
        Update-SuiteJournal $journalPath {param($j) $j.state='running'}
        if($SelectedScenario -eq 'ServiceCrashRecovery') {
            Invoke-SuitePhase 'Start'
            Invoke-SuiteSelection 'AuthenticationNamespaceLifecycleIT#bootstrapRollbackThenCommitAndLoginReadOnlyCompletion' 'service-prerequisite-bootstrap'
        }
        if($SelectedScenario -in @('CleanProvisioning','DownloadListener','ServiceShutdown','ServiceCrashRecovery')) {Enable-TestService}
        if($SelectedScenario -eq 'ServiceCrashRecovery') {
            Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-ServiceShutdownScenario.ps1') @('-Root',$root,'-Port',[string]$port,'-LogPath',(Join-Path $logs 'service-shutdown-prerequisite.log')) 'service-shutdown-prerequisite' 1800
        }
        $scenarioScript=Join-Path $PSScriptRoot ('Invoke-'+$SelectedScenario+'Scenario.ps1')
        if($SelectedScenario -eq 'CleanProvisioning') {$scenarioLog=if($ScenarioLogPath){$ScenarioLogPath}else{Join-Path $logs 'clean-provisioning.log'};$ScenarioArguments=@('-ReleasePackage',$archive,'-Root',$root,'-Port',[string]$port,'-LogPath',$scenarioLog);if($FreshAccountProbe){$ScenarioArguments+= '-FreshAccountProbe'}}
        elseif($SelectedScenario -eq 'DownloadListener') {$ScenarioArguments=@('-LogDirectory',(Join-Path $logs 'listener'),'-TlsMaterialDirectory',$tls)}
        elseif($SelectedScenario -eq 'LocalShutdownDrain') {$scenarioLog=if($ScenarioLogPath){$ScenarioLogPath}else{Join-Path $logs 'local-shutdown.log'};$ScenarioArguments=@('-Root',$root,'-Port',[string]$port,'-LogPath',$scenarioLog)}
        elseif($SelectedScenario -eq 'ServiceShutdown') {$scenarioLog=if($ScenarioLogPath){$ScenarioLogPath}else{Join-Path $logs 'service-shutdown.log'};$ScenarioArguments=@('-Root',$root,'-Port',[string]$port,'-LogPath',$scenarioLog)}
        elseif($SelectedScenario -eq 'ServiceCrashRecovery') {$scenarioLog=if($ScenarioLogPath){$ScenarioLogPath}else{Join-Path $logs 'service-crash-recovery.log'};$ScenarioArguments=@('-Root',$root,'-Port',[string]$port,'-LogPath',$scenarioLog,'-ProbeTest',$ScenarioProbeTest,'-CrashPoint',$ScenarioCrashPoint);if($ScenarioHistoryRecoveryChecks){$ScenarioArguments+= '-HistoryRecoveryChecks'};if($ScenarioAdditionalCrashPoints.Count){$ScenarioArguments+=@('-AdditionalCrashPoints');$ScenarioArguments+=,[string[]]$ScenarioAdditionalCrashPoints}}
        New-Item -ItemType Directory -Force -Path (Join-Path $logs 'listener') | Out-Null
        Invoke-SuiteChild $scenarioScript $ScenarioArguments ('scenario-'+$SelectedScenario) 10800
    } elseif ($Phase -eq 'Run') {
        Update-SuiteJournal $journalPath { param($j) $j.state='running' }
        Invoke-SuitePhase 'Start'
        Invoke-SuiteSelection 'AuthenticationNamespaceLifecycleIT' 'local-lifecycle'
        $env:DEPOSIT_TEST_PACKAGE_OUTPUT=Join-Path $logs 'generated-whole.zip'
        $index=0
        foreach($selection in $normal) { $index++; Invoke-SuiteSelection $selection ('normal-'+$index+'-'+($selection.Split('#')[0])) }
        foreach($package in @('generated-whole.zip','generated-subgraph.zip')) { Invoke-SuiteNative $python @((Join-Path $PSScriptRoot 'Test-PortablePackage.py'),(Join-Path $logs $package)) ('decode-'+$package) }
        Remove-Item Env:\DEPOSIT_TEST_PACKAGE_OUTPUT -ErrorAction SilentlyContinue
        foreach($transition in @('Restart','KillRestart')) { Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-TestScenario.ps1') @('-SeedTest',$seed,'-VerifyTest',$verify,'-Transition',$transition,'-Root',$root,'-Port',[string]$port,'-LogPath',(Join-Path $logs ('recovery-'+$transition+'.log')),'-TestTimeoutSeconds','900') ('recovery-'+$transition) 2400 }
        Enable-TestService
        Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-CleanProvisioningScenario.ps1') @('-ReleasePackage',$archive,'-Port',[string]$port,'-LogPath',(Join-Path $logs 'clean-provision.log')) 'clean-provision' 3600
        Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-CleanProvisioningScenario.ps1') @('-ReleasePackage',$archive,'-Port',[string]$port,'-LogPath',(Join-Path $logs 'fresh-account.log'),'-FreshAccountProbe') 'fresh-account' 3600
        Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-ServiceShutdownScenario.ps1') @('-Root',$root,'-Port',[string]$port,'-LogPath',(Join-Path $logs 'service-shutdown.log')) 'service-shutdown' 1800
        $crashArguments=@('-Root',$root,'-Port',[string]$port,'-LogPath',(Join-Path $logs 'service-crash.log'),'-ProbeTest',$probes,'-HistoryRecoveryChecks','-CrashPoint',$resourcePoints[0],'-AdditionalCrashPoints')
        $crashArguments+=,([string[]]$resourcePoints[1..8])
        Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-ServiceCrashRecoveryScenario.ps1') $crashArguments 'service-crash' 10800
        $listenerLogs=Join-Path $logs 'listener';New-Item -ItemType Directory -Path $listenerLogs | Out-Null
        Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-DownloadListenerScenario.ps1') @('-LogDirectory',$listenerLogs,'-TlsMaterialDirectory',$tls) 'listener' 2400
    }
} catch {
    $failure=$_.Exception.Message
    # Preserve the original call site before cleanup changes the journal state.
    try {
        $detail=@("Message: $failure","ErrorId: $($_.FullyQualifiedErrorId)","Position: $($_.InvocationInfo.PositionMessage)","Stack: $($_.ScriptStackTrace)","Exception: $($_.Exception.ToString())")
        if([IO.Directory]::Exists($logs)){[IO.File]::WriteAllLines((Join-Path $logs 'suite-failure.log'),$detail,(New-Object Text.UTF8Encoding($false)))}
    } catch { Write-Warning "Could not save suite failure details: $($_.Exception.Message)" }
} finally {
    if ($journalCreated) { try { Invoke-SuiteCleanup $journalPath $invocation } catch { $failure=($failure+' Cleanup failed: '+$_.Exception.Message).Trim() } }
    if ($watchdog) { if (!$watchdog.WaitForExit(10000)) { Write-Warning 'Watchdog remains active until the runner exits; retained journal records cleanup status.' }; $watchdog.Dispose() }
    foreach($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
    $suiteLock.Dispose()
    [DepositSuiteJobs]::Release()
}
if ($failure) { Write-Error "$failure Logs: $logs"; exit 1 }
Write-Output "PASS: integration harness $Phase. Logs: $logs"
