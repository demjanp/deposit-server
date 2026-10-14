[CmdletBinding()]
param([ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Name=('stage0-live-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')),[string]$InputsFile)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Inputs.ps1"
$root='C:\data_processed\Deposit_v2\stage0'; $repo=Split-Path $PSScriptRoot -Parent
$cleanupBarrier=Enter-Stage0CleanupBarrier $root
try {
$resultOutcome='failed'
$inputs=if($InputsFile) {Get-Content -LiteralPath $InputsFile -Raw | ConvertFrom-Json} else {Get-Stage0Inputs $repo}
$evidence=Join-Path $root "evidence\$Name"
if(Test-Path -LiteralPath $evidence) {throw 'Use a fresh live-runner evidence name'}
$ownerResultId=$null
if($Name -match '^(?<owner>.+)-runtime-live$') {$ownerResultId=$Matches.owner} elseif($Name -match '^(?<owner>.+)-live$') {$ownerResultId=$Matches.owner}
$childOwnerId=if($ownerResultId){$ownerResultId}else{$Name}
Start-Stage0Result $root $evidence $Name 'live-runner' $inputs.sha256 @() $ownerResultId | Out-Null
$package=& "$PSScriptRoot\Export-WorkPackage.ps1" -Section RunnerSmoke -Name $Name
$manifest=Get-Content -LiteralPath (Join-Path $evidence 'result-manifest.json') -Raw | ConvertFrom-Json -ErrorAction Stop
$manifest.requiredReferences+=@("runner\work-packages\$Name\RunnerSmoke.md")
Write-Stage0ResultManifest $root $evidence $manifest | Out-Null
} catch {$cleanupBarrier.Dispose();throw}
$launcher=$null
try {
$marker=Join-Path $root 'runner-live-marker.json'
if(Test-Path -LiteralPath $marker) {Move-Item -LiteralPath $marker -Destination ($marker+'.'+[Guid]::NewGuid().ToString('N')+'.previous')}
New-Item -ItemType Directory -Force $evidence | Out-Null
$runner=Join-Path $PSScriptRoot 'Invoke-CodexStage0.ps1'
$command="& '$($runner.Replace("'","''"))' -Mode Work -Name '$Name' -RepairCause '$Name' -WorkPackage '$($package.Replace("'","''"))' -OwnerResultId '$childOwnerId'"
$command+=" -CapturedInputsHash '$($inputs.sha256)'"
if($InputsFile) {$command+=" -InputsFile '$($InputsFile.Replace("'","''"))'"}
$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
$launcher=Start-Process powershell.exe -ArgumentList '-NoProfile','-EncodedCommand',$encoded -WindowStyle Hidden -PassThru -RedirectStandardOutput "$evidence\work.out.log" -RedirectStandardError "$evidence\work.err.log"
[void]$launcher.Handle
$stateFile=Join-Path $root "runner\$Name\state.json"
$deadline=[DateTime]::UtcNow.AddSeconds(180)
    while(!(Test-Path -LiteralPath $marker)) {
        if($launcher.HasExited) {throw "Live runner exited before its interruption marker; inspect $evidence"}
        if([DateTime]::UtcNow -ge $deadline) {throw 'Live runner did not reach its interruption marker within 180 seconds'}
        Start-Sleep -Milliseconds 250
    }
    $before=Get-Content $stateFile -Raw | ConvertFrom-Json
    if(!$before.sessionId -or $before.status -ne 'running') {throw 'Session was not checkpointed before interruption'}
    $owned=@(Get-CimInstance Win32_Process -Filter "Name='codex.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains("$root\runner\$Name\") -and $_.ParentProcessId -eq $launcher.Id -and $_.CreationDate -ge $launcher.StartTime.AddSeconds(-1)
    })
    if($owned.Count -ne 1) {throw 'Cannot establish a unique owned test CLI process'}
    $handle=Get-Process -Id $owned[0].ProcessId; [void]$handle.Handle
    try {Stop-Process -Id $owned[0].ProcessId -Force; if(!$handle.WaitForExit(10000)) {throw 'Owned test CLI did not exit'}} finally {$handle.Dispose()}
    if(!$launcher.WaitForExit(30000)) {throw 'Interrupted runner wrapper did not finish'}
    $interrupted=Get-Content $stateFile -Raw | ConvertFrom-Json
    if($interrupted.sessionId -ne $before.sessionId) {throw 'Interrupted session identity was lost'}
    & $runner -Mode Resume -Name $Name -WorkPackage $package -OwnerResultId $childOwnerId
    $after=Get-Content $stateFile -Raw | ConvertFrom-Json
    $result=Get-Content $marker -Raw | ConvertFrom-Json
    if($after.capturedInputs -ne $inputs.sha256 -or $after.inputs -ne $inputs.sha256 -or $after.observedInputs -ne $inputs.sha256) {throw "Live-runner input identity mismatch: expected $($inputs.sha256); captured=$($after.capturedInputs), saved=$($after.inputs), observed=$($after.observedInputs)"}
    if($after.sessionId -ne $before.sessionId) {throw "Live-runner resumed a different session: before=$($before.sessionId), after=$($after.sessionId)"}
    if($after.status -ne 'completed') {throw "Live-runner session did not complete: status=$($after.status), exitCode=$($after.exitCode), failureCause=$($after.failureCause)"}
    if($result.phase -ne 'resumed') {throw "Live-runner marker has wrong phase: expected resumed, observed $($result.phase)"}
    $proof=@{status='passed';inputs=$inputs.sha256;sessionId=$after.sessionId;runner=$Name;interruptedProcessId=$owned[0].ProcessId;interruptedCreated=$owned[0].CreationDate.ToUniversalTime().ToString('o');workPackageHash=(Get-FileHash $package -Algorithm SHA256).Hash;updated=[DateTime]::UtcNow.ToString('o')}
    $proof | ConvertTo-Json | Set-Content "$evidence\result.json" -Encoding UTF8
    $proof | ConvertTo-Json | Set-Content "$root\runner-live-acceptance.json" -Encoding UTF8
    $resultOutcome='passed'
    Write-Output "PASS: real CLI interruption and explicit session resumption ($($after.sessionId))"
} finally {
    try {
    if($launcher -and !$launcher.HasExited) {
        $ownedChildren=@(Get-CimInstance Win32_Process -Filter "Name='codex.exe'" | Where-Object {
            $_.ParentProcessId -eq $launcher.Id -and $_.CommandLine -and $_.CommandLine.Contains("$root\runner\$Name\")
        })
        foreach($child in $ownedChildren) {Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue}
        Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue
        try {$launcher.WaitForExit(10000) | Out-Null} catch {}
    }
    $manifestPath=Join-Path $evidence 'result-manifest.json'
    if(Test-Path -LiteralPath $manifestPath) {
        $manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -ErrorAction Stop
        if(Test-Path -LiteralPath (Join-Path $root "runner\$Name") -PathType Container) {$manifest.requiredReferences+=@("runner\$Name")}
        Write-Stage0ResultManifest $root $evidence $manifest | Out-Null
    }
    Complete-Stage0Result $root $evidence $resultOutcome $inputs.sha256
    } finally {if($launcher){$launcher.Dispose()};$cleanupBarrier.Dispose()}
}
