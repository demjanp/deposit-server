[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]{0,38}$')][string]$RunPrefix=('stage0-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')),
    [ValidateRange(1024,65535)][int]$Port=17770
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Inputs.ps1"
$repo=Split-Path $PSScriptRoot -Parent
$root='C:\data_processed\Deposit_v2\stage0'
$directory=Join-Path $root "evidence\$RunPrefix"
if(Test-Path "$directory\results.json") {throw 'Choose a fresh evidence prefix'}
New-Item -ItemType Directory -Force $directory | Out-Null
$lock=[IO.File]::Open("$root\gate.lock",'OpenOrCreate','ReadWrite','None')
function Save-Checkpoint($Value) {
    $path="$root\stage0-checkpoint.json"; $temporary="$path.partial"
    [IO.File]::WriteAllText($temporary,($Value | ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
    if(Test-Path $path) {[IO.File]::Replace($temporary,$path,"$path.previous")} else {[IO.File]::Move($temporary,$path)}
}
try {
    $inputs=Get-Stage0Inputs $repo
    $inputsFile=Join-Path $directory 'source-inputs.json'
    $inputs | ConvertTo-Json -Depth 8 | Set-Content $inputsFile -Encoding UTF8
    Save-Checkpoint @{stage=0;status='running';inputs=$inputs.sha256;gate=$RunPrefix;serverBaseline=$inputs.serverBaseline;hubBaseline=$inputs.hubBaseline;sessionId=$env:CODEX_THREAD_ID;nextAction='Inspect saved phases and owned process if interrupted; no Git writes'}
    & "$PSScriptRoot\Stage0.ps1" -Phase Provision -RunId stage0-host -Port $Port -EvidenceGroup $RunPrefix -InputsFile $inputsFile
    & "$PSScriptRoot\Test-Stage0Environment.ps1" -Build -RunPrefix $RunPrefix -Port $Port -InputsFile $inputsFile
    & "$PSScriptRoot\Test-LiveRunner.ps1" -Name ($RunPrefix+'-live') -InputsFile $inputsFile
    & "$PSScriptRoot\Collect-Stage0.ps1" -GateName $RunPrefix -Accept
} catch {
    Save-Checkpoint @{stage=0;status='failed';gate=$RunPrefix;failure=$_.Exception.Message;sessionId=$env:CODEX_THREAD_ID;updated=[DateTime]::UtcNow.ToString('o');nextAction='Repair the reported cause, then rerun the final suite with a fresh prefix; stop after three repairs of the same cause'}
    throw
} finally {$lock.Dispose()}
try { & "$PSScriptRoot\Cleanup-Stage0Evidence.ps1" } catch { Write-Warning "Stage 0 was accepted, but Stage 0 cleanup failed: $($_.Exception.Message)" }
