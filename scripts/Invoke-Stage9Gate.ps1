[CmdletBinding()]
param(
  [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$RunPrefix=('stage9-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')),
  [int]$Port=17770
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Inputs.ps1"
$repo=Split-Path $PSScriptRoot -Parent
$root='C:\data_processed\Deposit_v2\stage0'
$cleanupBarrier=Enter-Stage0CleanupBarrier $root
$gateLock=$null
$evidence=Join-Path $root "evidence\$RunPrefix"
$ownsGateResult=$false
try {
$gateLock=[IO.File]::Open("$root\gate.lock",'OpenOrCreate','ReadWrite','None')
  $evidence=Join-Path $root "evidence\$RunPrefix"
  if(Test-Path -LiteralPath $evidence){throw 'Use a fresh Stage 9 run prefix'}
  Start-Stage0Result $root $evidence $RunPrefix 'gate' $null | Out-Null
  $ownsGateResult=$true
  $prerequisiteHashes=@{}
  foreach($stage in @('0','1','2','3','4','5','5.5','6','6.5','7','8')){
    $fileStage=if($stage -eq '6.5'){'65'}else{$stage};$path="$root\stage$fileStage-checkpoint.json"
    if(!(Test-Path -LiteralPath $path)){throw "Accepted Stage $stage prerequisite required"}
    $checkpoint=Get-Content $path -Raw|ConvertFrom-Json;if($checkpoint.status -ne 'accepted'){throw "Accepted Stage $stage prerequisite required"}
    $prerequisiteHashes[$stage]=(Get-FileHash $path -Algorithm SHA256).Hash
  }
  $inputs=Get-Stage0Inputs $repo
  $inputsFile=Join-Path $evidence 'source-inputs.json';$inputs|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $inputsFile -Encoding UTF8
  $snapshot=Save-Stage0SourceSnapshot $repo $root $inputs
  $checkpointPath="$root\stage9-checkpoint.json"
  # Each Stage 9 category is enforced below by named executable cases and the
  # unit evidence collected from the gate's own build. No pending category is
  # cleared unless its assertions are part of this gate.
  $pending=@()
  $run=@{stage=9;gate=$RunPrefix;rerun='scripts/Invoke-Stage9Gate.ps1 -RunPrefix <fresh-id> -Port '+$Port;status='running';accepted=$false;sourceIdentity=$inputs.sha256;sourceSnapshot=$snapshot;startedUtc=[DateTime]::UtcNow.ToString('o');prerequisiteCheckpoints=$prerequisiteHashes;phases=@();pending=$pending;acceptance='Stage 9 gate remains incomplete until every pending contract criterion has executable evidence.'}
  function Save-Checkpoint {
    $run.updated=[DateTime]::UtcNow.ToString('o');$temporary=$checkpointPath+'.partial'
    [IO.File]::WriteAllText($temporary,($run|ConvertTo-Json -Depth 14),(New-Object Text.UTF8Encoding($false)))
    if(Test-Path $checkpointPath){[IO.File]::Replace($temporary,$checkpointPath,$checkpointPath+'.previous')}else{[IO.File]::Move($temporary,$checkpointPath)}
    Copy-Item -LiteralPath $checkpointPath -Destination (Join-Path $evidence 'checkpoint.json') -Force
  }
  function Clear-TestSessions {foreach($name in @('stage1-session.txt','stage55-session.txt')){$credential=Join-Path $root ('runs\stage0-host\secrets\'+$name);if(Test-Path -LiteralPath $credential){Remove-Item -LiteralPath $credential}}}
  function Phase([string]$Name,[hashtable]$Options=@{}) {
    Save-Checkpoint
    try {& "$PSScriptRoot\Stage0.ps1" -Phase $Name -RunId stage0-host -Port $Port -EvidenceGroup $RunPrefix -InputsFile $inputsFile @Options}
    finally {$phase=Get-Content "$root\runs\stage0-host\checkpoint.json" -Raw|ConvertFrom-Json;$run.phases+=@($phase);Save-Checkpoint}
    if($phase.phase -ne $Name -or $phase.status -ne 'passed'){throw "Stage 0 phase failed: $Name"}
  }
  Save-Checkpoint
  $stage7Package=Join-Path $evidence 'generated-namespace.zip'
  try {
    & python "$PSScriptRoot\Test-Stage65Catalog.py" | Tee-Object -FilePath (Join-Path $evidence 'public-catalog-checks.log')
    if($LASTEXITCODE -ne 0){throw 'Public procedure catalog/source contract failed'}
    Phase Stop;Clear-TestSessions;Phase Provision
    & "$PSScriptRoot\Test-Stage0Environment.ps1" -Build -Stage2Checks -Stage3Checks -Stage4Checks -Stage5Checks -Stage55Checks -RunPrefix ($RunPrefix+'-runtime') -Port $Port -InputsFile $inputsFile
    # The environment matrix ends by validating the normal probe-free package.
    # Reinstall the probe-enabled Stage 0 test runtime before cumulative tests.
    Phase Stop;Clear-TestSessions;Phase Reset;Phase Install;Phase Start
    foreach($suite in @('Stage1LifecycleIT','Stage1CoreIT','Stage2CoreIT','Stage3CoreIT','Stage4CoreIT','Stage5CoreIT','Stage55CoreIT','Stage6CoreIT','Stage65CoreIT')){Phase Test @{TestSelection=$suite}}
    $env:STAGE7_PACKAGE_OUTPUT=$stage7Package;Phase Test @{TestSelection='Stage7CoreIT'};Remove-Item Env:\STAGE7_PACKAGE_OUTPUT -ErrorAction SilentlyContinue
    Phase Test @{TestSelection='Stage8CoreIT,Stage9CoreIT'}
    Phase Test @{TestSelection='Stage9FormatIT'}
    foreach($suite in @('Stage55RecoveryIT#seed','Stage6RecoveryIT#seed','Stage65RecoveryIT#seed','Stage7RecoveryIT#seed','Stage8RecoveryIT#seed','Stage9RecoveryIT#seed')){Phase Test @{TestSelection=$suite}}
    $recovery='Stage1RecoveryIT,Stage2RecoveryIT,Stage3RecoveryIT,Stage4RecoveryIT,Stage5RecoveryIT,Stage55RecoveryIT#verify,Stage6RecoveryIT#verify,Stage65RecoveryIT#verify,Stage7RecoveryIT#verify,Stage8RecoveryIT#verify,Stage9RecoveryIT#verify'
    # Stage 8's recovery verifier applies its plan; it is intentionally run once.
    # Stage 9's staging replay is read-only and remains checked after forced restart.
    $forcedRecovery='Stage1RecoveryIT,Stage2RecoveryIT,Stage3RecoveryIT,Stage4RecoveryIT,Stage5RecoveryIT,Stage55RecoveryIT#verify,Stage6RecoveryIT#verify,Stage65RecoveryIT#verify,Stage7RecoveryIT#verify,Stage9RecoveryIT#verify'
    Phase Stop;Phase Start;Phase Test @{TestSelection=$recovery};Phase Kill;Phase Start;Phase Test @{TestSelection=$forcedRecovery};Phase Stop;Clear-TestSessions
    $cases=@()
    foreach($phase in $run.phases){
      if($phase.status -ne 'passed' -or $phase.inputs -ne $inputs.sha256 -or $phase.diagnostic -or $phase.readinessBypassed){throw 'Incomplete or diagnostic phase evidence'}
      if($phase.phase -eq 'Test'){
        $reports=@(Get-ChildItem (Join-Path $phase.evidence 'junit') -Filter 'TEST-*.xml');if(!$reports.Count){throw 'Missing JUnit evidence'}
        foreach($report in $reports){[xml]$xml=Get-Content $report.FullName -Raw;if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped -or [int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.tests -ne @($xml.testsuite.testcase).Count){throw 'Failed or skipped tests'};foreach($case in $xml.testsuite.testcase){$cases+=@{class=$case.classname;name=$case.name;report=$report.FullName}}}
      }
    }
    $expected=@{'org.deposit.it.Stage1LifecycleIT'=5;'org.deposit.it.Stage1CoreIT'=9;'org.deposit.it.Stage2CoreIT'=15;'org.deposit.it.Stage3CoreIT'=16;'org.deposit.it.Stage4CoreIT'=10;'org.deposit.it.Stage5CoreIT'=24;'org.deposit.it.Stage55CoreIT'=5;'org.deposit.it.Stage6CoreIT'=18;'org.deposit.it.Stage65CoreIT'=26;'org.deposit.it.Stage65RecoveryIT'=3;'org.deposit.it.Stage6RecoveryIT'=3;'org.deposit.it.Stage55RecoveryIT'=3;'org.deposit.it.Stage1RecoveryIT'=2;'org.deposit.it.Stage2RecoveryIT'=2;'org.deposit.it.Stage3RecoveryIT'=2;'org.deposit.it.Stage4RecoveryIT'=2;'org.deposit.it.Stage5RecoveryIT'=2;'org.deposit.it.Stage7CoreIT'=16;'org.deposit.it.Stage7RecoveryIT'=3;'org.deposit.it.Stage8CoreIT'=7;'org.deposit.it.Stage8RecoveryIT'=2;'org.deposit.it.Stage9CoreIT'=26;'org.deposit.it.Stage9FormatIT'=5;'org.deposit.it.Stage9RecoveryIT'=3}
    if($cases.Count -ne 209){throw "Incomplete cumulative Stage 1-9 coverage: $($cases.Count) test cases"}
    foreach($class in $expected.Keys){if(@($cases|Where-Object class -eq $class).Count -ne $expected[$class]){throw "Incomplete suite $class"}}
    foreach($requiredCase in @(
      @{suite='org.deposit.it.Stage8CoreIT';name='fixedSnapshotAppliesOnceAndUuidIndependentReanalysisReuses'},
      @{suite='org.deposit.it.Stage8CoreIT';name='staleAnalysisHasNoPartialEffects'},
      @{suite='org.deposit.it.Stage8CoreIT';name='equalMinimumMappingAmbiguityReturnsConflictWithoutPlan'},
      @{suite='org.deposit.it.Stage8CoreIT';name='nonKeyValueConflictReturnsNoPlan'},
      @{suite='org.deposit.it.Stage8CoreIT';name='compatibleSourcePatternsAreAddedAndSameNameDifferenceReturnsConflict'},
      @{suite='org.deposit.it.Stage8CoreIT';name='keylessObjectBesideForcedNewNeighborIsCreatedWhileKeyedControlReuses'},
      @{suite='org.deposit.it.Stage9CoreIT';name='csvTableStagesAndPagesWithoutDomainRevision'},
      @{suite='org.deposit.it.Stage9CoreIT';name='tablePageCursorBindsNamespaceAndRevision'},
      @{suite='org.deposit.it.Stage9CoreIT';name='malformedFinalRecordIsRejectedBeforeTablePublication'},
      @{suite='org.deposit.it.Stage9CoreIT';name='duplicateCsvHeadersAreRejectedBeforeTablePublication'},
      @{suite='org.deposit.it.Stage9CoreIT';name='inlineRowsUseImmutableAnalysisAndAtomicApply'},
      @{suite='org.deposit.it.Stage9CoreIT';name='invalidLateInlineRowBlocksAnalysisBeforeAnyWrite'},
      @{suite='org.deposit.it.Stage9CoreIT';name='dataByPatternRootIdReplacesOnlyPatternScopeAndCreatesConnectedRoles'},
      @{suite='org.deposit.it.Stage9CoreIT';name='rootImportRejectsDisconnectedPatternWithoutPlan'},
      @{suite='org.deposit.it.Stage9CoreIT';name='formRootIdTreatsOmittedRootValuesAsEmptyScopedReplacement'},
      @{suite='org.deposit.it.Stage9CoreIT';name='rootIdReplacesIncomingOutgoingAndSelfLoopScopesBeforeCreatingEdges'},
      @{suite='org.deposit.it.Stage9CoreIT';name='patternMatchEmptyRequiresExactPresenceWithinListedScope'},
      @{suite='org.deposit.it.Stage9CoreIT';name='preparedRowsAnalysisReplaysAfterIndependentRevisionAndKeepsPlanStale'},
      @{suite='org.deposit.it.Stage9CoreIT';name='preparedPatternAnalysisReplaysAfterIndependentRevisionAndKeepsPlanStale'},
      @{suite='org.deposit.it.Stage9CoreIT';name='queryExportUsesTypedQueryRowsAndPublishesDownloadableCsv'},
      @{suite='org.deposit.it.Stage9CoreIT';name='dResourceQueryExportFailsBeforePublishingResourceOrGrant'},
      @{suite='org.deposit.it.Stage9CoreIT';name='queryExportRetainsEmptyResultColumnsAndRejectsNativeObjects'},
      @{suite='org.deposit.it.Stage9CoreIT';name='lateXlsxCellLimitFailurePublishesNoResourceOrGrant'},
      @{suite='org.deposit.it.Stage9CoreIT';name='xlsxAcceptsTheMaximumCellTextLength'},
      @{suite='org.deposit.it.Stage9CoreIT';name='diagnosticsTruncateWithoutMarkingInvalidTableValid'},
      @{suite='org.deposit.it.Stage9CoreIT';name='activeImportPlanPinsExpiredTableUntilCompletion'},
      @{suite='org.deposit.it.Stage9CoreIT';name='expiredUnpinnedTableIsCollectedAndRevokedEditorCannotReadStaging'},
      @{suite='org.deposit.it.Stage9CoreIT';name='xlsxLoadsStagesExportsAndReopensWithIndependentWorkbookReader'},
      @{suite='org.deposit.it.Stage9CoreIT';name='geoPackageFeatureLayerLoadsStagesExportsAndReloadsValidSQLiteFile'},
      @{suite='org.deposit.it.Stage9CoreIT';name='geoPackageOutputPreservesAllSixGeometryFamiliesAndZCoordinates'},
      @{suite='org.deposit.it.Stage9CoreIT';name='layoutIsDeterministicAndRejectsApplyOption'},
      @{suite='org.deposit.it.Stage9CoreIT';name='layoutCancellationUsesListedRequestAndLeavesDomainStateUnchanged'},
      @{suite='org.deposit.it.Stage9FormatIT';name='csvAndXlsxPreserveDifficultTypedValuesAndChecksums'},
      @{suite='org.deposit.it.Stage9FormatIT';name='multiSridGeoPackageZipListsChecksummedMembersAndValidDatabases'},
      @{suite='org.deposit.it.Stage9FormatIT';name='preparedSaveReplayReturnsOnePublishedResourceAndGrant'},
      @{suite='org.deposit.it.Stage9FormatIT';name='preparedLoadReplayReturnsTheSameStagedTableForEveryFormat'},
      @{suite='org.deposit.it.Stage9FormatIT';name='rolledBackLoadLeavesNoPublishedTableLeaseReceiptOrStagingFiles'},
      @{suite='org.deposit.it.Stage9RecoveryIT';name='seed'},
      @{suite='org.deposit.it.Stage9RecoveryIT';name='verify'},
      @{suite='org.deposit.it.Stage6CoreIT';name='completeStreamsPagesAndMixedOrdering'},
      @{suite='org.deposit.it.Stage6CoreIT';name='cursorScopeRevisionAndRequestBinding'},
      @{suite='org.deposit.it.Stage6CoreIT';name='viewerAuthorizationAndCursorReauthentication'}
    )){if(@($cases|Where-Object {$_.class -eq $requiredCase.suite -and $_.name -eq $requiredCase.name}).Count -lt 1){throw "Missing Stage 9 acceptance integration evidence: $($requiredCase.name)"}}
    $run.testCases=$cases;$cases|ConvertTo-Json -Depth 6|Set-Content (Join-Path $evidence 'junit-results.json') -Encoding UTF8
    & "$PSScriptRoot\Test-LiveRunner.ps1" -Name ($RunPrefix+'-runtime-live') -InputsFile $inputsFile
    # Restore the probe-free distribution after cumulative tests. The Stage 0
    # environment check's normal-package install is replaced for Stage 9 tests.
    Phase Stop;Clear-TestSessions;Phase Reset;Phase Package;Phase Install @{WithoutProbe=$true}
    & "$PSScriptRoot\Collect-Stage0.ps1" -GateName ($RunPrefix+'-runtime') -Accept -CumulativeOnly
    $runtimePath="$root\evidence\$RunPrefix-runtime\cumulative-checkpoint.json";$runtime=Get-Content $runtimePath -Raw|ConvertFrom-Json
    if($runtime.status -ne 'accepted' -or $runtime.inputs -ne $inputs.sha256 -or $runtime.testCases.Count -ne 138){throw 'Missing common cumulative completion checkpoint'};$run.cumulative=$runtimePath
    & "$PSScriptRoot\Audit-Artifacts.ps1" -Root $root -Inputs $inputs.sha256
    $artifactAudit=Get-Content "$root\evidence\artifact-audit\inventory.json" -Raw|ConvertFrom-Json
    if($artifactAudit.inputs -ne $inputs.sha256 -or $artifactAudit.status -ne 'no-conflicting-runtime-classes'){throw 'Installed artifact audit failed'}
    $formatLock=Get-Content "$repo\runtime\format-runtime-lock.json" -Raw|ConvertFrom-Json
    if((Get-FileHash "$repo\runtime\format-dependencies.pom.xml" -Algorithm SHA256).Hash -ne $formatLock.sourcePomSha256){throw 'Format dependency lock does not match its source POM'}
    $installedRoot=Join-Path $root 'runs\stage0-host\neo4j'
    foreach($dependency in $formatLock.dependencies){
      if(!$dependency.licenses.Count){throw "Format dependency has no declared license: $($dependency.file)"}
      $installedFile=Join-Path (Join-Path $installedRoot $(if($dependency.ship){'plugins'}else{'lib'})) $dependency.file
      if(!(Test-Path -LiteralPath $installedFile) -or (Get-FileHash $installedFile -Algorithm SHA256).Hash -ne $dependency.sha256){throw "Installed format dependency hash mismatch: $($dependency.file)"}
    }
    $licenseOverrides=Get-Content "$repo\runtime\license-overrides.json" -Raw|ConvertFrom-Json
    foreach($override in $licenseOverrides.PSObject.Properties){$licensePath=Join-Path $root ('cache\'+$override.Value.file);if(!(Test-Path -LiteralPath $licensePath) -or (Get-FileHash $licensePath -Algorithm SHA256).Hash -ne $override.Value.sha256){throw "Format license text missing or changed: $($override.Value.file)"}}
    $buildPhases=@(Get-ChildItem (Join-Path $root "evidence\$RunPrefix-runtime\phases") -Directory | Where-Object Name -like 'Build-*')
    $unitReports=@($buildPhases | ForEach-Object {Get-ChildItem (Join-Path $_.FullName 'unit-junit') -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue});$unitNames=@();$unitCases=@();$unitCount=0
    foreach($report in $unitReports){[xml]$xml=Get-Content $report.FullName -Raw;if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Failed or skipped unit tests'};$unitCount+=[int]$xml.testsuite.tests;$unitNames+=$xml.testsuite.name;foreach($test in $xml.testsuite.testcase){$unitCases+=@{suite=$xml.testsuite.name;name=$test.name}}}
    foreach($required in @('deposit.service.GeoPackageAdapterTest','deposit.service.TabularCsvTest','deposit.service.TabularXlsxStreamingTest','deposit.service.TabularLimitsTest','deposit.service.ImportServiceTest','deposit.common.ResourceLinkEncoderTest')){if($unitNames -notcontains $required){throw "Missing Stage 9 unit evidence: $required"}};$run.unitTests=$unitCount
    foreach($requiredCase in @(@{suite='deposit.service.GeoPackageAdapterTest';name='featureLayerRequiresItsSoleRegisteredGeometryAndAnExactSelection'},@{suite='deposit.service.GeoPackageAdapterTest';name='unresolvedAndMixedGeometrySrsAreReportedWithoutInventingCrs'},@{suite='deposit.service.GeoPackageAdapterTest';name='attributeLayerRejectsGeometrySelection'},@{suite='deposit.service.GeoPackageAdapterTest';name='outputSeparatesMixedUnknownAndNullGeometryCrsAndPreservesFieldIdentity'})){if(@($unitCases|Where-Object {$_.suite -eq $requiredCase.suite -and $_.name -eq $requiredCase.name}).Count -ne 1){throw "Missing Stage 9 GeoPackage matrix evidence: $($requiredCase.name)"}}
    foreach($requiredCase in @(@{suite='deposit.service.TabularXlsxStreamingTest';name='meaningfulSharedStringWorkbookParsesWithinConstrainedHeapAndCleansWorkspace'},@{suite='deposit.service.TabularXlsxStreamingTest';name='date1904FormulaCachesRichStringsAndSparsePresenceArePreserved'},@{suite='deposit.service.TabularXlsxStreamingTest';name='cancellationWhileReadingSharedStringsRemovesTemporaryWorkspace'},@{suite='deposit.service.TabularXlsxStreamingTest';name='preflightRejectsZipEntryAndExpandedContentLimits'},@{suite='deposit.service.TabularLimitsTest';name='encodedContentLimitAcceptsTheBoundaryAndRejectsTheNextByte'},@{suite='deposit.service.TabularLimitsTest';name='xlsxDimensionsIncludeTheHeaderAndEnforceBothAxes'})){if(@($unitCases|Where-Object {$_.suite -eq $requiredCase.suite -and $_.name -eq $requiredCase.name}).Count -ne 1){throw "Missing Stage 9 format-limit evidence: $($requiredCase.name)"}}
    foreach($requiredCase in @(@{suite='deposit.service.TabularCsvTest';name='honorsNonDefaultDelimiter'})){if(@($unitCases|Where-Object {$_.suite -eq $requiredCase.suite -and $_.name -eq $requiredCase.name}).Count -ne 1){throw "Missing Stage 9 CSV matrix evidence: $($requiredCase.name)"}}
    foreach($requiredCase in @(@{suite='deposit.service.ImportServiceTest';name='randomizedMinimumAndAmbiguityAgreeWithIndependentExhaustiveOracle'},@{suite='deposit.service.ImportServiceTest';name='candidateHeavyAmbiguityStopsAfterTwoMinimumMappingsAndCleansScratch'},@{suite='deposit.service.ImportServiceTest';name='expiredSharedDeadlineIsHonoredAndScratchIsCleaned'},@{suite='deposit.service.ImportServiceTest';name='deepInputUsesDiskFramesUnderConstrainedHeapAndStack'},@{suite='deposit.service.GeoPackageAdapterTest';name='featureLayerRequiresItsSoleRegisteredGeometryAndAnExactSelection'},@{suite='deposit.service.GeoPackageAdapterTest';name='unresolvedAndMixedGeometrySrsAreReportedWithoutInventingCrs'},@{suite='deposit.service.GeoPackageAdapterTest';name='attributeLayerRejectsGeometrySelection'},@{suite='deposit.service.GeoPackageAdapterTest';name='outputSeparatesMixedUnknownAndNullGeometryCrsAndPreservesFieldIdentity'},@{suite='deposit.service.GeoPackageAdapterTest';name='positiveCrsDefinitionsComeFromTheInstalledAuthorityDatabase'},@{suite='deposit.service.GeoPackageAdapterTest';name='attributeAndFeatureGeoPackagesRoundTripThroughIndependentSqliteReader'})){if(@($unitCases|Where-Object {$_.suite -eq $requiredCase.suite -and $_.name -eq $requiredCase.name}).Count -ne 1){throw "Missing Stage 9 bounded-memory/GeoPackage matrix evidence: $($requiredCase.name)"}}
    if((Get-Stage0Inputs $repo).sha256 -ne $inputs.sha256){throw 'Source identity changed during gate'}
    foreach($stage in $prerequisiteHashes.Keys){$fileStage=if($stage -eq '6.5'){'65'}else{$stage};if((Get-FileHash "$root\stage$fileStage-checkpoint.json" -Algorithm SHA256).Hash -ne $prerequisiteHashes[$stage]){throw "Prerequisite checkpoint changed: Stage $stage"}}
    if($pending.Count){$run.status='incomplete';$run.cause='Stage 9 acceptance scenarios remain unverified; see pending criteria.'}
    else {$run.status='verified';$run.cause=$null}
  } catch {$run.status='incomplete';$run.cause=$_.Exception.Message}
  finally {
    Remove-Item Env:\STAGE7_PACKAGE_OUTPUT -ErrorAction SilentlyContinue
    try {if((Get-Content "$root\runs\stage0-host\checkpoint.json" -Raw|ConvertFrom-Json).status -eq 'passed'){Phase Stop}else{& "$PSScriptRoot\Stage0.ps1" -Phase Stop -RunId stage0-host -Port $Port -EvidenceGroup $RunPrefix -InputsFile $inputsFile};Clear-TestSessions}
    catch {$run.status='incomplete';$run.accepted=$false;$run.cause='Final stop failed: '+$_.Exception.Message}
  }
  if($run.status -eq 'verified'){$run.status='accepted';$run.accepted=$true;$run.pending=@()}
  Save-Checkpoint
  if($run.status -ne 'accepted'){Write-Error "Stage 9 acceptance is incomplete; evidence: $evidence";exit 1}
} finally {try {if($ownsGateResult){Complete-Stage0GateResult $root $evidence $RunPrefix}} finally {if($gateLock){$gateLock.Dispose()};$cleanupBarrier.Dispose()}}
if($run.status -eq 'accepted'){try{& "$PSScriptRoot\Cleanup-Stage0Evidence.ps1"}catch{Write-Warning "Stage 9 was accepted, but Stage 0 cleanup failed: $($_.Exception.Message)"}}
