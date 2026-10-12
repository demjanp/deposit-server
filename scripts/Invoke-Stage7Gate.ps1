[CmdletBinding()]
param(
  [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$RunPrefix=('stage7-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')),
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
if(Test-Path -LiteralPath $evidence){throw 'Use a fresh Stage 7 run prefix'}
Start-Stage0Result $root $evidence $RunPrefix 'gate' $null | Out-Null
$ownsGateResult=$true
$prerequisite=Get-Content "$root\stage65-checkpoint.json" -Raw | ConvertFrom-Json
if($prerequisite.status -ne 'accepted'){throw 'Accepted Stage 6.5 prerequisite required'}
$acceptedStages=@('0','1','2','3','4','5','5.5','6')
$prerequisiteHashes=@{}
foreach($stage in $acceptedStages){$path="$root\stage$stage-checkpoint.json";$checkpoint=Get-Content $path -Raw|ConvertFrom-Json;if($checkpoint.status -ne 'accepted'){throw "Accepted Stage $stage prerequisite required"};$prerequisiteHashes[$stage]=(Get-FileHash $path -Algorithm SHA256).Hash}
$stage65Path="$root\stage65-checkpoint.json";$stage65Hash=(Get-FileHash $stage65Path -Algorithm SHA256).Hash
$inputs=Get-Stage0Inputs $repo
$inputsFile=Join-Path $evidence 'source-inputs.json';$inputs|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $inputsFile -Encoding UTF8
$snapshot=Save-Stage0SourceSnapshot $repo $root $inputs
$checkpointPath="$root\stage7-checkpoint.json"
$run=@{stage=7;gate=$RunPrefix;rerun='scripts/Invoke-Stage7Gate.ps1 -RunPrefix <fresh-id> -Port '+$Port;status='running';accepted=$false;sourceIdentity=$inputs.sha256;sourceSnapshot=$snapshot;stage65CheckpointSha256=$stage65Hash;prerequisiteCheckpoints=$prerequisiteHashes;phases=@();pending=@('Pattern and compatibility matrix','Binding and search limits/pages','Pattern history conflicts and restore','Snapshot races and resource integrity','Scope boundaries, geometry, and User Tool rules','Malformed/oversized package rejection','Cumulative recovery and crash checks','Independent package validation')}
function Save-Checkpoint { $run.updated=[DateTime]::UtcNow.ToString('o');$tmp=$checkpointPath+'.partial';[IO.File]::WriteAllText($tmp,($run|ConvertTo-Json -Depth 12),(New-Object Text.UTF8Encoding($false)));if(Test-Path $checkpointPath){[IO.File]::Replace($tmp,$checkpointPath,$checkpointPath+'.previous')}else{[IO.File]::Move($tmp,$checkpointPath)};Copy-Item $checkpointPath (Join-Path $evidence 'checkpoint.json') -Force }
function Clear-TestSessions {
  foreach($name in @('stage1-session.txt','stage55-session.txt')){ $credential=Join-Path $root ('runs\stage0-host\secrets\'+$name);if(Test-Path -LiteralPath $credential){Remove-Item -LiteralPath $credential} }
}
function Phase([string]$Name,[hashtable]$Options=@{}) {
  try { & "$PSScriptRoot\Stage0.ps1" -Phase $Name -RunId stage0-host -Port $Port -EvidenceGroup $RunPrefix -InputsFile $inputsFile @Options }
  finally {
    $phase=Get-Content "$root\runs\stage0-host\checkpoint.json" -Raw|ConvertFrom-Json
    $run.phases+=@($phase);Save-Checkpoint
  }
  if($phase.phase -ne $Name -or $phase.status -ne 'passed'){throw "Stage 0 phase failed: $Name"}
}
Save-Checkpoint
$package=Join-Path $evidence 'generated-namespace.zip'
try {
  Phase Stop
  Clear-TestSessions
  Phase Provision
  & "$PSScriptRoot\Test-Stage0Environment.ps1" -Build -Stage2Checks -Stage3Checks -Stage4Checks -Stage5Checks -Stage55Checks -RunPrefix ($RunPrefix+'-runtime') -Port $Port -InputsFile $inputsFile
  Phase Stop
  Clear-TestSessions
  Phase Start
  foreach($suite in @('Stage1LifecycleIT','Stage1CoreIT','Stage2CoreIT','Stage3CoreIT','Stage4CoreIT','Stage5CoreIT','Stage55CoreIT','Stage6CoreIT','Stage65CoreIT')){Phase Test @{TestSelection=$suite}}
  $env:STAGE7_PACKAGE_OUTPUT=$package
  Phase Test @{TestSelection='Stage7CoreIT'}
  Remove-Item Env:\STAGE7_PACKAGE_OUTPUT -ErrorAction SilentlyContinue
  & python "$PSScriptRoot\Test-Stage7Package.py" $package | Tee-Object -FilePath (Join-Path $evidence 'independent-package-validation.log')
  if($LASTEXITCODE -ne 0){throw 'Independent package validation failed'}
  if(!(Test-Path -LiteralPath $package)){throw 'Stage 7 integration suite did not retain its package artifact'}
  & python "$PSScriptRoot\Test-Stage7Package.py" (Join-Path $evidence 'generated-subgraph.zip') | Tee-Object -FilePath (Join-Path $evidence 'independent-subgraph-validation.log')
  if($LASTEXITCODE -ne 0){throw 'Independent subgraph/history validation failed'}
  foreach($suite in @('Stage55RecoveryIT#seed','Stage6RecoveryIT#seed','Stage65RecoveryIT#seed','Stage7RecoveryIT#seed')){Phase Test @{TestSelection=$suite}}
  $recovery='Stage1RecoveryIT,Stage2RecoveryIT,Stage3RecoveryIT,Stage4RecoveryIT,Stage5RecoveryIT,Stage55RecoveryIT#verify,Stage6RecoveryIT#verify,Stage65RecoveryIT#verify,Stage7RecoveryIT#verify'
  Phase Stop;Phase Start;Phase Test @{TestSelection=$recovery}
  Phase Kill;Phase Start;Phase Test @{TestSelection=$recovery};Phase Stop;Clear-TestSessions
  $cases=@()
  foreach($phase in $run.phases){
    if($phase.status -ne 'passed' -or $phase.inputs -ne $inputs.sha256 -or $phase.diagnostic -or $phase.readinessBypassed){throw 'Incomplete or diagnostic phase evidence'}
    if($phase.phase -eq 'Test'){
      $reports=@(Get-ChildItem (Join-Path $phase.evidence 'junit') -Filter 'TEST-*.xml');if(!$reports.Count){throw 'Missing JUnit evidence'}
      foreach($report in $reports){[xml]$xml=Get-Content $report.FullName -Raw;if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped -or [int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.tests -ne @($xml.testsuite.testcase).Count){throw 'Failed/skipped tests'};foreach($case in $xml.testsuite.testcase){$cases+=@{class=$case.classname;name=$case.name;report=$report.FullName}}}
    }
  }
  $expected=@{'org.deposit.it.Stage1LifecycleIT'=5;'org.deposit.it.Stage1CoreIT'=9;'org.deposit.it.Stage2CoreIT'=15;'org.deposit.it.Stage3CoreIT'=16;'org.deposit.it.Stage4CoreIT'=10;'org.deposit.it.Stage5CoreIT'=24;'org.deposit.it.Stage55CoreIT'=5;'org.deposit.it.Stage6CoreIT'=18;'org.deposit.it.Stage65CoreIT'=26;'org.deposit.it.Stage65RecoveryIT'=3;'org.deposit.it.Stage6RecoveryIT'=3;'org.deposit.it.Stage55RecoveryIT'=3;'org.deposit.it.Stage1RecoveryIT'=2;'org.deposit.it.Stage2RecoveryIT'=2;'org.deposit.it.Stage3RecoveryIT'=2;'org.deposit.it.Stage4RecoveryIT'=2;'org.deposit.it.Stage5RecoveryIT'=2;'org.deposit.it.Stage7CoreIT'=16;'org.deposit.it.Stage7RecoveryIT'=3}
  if($cases.Count -ne 166){throw 'Incomplete cumulative Stage 1-7 coverage'}
  foreach($class in $expected.Keys){if(@($cases|Where-Object class -eq $class).Count -ne $expected[$class]){throw "Incomplete suite $class"}}
  $run.testCases=$cases;$cases|ConvertTo-Json -Depth 6|Set-Content (Join-Path $evidence 'junit-results.json') -Encoding UTF8
  & "$PSScriptRoot\Test-LiveRunner.ps1" -Name ($RunPrefix+'-runtime-live') -InputsFile $inputsFile
  & "$PSScriptRoot\Collect-Stage0.ps1" -GateName ($RunPrefix+'-runtime') -Accept -CumulativeOnly
  $runtimePath="$root\evidence\$RunPrefix-runtime\cumulative-checkpoint.json";$runtime=Get-Content $runtimePath -Raw|ConvertFrom-Json
  if($runtime.status -ne 'accepted' -or $runtime.inputs -ne $inputs.sha256 -or $runtime.testCases.Count -ne 138){throw 'Missing common cumulative completion checkpoint'}
  $run.cumulative=$runtimePath
  $units=@(Get-ChildItem "$root\evidence\$RunPrefix-runtime\phases\Build-*\unit-junit\TEST-*.xml");$unitNames=@();$unitCount=0
  foreach($report in $units){[xml]$xml=Get-Content $report.FullName -Raw;if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Failed/skipped unit tests'};$unitCount+=[int]$xml.testsuite.tests;$unitNames+=$xml.testsuite.name}
  foreach($required in @('deposit.common.ExchangeArchiveLimitsTest','deposit.common.ExchangePackageCodecTest','deposit.common.ExchangePackageStreamingTest','deposit.common.ExportRecordsTest','deposit.common.HistoryProjectionTest','deposit.common.PatternCodecTest')){if($unitNames -notcontains $required){throw "Missing unit evidence: $required"}}
  $run.unitTests=$unitCount
  if((Get-Stage0Inputs $repo).sha256 -ne $inputs.sha256){throw 'Source identity changed during gate'}
  if((Get-FileHash $stage65Path -Algorithm SHA256).Hash -ne $stage65Hash){throw 'Accepted Stage 6.5 checkpoint changed during Stage 7 gate'}
  foreach($stage in $acceptedStages){if((Get-FileHash "$root\stage$stage-checkpoint.json" -Algorithm SHA256).Hash -ne $prerequisiteHashes[$stage]){throw "Prerequisite checkpoint changed: Stage $stage"}}
  $run.status='verified';$run.cause=$null;$run.rerun='scripts/Invoke-Stage7Gate.ps1 -RunPrefix <fresh-id> -Port '+$Port
} catch { $run.status='incomplete';$run.cause=$_.Exception.Message }
finally { Remove-Item Env:\STAGE7_PACKAGE_OUTPUT -ErrorAction SilentlyContinue;try{Phase Stop;Clear-TestSessions}catch{$run.status='incomplete';$run.accepted=$false;$run.cause='Final stop failed: '+$_.Exception.Message;$run.phases+=@(@{phase='Stop';status='failed';failure=$_.Exception.Message});Save-Checkpoint} }
if($run.status -eq 'verified'){$run.status='accepted';$run.accepted=$true;$run.pending=@()}
Save-Checkpoint
if($run.status -ne 'accepted'){Write-Error "Stage 7 is incomplete; evidence: $evidence";exit 1}
} finally { try { if($ownsGateResult){Complete-Stage0GateResult $root $evidence $RunPrefix} } finally { if($gateLock){$gateLock.Dispose()}; $cleanupBarrier.Dispose() } }
if($run.status -eq 'accepted') { try { & "$PSScriptRoot\Cleanup-Stage0Evidence.ps1" } catch { Write-Warning "Stage 7 was accepted, but Stage 0 cleanup failed: $($_.Exception.Message)" } }
