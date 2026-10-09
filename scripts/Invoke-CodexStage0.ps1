[CmdletBinding()]
param(
    [ValidateSet(0,1,2)][int]$Stage=0,
    [ValidateSet('Work','Resume','Review')][string]$Mode='Review',
    [Parameter(Mandatory=$true)][string]$WorkPackage,
    [string]$Root='C:\data_processed\Deposit_v2\stage0',
    [string]$InputsFile,
    [ValidatePattern('^[a-z0-9-]+$')][string]$RepairCause,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Name='stage0'
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Inputs.ps1"
$repo=Split-Path $PSScriptRoot -Parent
if ([IO.Path]::GetFullPath($Root).TrimEnd('\') -ne 'C:\data_processed\Deposit_v2\stage0') {throw 'Use the approved Stage 0 root'}
$directory=Join-Path $Root "runner\$Name"
New-Item -ItemType Directory -Force $directory | Out-Null
$stateFile=Join-Path $directory 'state.json'
$countsFile=Join-Path $Root 'runner\repair-counts.json'
function Save-State($State) {
    $temp="$stateFile.partial"
    [IO.File]::WriteAllText($temp,($State | ConvertTo-Json -Depth 8))
    if(Test-Path $stateFile) {[IO.File]::Replace($temp,$stateFile,"$stateFile.previous")} else {[IO.File]::Move($temp,$stateFile)}
}
$lock=[IO.File]::Open((Join-Path $Root 'runner\writer.lock'),'OpenOrCreate','ReadWrite','None')
try {
    $inputs=if($InputsFile -and $Mode -ne 'Resume') {Get-Content -LiteralPath $InputsFile -Raw | ConvertFrom-Json} else {Get-Stage0Inputs $repo}
    $workPackageHash=(Get-FileHash -LiteralPath $WorkPackage -Algorithm SHA256).Hash
    $counts=@{}
    if(Test-Path $countsFile) {(Get-Content $countsFile -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object {$counts[$_.Name]=[int]$_.Value}}
    if($Mode -eq 'Work' -and !$RepairCause) {throw 'Work requires a stable RepairCause, shared across names and attempts'}
    if($Mode -ne 'Review' -and $RepairCause -and $counts.ContainsKey($RepairCause) -and $counts[$RepairCause] -ge 3) {throw 'Three failed repairs of this cause require reassessment'}
    $previous=$null
    if (Test-Path $stateFile) {$previous=Get-Content $stateFile -Raw | ConvertFrom-Json}
    if ($Mode -eq 'Resume') {
        if (!$previous -or !$previous.sessionId) {throw 'An explicit saved session ID is required'}
        $savedStage=if($previous.PSObject.Properties['stage']) {[int]$previous.stage} else {0}
        if($PSBoundParameters.ContainsKey('Stage') -and $Stage -ne $savedStage) {throw 'Cannot change stage when resuming'}
        $Stage=$savedStage
        if ($previous.inputs -ne $inputs.sha256) {throw 'Inputs changed: use a new work package/name; stale evidence cannot be resumed'}
        if (!$previous.PSObject.Properties['workPackageHash'] -or $previous.workPackageHash -ne $workPackageHash) {throw 'Work package changed or is unbound; start a new work package/name'}
        if ($previous.PSObject.Properties['failureCause'] -and $previous.failureCause -and $counts.ContainsKey($previous.failureCause) -and $counts[$previous.failureCause] -ge 3) {throw 'Three failures of the same cause require reassessment'}
        if(!$previous.PSObject.Properties['repairCause'] -or !$previous.repairCause) {throw 'Saved session has no bound repair cause; start an explicitly identified work package'}
        if($RepairCause -and $RepairCause -ne $previous.repairCause) {throw 'Cannot change the repair cause when resuming'}
        $RepairCause=$previous.repairCause
        if($counts.ContainsKey($RepairCause) -and $counts[$RepairCause] -ge 3) {throw 'Three failed repairs of this cause require reassessment'}
    }
    $attempt=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
    $events=Join-Path $directory "$attempt.jsonl"
    $errors=Join-Path $directory "$attempt.stderr.log"
    $resultFile=Join-Path $directory "$attempt.result.json"
    $prompt=Get-Content -LiteralPath $WorkPackage -Raw
    $prompt="Stage $Stage only. Perform no Git operations. Use filesystem source fingerprints. Do not change the pinned runtime or port Spatial. Stop on compatibility blockers. Keep secrets out of logs. Work package:`n"+$prompt
    $prompt+="`nReport failed tests or unresolved blockers as status failed/blocked, even if you completed your agent turn. failureCause must be a stable lowercase hyphenated cause identifier, independent of timestamps/log wording. Use null only when completed. Expected repair cause: $RepairCause."
    $arguments=@('exec','--json','--sandbox',$(if($Mode -eq 'Review'){'read-only'}else{'workspace-write'}),'-C',$repo,'--output-schema',"$repo\runtime\runner-result.schema.json",'--output-last-message',$resultFile)
    if ($Mode -ne 'Review') {$arguments+=@('--add-dir',$Root,'--add-dir',"$repo\..\deposit-hub")}
    if ($Mode -eq 'Resume') {$arguments+=@('resume',$previous.sessionId,'-')}
    else {$arguments+='-'}
    if ($Mode -eq 'Review') {$prompt="Read-only review. Do not modify files. Inspect the implementation and available evidence; report concrete defects and pending acceptance. `n"+$prompt}
    $state=@{stage=$Stage;sessionId=$null;inputs=$inputs.sha256;workPackageHash=$workPackageHash;mode=$Mode;status='running';events=$events;repairCause=$RepairCause;failureCause=$null;nextAction='Resume the explicit session after revalidating inputs'}
    if($Mode -eq 'Resume') {$state.sessionId=$previous.sessionId}
    Save-State $state
    $eventWriter=New-Object IO.StreamWriter($events,$false,(New-Object Text.UTF8Encoding($false)))
    $eventWriter.AutoFlush=$true
    $ErrorActionPreference='Continue'
    try {
        & { $prompt | & codex @arguments 2> $errors; $script:codexExitCode=$LASTEXITCODE } | ForEach-Object {
            $eventWriter.WriteLine($_)
            try {
                $event=$_ | ConvertFrom-Json
                if($event.type -eq 'thread.started') {$state.sessionId=$event.thread_id; Save-State $state}
                if($event.type -eq 'item.completed') {Save-State $state}
            } catch { $eventWriter.WriteLine('{"checkpointWarning":"Could not checkpoint event; inspect inputs before resuming"}') }
        }
    } finally {$eventWriter.Dispose()}
    $exitCode=$script:codexExitCode
    $ErrorActionPreference='Stop'
    $after=Get-Stage0Inputs $repo
    if ($Mode -eq 'Review' -and $after.sha256 -ne $inputs.sha256) {throw 'Read-only review changed inputs'}
    $result=@{status='failed';failureCause='codex-process-failure';summary='No structured result'}
    if($exitCode -eq 0 -and (Test-Path $resultFile)) {$result=Get-Content $resultFile -Raw | ConvertFrom-Json}
    if($result.status -ne 'completed') {
        $cause=$result.failureCause
        if(!$cause -or $cause -notmatch '^[a-z0-9-]+$') {$cause='invalid-agent-result'}
        if($RepairCause) {$cause=$RepairCause}
        if($Mode -ne 'Review') {
            if(!$counts.ContainsKey($cause)) {$counts[$cause]=0}
            $counts[$cause]++
            $temporary=$countsFile+'.'+[Guid]::NewGuid().ToString('N')+'.partial'
            [IO.File]::WriteAllText($temporary,($counts | ConvertTo-Json))
            if(Test-Path $countsFile) {[IO.File]::Replace($temporary,$countsFile,"$countsFile.previous")} else {[IO.File]::Move($temporary,$countsFile)}
        }
        $state.failureCause=$cause
    }
    $state.inputs=$after.sha256; $state.status=$result.status; $state.exitCode=$exitCode
    $state.nextAction='Inspect the structured result and scenario evidence; no automatic stage acceptance'
    Save-State $state
    if($exitCode -ne 0 -or $result.status -ne 'completed') {throw "Codex work package did not complete; see $resultFile and saved state"}
} finally {$lock.Dispose()}
