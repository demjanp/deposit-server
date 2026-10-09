[CmdletBinding()]
param([ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Name=('stage0-live-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')),[string]$InputsFile)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Inputs.ps1"
$root='C:\data_processed\Deposit_v2\stage0'; $repo=Split-Path $PSScriptRoot -Parent
$package=& "$PSScriptRoot\Export-WorkPackage.ps1" -Section RunnerSmoke -Name $Name
$marker=Join-Path $root 'runner-live-marker.json'
if(Test-Path -LiteralPath $marker) {Move-Item -LiteralPath $marker -Destination ($marker+'.'+[Guid]::NewGuid().ToString('N')+'.previous')}
$inputs=if($InputsFile) {Get-Content -LiteralPath $InputsFile -Raw | ConvertFrom-Json} else {Get-Stage0Inputs $repo}
$evidence=Join-Path $root "evidence\$Name"
New-Item -ItemType Directory -Force $evidence | Out-Null
$runner=Join-Path $PSScriptRoot 'Invoke-CodexStage0.ps1'
$command="& '$($runner.Replace("'","''"))' -Mode Work -Name '$Name' -RepairCause '$Name' -WorkPackage '$($package.Replace("'","''"))'"
if($InputsFile) {$command+=" -InputsFile '$($InputsFile.Replace("'","''"))'"}
$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
$launcher=Start-Process powershell.exe -ArgumentList '-NoProfile','-EncodedCommand',$encoded -WindowStyle Hidden -PassThru -RedirectStandardOutput "$evidence\work.out.log" -RedirectStandardError "$evidence\work.err.log"
[void]$launcher.Handle
$stateFile=Join-Path $root "runner\$Name\state.json"
$deadline=[DateTime]::UtcNow.AddSeconds(180)
try {
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
    & $runner -Mode Resume -Name $Name -WorkPackage $package
    $after=Get-Content $stateFile -Raw | ConvertFrom-Json
    $result=Get-Content $marker -Raw | ConvertFrom-Json
    if($after.inputs -ne $inputs.sha256 -or $after.sessionId -ne $before.sessionId -or $after.status -ne 'completed' -or $result.phase -ne 'resumed') {throw 'The explicit interrupted session did not resume successfully'}
    $proof=@{status='passed';inputs=$inputs.sha256;sessionId=$after.sessionId;runner=$Name;interruptedProcessId=$owned[0].ProcessId;interruptedCreated=$owned[0].CreationDate.ToUniversalTime().ToString('o');workPackageHash=(Get-FileHash $package -Algorithm SHA256).Hash;updated=[DateTime]::UtcNow.ToString('o')}
    $proof | ConvertTo-Json | Set-Content "$evidence\result.json" -Encoding UTF8
    $proof | ConvertTo-Json | Set-Content "$root\runner-live-acceptance.json" -Encoding UTF8
    Write-Output "PASS: real CLI interruption and explicit session resumption ($($after.sessionId))"
} finally {$launcher.Dispose()}
