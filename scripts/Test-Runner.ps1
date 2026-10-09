# Exercises checkpoint/failure policy with a deterministic fake CLI; makes no model calls.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root='C:\data_processed\Deposit_v2\stage0'
$id='runner-test-'+[Guid]::NewGuid().ToString('N')
$workPackage=Join-Path $root "$id.txt"
[IO.File]::WriteAllText($workPackage,'Runner lifecycle fixture')
$global:fakeCalls=0
$global:fakeMode='blocked'
$global:fakeSession=[Guid]::NewGuid().ToString()
function global:codex {
    $global:fakeCalls++
    $arguments=@($args)
    $global:fakeArguments=$arguments
    $output=$arguments[[Array]::IndexOf($arguments,'--output-last-message')+1]
    @{type='thread.started';thread_id=$global:fakeSession} | ConvertTo-Json -Compress | Write-Output
    if($global:fakeMode -eq 'interrupt') {throw 'Simulated interruption after session creation'}
    @{status=$(if($global:fakeMode -eq 'completed'){'completed'}else{'blocked'});failureCause=$(if($global:fakeMode -eq 'completed'){$null}else{'runner-test-blocker'});summary='Deterministic fixture'} | ConvertTo-Json | Set-Content $output
    $global:LASTEXITCODE=0
}
try {
    $cause=$id
    for($attempt=1;$attempt -le 3;$attempt++) {
        try {& "$PSScriptRoot\Invoke-CodexStage0.ps1" -Mode Work -Name $id -RepairCause $cause -WorkPackage $workPackage; throw 'Expected blocked result'}
        catch {if($_.Exception.Message -notlike 'Codex work package did not complete*') {throw}}
    }
    try {& "$PSScriptRoot\Invoke-CodexStage0.ps1" -Mode Work -Name $id -RepairCause $cause -WorkPackage $workPackage; throw 'Expected repair limit'}
    catch {if($_.Exception.Message -notlike 'Three failed repairs*') {throw}}
    if($global:fakeCalls -ne 3) {throw 'Fourth repair unexpectedly invoked Codex'}
    $global:fakeMode='interrupt'
    $interrupted=$id+'-interrupt'
    try {& "$PSScriptRoot\Invoke-CodexStage0.ps1" -Mode Work -Name $interrupted -RepairCause $interrupted -WorkPackage $workPackage}
    catch {if($_.Exception.Message -ne 'Simulated interruption after session creation') {throw}}
    $state=Get-Content "$root\runner\$interrupted\state.json" -Raw | ConvertFrom-Json
    if($state.sessionId -ne $global:fakeSession -or $state.status -ne 'running') {throw 'Interrupted session was not durably checkpointed'}
    $global:fakeMode='completed'
    & "$PSScriptRoot\Invoke-CodexStage0.ps1" -Mode Resume -Name $interrupted -WorkPackage $workPackage
    if($global:fakeArguments -notcontains 'resume' -or $global:fakeArguments -notcontains $global:fakeSession) {throw 'Resume did not use the explicit saved session'}
    try {& "$PSScriptRoot\Invoke-CodexStage0.ps1" -Mode Work -Name ($id+'-bypass') -WorkPackage $workPackage; throw 'Expected cause requirement'}
    catch {if($_.Exception.Message -notlike 'Work requires a stable RepairCause*') {throw}}
    [IO.File]::WriteAllText($workPackage,'Changed work package')
    try {& "$PSScriptRoot\Invoke-CodexStage0.ps1" -Mode Resume -Name $interrupted -WorkPackage $workPackage; throw 'Expected stale work package rejection'}
    catch {if($_.Exception.Message -notlike 'Work package changed*') {throw}}
    [IO.File]::WriteAllText($workPackage,'Runner lifecycle fixture')
    $state.inputs='stale'
    $state | ConvertTo-Json | Set-Content "$root\runner\$interrupted\state.json"
    try {& "$PSScriptRoot\Invoke-CodexStage0.ps1" -Mode Resume -Name $interrupted -WorkPackage $workPackage; throw 'Expected stale input rejection'}
    catch {if($_.Exception.Message -notlike 'Inputs changed*') {throw}}
    Write-Output "PASS: repair limit, explicit interruption/resume, required stable cause, changed work package and stale inputs ($id)"
} finally {
    Remove-Item Function:\codex
    Remove-Variable fakeCalls,fakeMode,fakeSession,fakeArguments -Scope Global
}
