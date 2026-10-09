[CmdletBinding()]
param([ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$RunPrefix=('stage5-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')),[int]$Port=17770)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Inputs.ps1"
$root='C:\data_processed\Deposit_v2\stage0';$repo=Split-Path $PSScriptRoot -Parent
$prior=@{}
foreach($stage in 0..4){$path="$root\stage$stage-checkpoint.json";if((Get-Content $path -Raw|ConvertFrom-Json).status -ne 'accepted'){throw "Accepted Stage $stage prerequisite required"};$prior[[string]$stage]=(Get-FileHash $path -Algorithm SHA256).Hash}
$directory=Join-Path $root "evidence\$RunPrefix";if(Test-Path -LiteralPath $directory){throw 'Use a fresh evidence prefix'}
New-Item -ItemType Directory -Path $directory | Out-Null
$inputs=Get-Stage0Inputs $repo;$inputsFile=Join-Path $directory 'source-inputs.json';$inputs|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $inputsFile -Encoding UTF8
$snapshot=Save-Stage0SourceSnapshot $repo $root $inputs
$phases=New-Object System.Collections.Generic.List[object]
$checkpoint=@{stage=5;status='running';inputs=$inputs.sha256;sourceSnapshot=$snapshot;prerequisites=$prior;gate=$RunPrefix;sessionId=$env:CODEX_THREAD_ID;phases=@();nextAction='Run cumulative environment/probe/crash cases';pending=@('Stage 6 query','Stages 6-11 query/exchange/import/browser/release acceptance')}
function Save-Checkpoint {
    $checkpoint.phases=$phases.ToArray();$checkpoint.updated=[DateTime]::UtcNow.ToString('o');$path="$root\stage5-checkpoint.json";$temporary=$path+'.partial'
    [IO.File]::WriteAllText($temporary,($checkpoint|ConvertTo-Json -Depth 15),(New-Object Text.UTF8Encoding($false)))
    if(Test-Path $path){[IO.File]::Replace($temporary,$path,$path+'.previous')}else{[IO.File]::Move($temporary,$path)}
    Copy-Item -LiteralPath $path -Destination "$directory\checkpoint.json" -Force
}
function Phase([string]$Name,[hashtable]$Options=@{}) {
    Save-Checkpoint
    try {& "$PSScriptRoot\Stage0.ps1" -Phase $Name -RunId stage0-host -Port $Port -EvidenceGroup $RunPrefix -InputsFile $inputsFile @Options}
    finally {$phases.Add((Get-Content "$root\runs\stage0-host\checkpoint.json" -Raw|ConvertFrom-Json));Save-Checkpoint}
}
Save-Checkpoint
try {
    & "$PSScriptRoot\Test-Stage1Inputs.ps1" | Tee-Object -FilePath "$directory\input-checks.log"
    Phase Provision
    & "$PSScriptRoot\Test-Stage0Environment.ps1" -Build -Stage2Checks -Stage3Checks -Stage4Checks -Stage5Checks -RunPrefix ($RunPrefix+'-runtime') -Port $Port -InputsFile $inputsFile
    $checkpoint.nextAction='Run cumulative Stage 1-5 public contracts on normal distribution';Save-Checkpoint
    Phase Start
    foreach($suite in @('Stage1LifecycleIT','Stage1CoreIT','Stage2CoreIT','Stage3CoreIT','Stage4CoreIT','Stage5CoreIT')){Phase Test @{TestSelection=$suite}}
    $checkpoint.nextAction='Verify graceful restart and actual termination recovery';Save-Checkpoint
    Phase Stop;Phase Start;Phase Test @{TestSelection='Stage1RecoveryIT,Stage2RecoveryIT,Stage3RecoveryIT,Stage4RecoveryIT,Stage5RecoveryIT'}
    Phase Kill;Phase Start;Phase Test @{TestSelection='Stage1RecoveryIT,Stage2RecoveryIT,Stage3RecoveryIT,Stage4RecoveryIT,Stage5RecoveryIT'}
    Phase Stop
    $credential=Join-Path $root 'runs\stage0-host\secrets\stage1-session.txt';if(Test-Path -LiteralPath $credential){Remove-Item -LiteralPath $credential}
    $cases=New-Object System.Collections.Generic.List[object]
    foreach($phase in $phases){if($phase.status -ne 'passed' -or $phase.inputs -ne $inputs.sha256 -or $phase.diagnostic -or $phase.readinessBypassed){throw 'Incomplete or diagnostic evidence'}}
    foreach($phase in @($phases|Where-Object phase -eq 'Test')){
        $reports=@(Get-ChildItem (Join-Path $phase.evidence 'junit') -Filter 'TEST-*.xml');if(!$reports.Count){throw 'Missing JUnit evidence'}
        foreach($report in $reports){[xml]$xml=Get-Content $report.FullName -Raw;if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped -or [int]$xml.testsuite.tests -lt 1){throw 'Failed/skipped tests'};foreach($case in $xml.testsuite.testcase){$cases.Add(@{class=$case.classname;name=$case.name;seconds=$case.time;report=$report.FullName})}}
    }
    $expected=@{'org.deposit.it.Stage1LifecycleIT'=5;'org.deposit.it.Stage1CoreIT'=9;'org.deposit.it.Stage2CoreIT'=15;'org.deposit.it.Stage3CoreIT'=16;'org.deposit.it.Stage4CoreIT'=10;'org.deposit.it.Stage5CoreIT'=24;'org.deposit.it.Stage1RecoveryIT'=2;'org.deposit.it.Stage2RecoveryIT'=2;'org.deposit.it.Stage3RecoveryIT'=2;'org.deposit.it.Stage4RecoveryIT'=2;'org.deposit.it.Stage5RecoveryIT'=2}
    if($cases.Count -ne 89){throw 'Incomplete cumulative Stage 1-5 coverage'}
    foreach($class in $expected.Keys){if(@($cases|Where-Object class -eq $class).Count -ne $expected[$class]){throw "Incomplete suite $class"}}
    $stage5Names=@('defaultPersistentBaselineAndScientificImages','emptyNetBatchReversalReceiptAndOrdinaryNoop','failedBatchAndExplicitRollbackLeaveNoHistory','inferredSchemaAndDeletedCascadesRestoreExactIds','unrelatedFieldsAndCreationAuditSurviveReversal','sameValueReturnAndBatchTouchesConflictThenUndoClears','defaultCandidateConflictsWithoutFallbackAndExplicitOlderWorks','ownOtherPermissionsViewerInspectionAndAuthorizationBeforeReplay','rejectsBaselineUndoUndoAndDuplicateChainEffects','schemaConversionRenameMembershipAndDeclarationRecreation','structuralDependantsAndLaterTypeChangesConflict','restoreBaselineClosedEpochRolesAndStalePreview','allModeTransitionsPromotionAndSequenceOnlyTags','deletedIdsAuditAndOpaqueToolsNullCollections','resourcesHistoryRoleAccessRetentionRestoreAndCorruption','geometryReversalRestoresIdentityCoordinatesAndMembership','equalTimestampsOrderingAndCursorScopeChanges','completeStreamAndPagesBeyondOneThousand','staleConcurrentActionsAndUnknownCommitReplay','namespaceDeletionRemovesHistoryButPreservesReceipt','differentActorSameValueReturnAndTransientDisconnect','allSupportedGeometryKindsReverseWithFixedCoordinates','allNoChangeBatchAndCreatedThenDeletedBatch','persistRecoveryFixture')
    if(Compare-Object $stage5Names @($cases|Where-Object class -eq 'org.deposit.it.Stage5CoreIT'|ForEach-Object name)){throw 'Missing or duplicate Stage5 core scenario'}
    foreach($case in @($cases|Where-Object class -eq 'org.deposit.it.Stage5RecoveryIT')){if($case.name -ne 'persistentAndTransientRecoveryReceiptAndCursor'){throw 'Wrong Stage5 recovery scenario'}}
    $cases.ToArray()|ConvertTo-Json -Depth 6|Set-Content "$directory\junit-results.json" -Encoding UTF8
    $checkpoint.testCases=$cases.ToArray();$checkpoint.nextAction='Run required real CLI interruption/resumption fixture and collect cumulative acceptance';Save-Checkpoint
    & "$PSScriptRoot\Test-LiveRunner.ps1" -Name ($RunPrefix+'-runtime-live') -InputsFile $inputsFile
    & "$PSScriptRoot\Collect-Stage0.ps1" -GateName ($RunPrefix+'-runtime') -Accept -CumulativeOnly
    $runtime=Get-Content "$root\evidence\$RunPrefix-runtime\cumulative-checkpoint.json" -Raw|ConvertFrom-Json
    if($runtime.status -ne 'accepted' -or $runtime.inputs -ne $inputs.sha256 -or $runtime.testCases.Count -ne 135 -or @($runtime.scenarios|Where-Object name -like 'stage5-crash-*').Count -ne 6 -or @($runtime.scenarios|Where-Object name -like 'stage4-crash-*').Count -ne 8){throw 'Missing cumulative Stage 5 runtime/crash evidence'}
    $unit=Get-ChildItem "$root\evidence\$RunPrefix-runtime\phases\Build-*\unit-junit\TEST-*.xml"
    $unitTotal=0
    foreach($report in $unit){[xml]$xml=Get-Content -LiteralPath $report.FullName -Raw;if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Failed/skipped unit tests'};$unitTotal+=[int]$xml.testsuite.tests}
    if($unitTotal -ne 23){throw 'Missing expected unit suite evidence'}
    foreach($stage in 0..4){if((Get-FileHash "$root\stage$stage-checkpoint.json" -Algorithm SHA256).Hash -ne $prior[[string]$stage]){throw "Accepted Stage $stage checkpoint changed"}}
    if((Get-Stage0Inputs $repo).sha256 -ne $inputs.sha256){throw 'Sources changed during gate'}
    $checkpoint.cumulative="$root\evidence\$RunPrefix-runtime\cumulative-checkpoint.json";$checkpoint.status='accepted';$checkpoint.nextAction='User may manually commit Stage 5; Stage 6 remains pending';Save-Checkpoint
    $report=@('# Stage 5 accepted','','Source fingerprint: '+$inputs.sha256,'','Cumulative deployed cases: '+($cases.Count+$runtime.testCases.Count)+'; no failures, errors or skips.','Six Stage 5 and eight Stage 4 real process termination/restart scenarios passed.','', 'Later-dependent history/import/export/browser scenarios remain pending; no whole acceptance ID is claimed.','','Rerun: scripts/Invoke-Stage5Gate.ps1 -RunPrefix <fresh-id> -Port '+$Port)
    [IO.File]::WriteAllLines("$directory\REPORT.md",[string[]]$report,(New-Object Text.UTF8Encoding($false)))
}catch{$checkpoint.status='failed';$checkpoint.failureCause='stage5-code';$checkpoint.failure=$_.Exception.Message;$checkpoint.nextAction='Inspect recorded failure; repair or resolve external blocker, then rerun with fresh evidence prefix';Save-Checkpoint;throw}
finally {
    if($checkpoint.status -ne 'accepted'){try{Phase Stop}catch{Write-Warning 'Inspect owned server: stop failed'}}
    $credential=Join-Path $root 'runs\stage0-host\secrets\stage1-session.txt';if(Test-Path -LiteralPath $credential){Remove-Item -LiteralPath $credential}
}
