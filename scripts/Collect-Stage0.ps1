[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$GateName,
    [switch]$Accept,
    [switch]$CumulativeOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Inputs.ps1"
. "$PSScriptRoot\Credentials.ps1"
$root='C:\data_processed\Deposit_v2\stage0'; $repo=Split-Path $PSScriptRoot -Parent
$inputs=Get-Stage0Inputs $repo
$directory=Join-Path $root "evidence\$GateName"
$gate=Get-Content "$directory\results.json" -Raw | ConvertFrom-Json
# Schema 2 represents sequential cycles of one installation. Historical multi-instance gates cannot be accepted.
. "$PSScriptRoot\ArtifactIntegrity.ps1"
$contracts=[ordered]@{
 'clean-wrapper-build'=@('Build:passed')
 'install-single-instance'=@('Install:passed')
 'authenticated-readiness'=@('Start:passed')
 'native-runtime-authentication-and-fixtures'=@('Test:passed')
 'independent-client-sessions'=@('Test:passed')
 'graceful-restart-and-persistent-uuid-maps'=@('Stop:passed','Start:passed','Test:passed')
 'actual-termination-and-recovery'=@('Kill:passed','Start:passed','Test:passed','Stop:passed')
 'reject-modified-installed-runtime'=@('Start:failed')
 'reject-missing-spatial'=@('Start:failed')
 'reject-modified-spatial-binary'=@('Start:failed')
 'reject-occupied-port'=@('Start:failed')
 'reject-invalid-deposit-configuration'=@('Start:failed','Test:passed','Stop:passed')
 'strict-neo4j-configuration-validation'=@('Start:failed','Stop:passed')
 'bounded-startup-timeout'=@('Start:failed','Kill:passed')
 'runner-checkpoint-and-resume-policy'=@()
 'sequential-clean-reprovisioning'=@('Reset:passed','Install:passed','Start:passed','Test:passed','Stop:passed')
 'normal-distribution-excludes-probe-and-permissions'=@('Reset:passed','Package:passed','Install:passed','Start:passed','Test:passed','Stop:passed')
 'evidence-failure-still-saves-checkpoint-and-restores-environment'=@('Evidence:failed')
}
$selections=@{
 'native-runtime-authentication-and-fixtures'='EnvironmentIT#nativeRuntimeAndApoc,RuntimeIT'
 'independent-client-sessions'='EnvironmentIT#independentClientSessions'
 'graceful-restart-and-persistent-uuid-maps'='RuntimeIT,EnvironmentIT#independentClientSessions'
 'actual-termination-and-recovery'='RuntimeIT,EnvironmentIT#independentClientSessions'
 'reject-invalid-deposit-configuration'='EnvironmentIT#rejectsUnavailableDependenciesAndConfiguration'
 'sequential-clean-reprovisioning'='RuntimeIT,EnvironmentIT#nativeRuntimeAndApoc+independentClientSessions'
 'normal-distribution-excludes-probe-and-permissions'='DistributionIT'
}
$runtimeNames=@('discoveryAndRuntime','rejectsInvalidAuthentication','fixtureAndTransactionPersistence','rollbackAtEachSpatialBoundary','editsLayerMovementAndNamespaceIsolation','concurrentRegistrationAndPersistentGeometryIdentity','callerRollbackIncludesSpatialGraphAndIndex')
$runtimeNames+=@(1..12 | ForEach-Object {"geometryRoundTrip(Geometry)[$_]"})
function Require-File([string]$Path) {if(!(Test-Path -LiteralPath $Path -PathType Leaf)) {throw "Required evidence is missing: $Path"}}
function Assert-Plugins($Left,$Right) {
    $a=@($Left | ForEach-Object {$_.name+':'+$_.sha256.ToLowerInvariant()} | Sort-Object)
    $b=@($Right | ForEach-Object {$_.name+':'+$_.sha256.ToLowerInvariant()} | Sort-Object)
    if(!$a.Count -or !$b.Count -or (Compare-Object $a $b)) {throw 'Sequential installation/distribution artifact sets differ'}
}
if($gate.schemaVersion -ne 2 -or $gate.installation -ne 'stage0-host' -or $gate.inputs -ne $inputs.sha256 -or $gate.status -ne 'environment-checks-passed') {throw 'A current, complete single-instance gate is required'}
$required=@($contracts.Keys)
$actualNames=@($gate.results | ForEach-Object name)
$expectedNames=@($required)
if($actualNames -contains 'archive-previous-installation') {$expectedNames+=@('archive-previous-installation');$contracts['archive-previous-installation']=@('Reset:passed')}
if($actualNames -contains 'stage2-isolated-clock-and-commit-faults') {
    $expectedNames+=@('stage2-isolated-clock-and-commit-faults');$contracts['stage2-isolated-clock-and-commit-faults']=@('Test:passed')
    $selections['stage2-isolated-clock-and-commit-faults']='Stage2ProbeIT'
}
if($actualNames -contains 'stage3-isolated-batch-commit-faults') {
    $expectedNames+=@('stage3-isolated-batch-commit-faults');$contracts['stage3-isolated-batch-commit-faults']=@('Test:passed')
    $selections['stage3-isolated-batch-commit-faults']='Stage3ProbeIT'
}
if($actualNames -contains 'stage4-storage-faults-and-invariants') {
    $expectedNames+=@('stage4-storage-faults-and-invariants');$contracts['stage4-storage-faults-and-invariants']=@('Test:passed');$selections['stage4-storage-faults-and-invariants']='Stage4ProbeIT'
    foreach($point in @('chunk-flushed','publication-before','publication-after','ready-before-commit','relocation-before','relocation-after','collection-before','collection-after')) {
        $name='stage4-crash-'+$point;$expectedNames+=@($name);$contracts[$name]=@('Test:passed','Start:passed','Test:passed')
    }
}
if(@(Compare-Object $actualNames $expectedNames).Count -or @($actualNames | Sort-Object -Unique).Count -ne $actualNames.Count) {throw 'Scenario coverage is incomplete or duplicated'}
$testCases=New-Object System.Collections.Generic.List[object]
$files=New-Object System.Collections.Generic.List[object]
$seen=@{};$installs=New-Object System.Collections.Generic.List[object];$packagePhase=$null
foreach($scenario in $gate.results) {
    if($scenario.status -ne 'passed') {throw "Failed scenario: $($scenario.name)"}
    $signature=@($scenario.phases | ForEach-Object {$_.phase+':'+$_.status})
    $expected=@($contracts[$scenario.name])
    if(($signature -join '|') -ne ($expected -join '|')) {throw "Incomplete phase evidence: $($scenario.name)"}
    foreach($reference in $scenario.phases) {
        $path=[IO.Path]::GetFullPath($reference.path)
        if(!$path.StartsWith($directory+'\phases\',[StringComparison]::OrdinalIgnoreCase) -or $seen.ContainsKey($path)) {throw 'Phase evidence is outside this gate or reused'}
        $seen[$path]=$true;Require-File $path
        $phase=Get-Content $path -Raw | ConvertFrom-Json
        if($phase.runId -ne 'stage0-host' -or $phase.inputs -ne $inputs.sha256 -or $phase.phase -ne $reference.phase -or $phase.status -ne $reference.status -or $phase.readinessBypassed -or $phase.diagnostic -or [IO.Path]::GetFullPath($phase.evidence) -ne (Split-Path $path -Parent)) {throw 'Phase identity/provenance is invalid'}
        if($scenario.name -eq 'evidence-failure-still-saves-checkpoint-and-restores-environment') {
            if(!$phase.evidenceFailure) {throw 'Evidence-failure checkpoint has no diagnostic'}
            continue
        }
        foreach($name in @('inputs.json','artifact-manifest.json','spatial-dependencies.json','environment.json','server-commit.txt','hub-commit.txt')) {Require-File "$($phase.evidence)\$name"}
        foreach($name in @('inputs.json')) {if((Get-Content "$($phase.evidence)\$name" -Raw | ConvertFrom-Json).sha256 -ne $inputs.sha256) {throw 'Phase inputs changed'}}
        if($phase.phase -eq 'Install') {
            $installed=Get-Content "$($phase.evidence)\installed-artifacts.json" -Raw | ConvertFrom-Json
            if($installed.inputs -ne $inputs.sha256 -or $installed.diagnostic) {throw 'Installation provenance is stale'}
            foreach($name in @('owner.json','neo4j.conf','deposit.properties','install.log')) {Require-File "$($phase.evidence)\$name"}
            $installs.Add($installed)
        }
        if($phase.phase -eq 'Reset') {
            $archive=Get-Content "$($phase.evidence)\reset-archive.json" -Raw | ConvertFrom-Json
            Require-File $archive.path
        }
        if($phase.phase -eq 'Package') {$packagePhase=$phase}
        if($phase.phase -eq 'Test') {
            if($scenario.name -like 'stage4-crash-*') {if($phase.testSelection -notin @('Stage4CrashIT#trigger','Stage4CrashIT#verify')) {throw 'Wrong crash suite'}}
            elseif($phase.testSelection -ne $selections[$scenario.name]) {throw 'Wrong test selection for scenario'}
            $expectedMode=if($scenario.name -in @('graceful-restart-and-persistent-uuid-maps','actual-termination-and-recovery')) {'verify'} else {'seed'}
            if($phase.testMode -ne $expectedMode) {throw 'Persistence scenario did not verify its baseline'}
            $expectedCases=New-Object System.Collections.Generic.List[string]
            foreach($selection in $phase.testSelection.Split(',')) {
                if($selection -eq 'RuntimeIT') {foreach($name in $runtimeNames) {$expectedCases.Add('org.deposit.it.RuntimeIT::'+$name)}}
                elseif($selection -eq 'DistributionIT') {$expectedCases.Add('org.deposit.it.DistributionIT::normalDistributionExcludesProbePermissionsAndProcedures')}
                elseif($selection -eq 'Stage2ProbeIT') {foreach($name in @('competingBootstrapAfterRollback','idleAndAbsoluteExpiryUseFixedProductionLimits','expiryBeforeCommitRollsBackDataRevisionAndReceipt','failedPasswordCommitDoesNotRevokeOrAdvance','failedDeletionCommitPreservesSelectionGraphAndReceipt','loginWaitsForPasswordResetCommit','tokensAreBoundToAuthenticatedNeo4jPrincipal')) {$expectedCases.Add('org.deposit.it.Stage2ProbeIT::'+$name)}}
                elseif($selection -eq 'Stage3ProbeIT') {foreach($name in @('failedBatchCommitRollsBackAllCategoriesAndReceiptThenRetries','expiryAfterBatchReturnPreventsCommit')) {$expectedCases.Add('org.deposit.it.Stage3ProbeIT::'+$name)}}
                elseif($selection -eq 'Stage4ProbeIT') {foreach($name in @('spatialFailuresRollbackCompleteDomainAction','geometryIndexBoundsIdentityRebuildAndNamespaceDeletion','expiryResumptionNonRenewalAndReadyLeasePlacement','exportGrantRemainsPrivateAndRechecksCurrentRole','publicationFaultsRecoverWithoutRepeatingEffects','readLeaseBlocksRelocationUntilTransactionCompletes')) {$expectedCases.Add('org.deposit.it.Stage4ProbeIT::'+$name)}}
                elseif($selection.StartsWith('Stage4CrashIT#')) {$expectedCases.Add('org.deposit.it.Stage4CrashIT::'+$selection.Split('#')[1])}
                elseif($selection.StartsWith('EnvironmentIT#')) {foreach($name in $selection.Substring(14).Split('+')) {$expectedCases.Add('org.deposit.it.EnvironmentIT::'+$name)}}
                else {throw 'Unrecognized acceptance suite'}
            }
            $reports=@(Get-ChildItem "$($phase.evidence)\junit" -Filter 'TEST-*.xml')
            if(!$reports.Count) {throw 'Required JUnit reports are missing'}
            $actualCases=New-Object System.Collections.Generic.List[string]
            foreach($report in $reports) {
                [xml]$xml=Get-Content $report.FullName -Raw
                if([int]$xml.testsuite.tests -ne @($xml.testsuite.testcase).Count -or [int]$xml.testsuite.tests -eq 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.skipped -ne 0) {throw 'JUnit evidence contains missing, failed or skipped tests'}
                foreach($case in $xml.testsuite.testcase) {
                    if($case.SelectNodes('failure|error|skipped').Count) {throw 'JUnit testcase did not pass'}
                    $actualCases.Add($case.classname+'::'+$case.name)
                    $testCases.Add(@{scenario=$scenario.name;class=$case.classname;name=$case.name;seconds=[double]$case.time;report=$report.FullName})
                }
            }
            if($actualCases.Count -ne $expectedCases.Count -or (Compare-Object $actualCases.ToArray() $expectedCases.ToArray())) {throw 'Expected JUnit cases are missing or duplicated'}
            if($phase.testSelection.Split(',') -contains 'RuntimeIT') {
                foreach($name in @('fixture-uuid-baseline.tsv','fixture-uuid-baseline.tsv.geometry','fixture-uuid-map.tsv','geometry-state.tsv','spatial-index-state.txt')) {Require-File "$($phase.evidence)\$name"}
            }
        }
    }
}
if($installs.Count -ne 3 -or !$packagePhase) {throw 'Both clean probe cycles and the normal distribution must be tested'}
Assert-Plugins $installs[0].plugins $installs[1].plugins
$normal=$installs[2]
if(@($normal.plugins | Where-Object name -like '*probe*').Count) {throw 'Normal installation contains probe artifacts'}
$run=Join-Path $root 'runs\stage0-host'
$current=Get-Content "$run\installed-artifacts.json" -Raw | ConvertFrom-Json
Assert-Plugins $normal.plugins $current.plugins
foreach($plugin in $current.plugins) {Require-File "$run\neo4j\plugins\$($plugin.name)"}
$receipt=Get-Content "$root\build-receipt.json" -Raw | ConvertFrom-Json
if($receipt.inputs -ne $inputs.sha256 -or $receipt.sourceSnapshot.inputs -ne $inputs.sha256) {throw 'Build/source receipt is stale'}
foreach($artifact in $receipt.artifacts) {Require-File (Join-Path $root $artifact.path)}
Require-File $receipt.sourceSnapshot.path
$package=Get-Content "$($packagePhase.evidence)\distribution.json" -Raw | ConvertFrom-Json
if($package.inputs -ne $inputs.sha256) {throw 'Package provenance is stale'}
Require-File $package.path
$packaged=Get-Content "$run\distribution\distribution-manifest.json" -Raw | ConvertFrom-Json
Assert-Plugins $normal.plugins $packaged.plugins
foreach($plugin in $packaged.plugins) {Require-File "$run\distribution\plugins\$($plugin.name)"}
$audit=Get-Content "$run\distribution\runtime-inventory.json" -Raw | ConvertFrom-Json
if($audit.conflicts -ne 0 -or $audit.inputs -ne $inputs.sha256) {throw 'Class audit is stale or conflicting'}
if(@(Get-ChildItem "$run\secrets" -File -Recurse).Count) {throw 'Temporary secret files remain'}
$password=[DepositCredentialStore]::Read('Deposit/Stage0/stage0-host')
try {
    foreach($file in (Get-ChildItem "$directory\phases" -File -Recurse)) {
        if($file.Extension -in @('.log','.json','.xml','.txt','.diff','.tsv','.conf','.properties') -and [IO.File]::ReadAllText($file.FullName).Contains($password)) {throw "Credential leaked into evidence: $($file.FullName)"}
        $files.Add(@{path=$file.FullName})
    }
} finally {$password=$null}
$live=Get-Content "$root\evidence\$GateName-live\result.json" -Raw | ConvertFrom-Json
if($live.status -ne 'passed' -or $live.inputs -ne $inputs.sha256 -or !$live.sessionId) {throw 'Real CLI interruption/resumption evidence for these inputs is required'}
if($Accept) {
    if(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains($root) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)'}) {throw 'Stage 0 Neo4j processes remain running'}
}
$checkpoint=@{stage=0;status=$(if($Accept){'accepted'}else{'suite-passed-acceptance-pending'});sessionId=$env:CODEX_THREAD_ID;inputs=$inputs.sha256;serverBaseline=$inputs.serverBaseline;hubBaseline=$inputs.hubBaseline;testedState='uncommitted-working-tree';gate=$GateName;runner=$live;scenarios=$gate.results;testCases=$testCases.ToArray();artifactAudit="$run\distribution\runtime-inventory.json";updated=[DateTime]::UtcNow.ToString('o');pending=@('Later-stage A25 domain CRUD/history/import/export/browser scenarios','Stage 11 release/recovery acceptance on this existing host');nextAction=$(if($Accept){'Stage 0 accepted; proceed to Stage 1 only when requested'}else{'Collect final results with -Accept; no Git commit is required'})}
$utf8=New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllText("$directory\evidence-paths.json",($files.ToArray() | ConvertTo-Json -Depth 6),$utf8)
[IO.File]::WriteAllText("$directory\junit-results.json",($testCases.ToArray() | ConvertTo-Json -Depth 6),$utf8)
$checkpointPath=if($CumulativeOnly) {"$directory\cumulative-checkpoint.json"} else {"$root\stage0-checkpoint.json"}
$temporary=$checkpointPath+'.partial'
[IO.File]::WriteAllText($temporary,($checkpoint | ConvertTo-Json -Depth 12),$utf8)
if(Test-Path -LiteralPath $checkpointPath) {[IO.File]::Replace($temporary,$checkpointPath,$checkpointPath+'.previous')} else {[IO.File]::Move($temporary,$checkpointPath)}
$report=@("# Stage 0: $($checkpoint.status)","","Neo4j/APOC 2026.05.0; Spatial 2025.10.0 thin; GeoTools 33.2; JTS 1.20.0; Temurin 21.0.12.1+1; Cypher 25.","","Server baseline: $($checkpoint.serverBaseline)","Hub baseline: $($checkpoint.hubBaseline)","Tested working-tree SHA-256: $($inputs.sha256)","No Git staging, commits or pushes were performed.","Live runner session: $($live.sessionId)","Gate: $GateName; scenarios: $($gate.results.Count); JUnit cases: $($testCases.Count); no failed/skipped cases.","","| Scenario | Result | Seconds |","| --- | --- | ---: |")
$report+=@($gate.results | ForEach-Object {"| $($_.name) | $($_.status) | $([Math]::Round($_.seconds,2)) |"})
$report+=@("","Later-stage A25 scenarios and Stage 11 release/recovery acceptance on this existing host remain pending. No clean-machine or cross-host validation is claimed.","","Rerun: .\scripts\Invoke-Stage0Gate.ps1 -RunPrefix <fresh-id> -Port $($gate.port)")
[IO.File]::WriteAllLines("$directory\REPORT.md",[string[]]$report,$utf8)
Write-Output "Stage 0 checkpoint: $($checkpoint.status); $($testCases.Count) verified JUnit cases. $directory\REPORT.md"
