[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]{0,30}$')][string]$RunPrefix=('stage2-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')),
    [ValidateRange(1024,65534)][int]$Port=17770
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Inputs.ps1"
$root='C:\data_processed\Deposit_v2\stage0'; $repo=Split-Path $PSScriptRoot -Parent
$accepted=Get-Content "$root\stage0-checkpoint.json" -Raw | ConvertFrom-Json
if($accepted.status -ne 'accepted') {throw 'Accepted Stage 0 prerequisite required'}
$priorStage1=Get-Content "$root\stage1-checkpoint.json" -Raw | ConvertFrom-Json
if($priorStage1.status -ne 'accepted') {throw 'Accepted Stage 1 prerequisite required'}
$stage1Hash=(Get-FileHash "$root\stage1-checkpoint.json" -Algorithm SHA256).Hash
$priorHash=(Get-FileHash "$root\stage0-checkpoint.json" -Algorithm SHA256).Hash
$directory=Join-Path $root "evidence\$RunPrefix"
if(Test-Path -LiteralPath $directory) {throw 'Use a fresh evidence prefix'}
New-Item -ItemType Directory -Path $directory | Out-Null
$inputs=Get-Stage0Inputs $repo
$inputsFile=Join-Path $directory 'source-inputs.json'
$inputs|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $inputsFile -Encoding UTF8
$snapshot=Save-Stage0SourceSnapshot $repo $root $inputs
$phases=New-Object System.Collections.Generic.List[object]
$checkpoint=@{stage=2;status='running';inputs=$inputs.sha256;sourceSnapshot=$snapshot;gate=$RunPrefix;prerequisite=$priorHash;stage1Prerequisite=$stage1Hash;sessionId=$env:CODEX_THREAD_ID;nextAction='Inspect saved phases and owned process before resumption';phases=@();pending=@('Stage 3 batches and broader model coverage','Stage 4 geometry/resources','Stage 5 history','Later query/exchange/import/browser/release stages')}
function Save-Checkpoint {
    $checkpoint.phases=$phases.ToArray();$checkpoint.updated=[DateTime]::UtcNow.ToString('o')
    $path="$root\stage2-checkpoint.json";$temporary=$path+'.partial'
    [IO.File]::WriteAllText($temporary,($checkpoint|ConvertTo-Json -Depth 15),(New-Object Text.UTF8Encoding($false)))
    if(Test-Path $path){[IO.File]::Replace($temporary,$path,$path+'.previous')}else{[IO.File]::Move($temporary,$path)}
    Copy-Item -LiteralPath $path -Destination "$directory\checkpoint.json" -Force
}
function Phase([string]$Name,[hashtable]$Options=@{}) {
    Save-Checkpoint
    try {& "$PSScriptRoot\Stage0.ps1" -Phase $Name -RunId stage0-host -Port $Port -EvidenceGroup $RunPrefix -InputsFile $inputsFile @Options}
    finally {
        $phase=Get-Content "$root\runs\stage0-host\checkpoint.json" -Raw|ConvertFrom-Json
        $phases.Add($phase);Save-Checkpoint
    }
}
Save-Checkpoint
try {
    & "$PSScriptRoot\Test-Stage1Inputs.ps1" | Tee-Object -FilePath "$directory\input-checks.log"
    $checkpoint.inputChecks="$directory\input-checks.log";Save-Checkpoint
    Phase Provision
    $checkpoint.nextAction='Run cumulative Stage 0 environment and runner checks';Save-Checkpoint
    & "$PSScriptRoot\Test-Stage0Environment.ps1" -Build -Stage2Checks -RunPrefix ($RunPrefix+'-runtime') -Port $Port -InputsFile $inputsFile
    & "$PSScriptRoot\Test-LiveRunner.ps1" -Name ($RunPrefix+'-runtime-live') -InputsFile $inputsFile
    & "$PSScriptRoot\Collect-Stage0.ps1" -GateName ($RunPrefix+'-runtime') -Accept -CumulativeOnly
    # The cumulative suite leaves the normal distribution installed and stopped.
    $checkpoint.nextAction='Run Stage 1 and Stage 2 public contracts against normal distribution';Save-Checkpoint
    Phase Start
    Phase Test @{TestSelection='Stage1LifecycleIT'}
    Phase Test @{TestSelection='Stage1CoreIT'}
    Phase Test @{TestSelection='Stage2CoreIT'}
    $checkpoint.nextAction='Verify graceful restart and real process-termination receipt recovery';Save-Checkpoint
    Phase Stop;Phase Start;Phase Test @{TestSelection='Stage1RecoveryIT,Stage2RecoveryIT'}
    Phase Kill;Phase Start;Phase Test @{TestSelection='Stage1RecoveryIT,Stage2RecoveryIT'}
    Phase Stop
    $credential=Join-Path $root 'runs\stage0-host\secrets\stage1-session.txt'
    if(Test-Path -LiteralPath $credential) {Remove-Item -LiteralPath $credential}
    if((Get-FileHash "$root\stage1-checkpoint.json" -Algorithm SHA256).Hash -ne $stage1Hash){throw 'Accepted Stage 1 checkpoint changed'}
    if((Get-Stage0Inputs $repo).sha256 -ne $inputs.sha256){throw 'Sources changed during gate'}
    if((Get-FileHash "$root\stage0-checkpoint.json" -Algorithm SHA256).Hash -ne $priorHash){throw 'Accepted Stage 0 checkpoint changed'}
    foreach($phase in $phases) {if($phase.status -ne 'passed' -or $phase.inputs -ne $inputs.sha256 -or $phase.diagnostic -or $phase.readinessBypassed){throw 'Incomplete or diagnostic phase cannot establish acceptance'}}
    $cases=New-Object System.Collections.Generic.List[object]
    foreach($phase in @($phases|Where-Object phase -eq 'Test')) {
        $reports=@(Get-ChildItem (Join-Path $phase.evidence 'junit') -Filter 'TEST-*.xml')
        if(!$reports.Count){throw 'Missing JUnit evidence'}
        foreach($report in $reports) {
            [xml]$xml=Get-Content -LiteralPath $report.FullName -Raw
            if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped -or [int]$xml.testsuite.tests -lt 1){throw 'Missing, skipped or failed acceptance cases'}
            foreach($case in $xml.testsuite.testcase){$cases.Add(@{class=$case.classname;name=$case.name;seconds=$case.time;report=$report.FullName})}
        }
    }
    $cases.ToArray()|ConvertTo-Json -Depth 6|Set-Content "$directory\junit-results.json" -Encoding UTF8
    $expectedClasses=@{'org.deposit.it.Stage1LifecycleIT'=5;'org.deposit.it.Stage1CoreIT'=9;'org.deposit.it.Stage2CoreIT'=15;'org.deposit.it.Stage1RecoveryIT'=2;'org.deposit.it.Stage2RecoveryIT'=2}
    if($cases.Count -ne 33) {throw 'Incomplete cumulative Stage 1/2 case count'}
    foreach($class in $expectedClasses.Keys) {if(@($cases | Where-Object class -eq $class).Count -ne $expectedClasses[$class]) {throw "Incomplete case coverage: $class"}}
    $runtime=Get-Content "$root\evidence\$RunPrefix-runtime\cumulative-checkpoint.json" -Raw | ConvertFrom-Json
    if($runtime.status -ne 'accepted' -or $runtime.inputs -ne $inputs.sha256 -or @($runtime.scenarios | Where-Object name -eq 'stage2-isolated-clock-and-commit-faults').Count -ne 1) {throw 'Required Stage 2 fault/expiry evidence is missing'}
    $checkpoint.testCases=$cases.ToArray();$checkpoint.cumulative="$root\evidence\$RunPrefix-runtime\cumulative-checkpoint.json"
    $checkpoint.status='accepted';$checkpoint.nextAction='User may manually commit Stage 2; Stage 3 remains pending';Save-Checkpoint
    $report=@('# Stage 2 accepted','','Source fingerprint: '+$inputs.sha256,'','Cumulative Stage 0 evidence: '+$checkpoint.cumulative,'','Stage 1/2 JUnit cases: '+$cases.Count+'; no failures, errors or skips.','','| Scenario | Seconds |','| --- | ---: |')
    $report+=@($cases|ForEach-Object {"| $($_.name) | $($_.seconds) |"})
    $report+=@('','Deployed total: '+($cases.Count+$runtime.testCases.Count)+'; runtime/isolated cases: '+$runtime.testCases.Count+'; cumulative environment scenarios: '+$runtime.scenarios.Count+'.','', 'Later-dependent portions of A01-A05, A24, A26, A27 and A30 remain pending. No complete acceptance ID is claimed.','','Rerun: scripts/Invoke-Stage2Gate.ps1 -RunPrefix <fresh-id> -Port '+$Port)
    [IO.File]::WriteAllLines("$directory\REPORT.md",[string[]]$report,(New-Object Text.UTF8Encoding($false)))
    Write-Output "Stage 2 accepted: $directory\REPORT.md"
} catch {
    $checkpoint.status='failed';$checkpoint.failure=$_.Exception.Message;$checkpoint.nextAction='Repair the recorded failure, reassess after three same-cause failures, then run with a fresh prefix';Save-Checkpoint
    throw
} finally {
    if($checkpoint.status -ne 'accepted') {try {Phase Stop} catch {Write-Warning 'Inspect the owned server process; stop failed'}}
    $credential=Join-Path $root 'runs\stage0-host\secrets\stage1-session.txt'
    if(Test-Path -LiteralPath $credential) {Remove-Item -LiteralPath $credential}
}
