# Independent crash harness helper; accepts no arbitrary target process or path.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
$run=Get-HarnessRunRoot
$repo=Split-Path $PSScriptRoot -Parent
$statePath=Join-Path $run 'distribution\deposit.install.json'
$recordPath=Join-Path $run 'process.json'
foreach($path in @($run,(Join-Path $run 'distribution'),$statePath,$recordPath)) {
    $item=Get-Item -LiteralPath $path -Force -ErrorAction Stop
    if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refuse termination: authorized paths must not traverse reparse points'}
}
$state=Get-Content -LiteralPath $statePath -Raw|ConvertFrom-Json -ErrorAction Stop
$record=Get-Content -LiteralPath $recordPath -Raw|ConvertFrom-Json -ErrorAction Stop
$neo4jHome=[IO.Path]::GetFullPath((Join-Path $run 'neo4j')).TrimEnd('\')
$stateFields=@('mode','serviceName','serviceAccount','neo4jHome')
$recordFields=@('mode','serviceName','serviceAccount','neo4jHome','pid','startMillis','runtimeSid')
foreach($field in $stateFields){if(!$state.PSObject.Properties[$field]){throw 'Refuse termination: installation state is missing required service identity fields'}}
foreach($field in $recordFields){if(!$record.PSObject.Properties[$field]){throw 'Refuse termination: process record is missing required service identity fields'}}
if($state.mode -ne 'service' -or !$state.serviceName -or !$state.serviceAccount -or
   ![string]::Equals([IO.Path]::GetFullPath([string]$state.neo4jHome).TrimEnd('\'),$neo4jHome,[StringComparison]::OrdinalIgnoreCase) -or
   $record.mode -ne 'service' -or $record.serviceName -cne [string]$state.serviceName -or
   $record.serviceAccount -cne [string]$state.serviceAccount -or
   ![string]::Equals([IO.Path]::GetFullPath([string]$record.neo4jHome).TrimEnd('\'),$neo4jHome,[StringComparison]::OrdinalIgnoreCase) -or
   [long]$record.pid -le 0 -or [long]$record.startMillis -le 0 -or !$record.runtimeSid) {
    throw 'Refuse termination: unsupported or mismatched recorded service identity'
}
. (Join-Path $PSScriptRoot 'Request-DepositStop.ps1')
$first=Get-DepositServiceProcessIdentity $neo4jHome ([string]$state.serviceName) ([string]$state.serviceAccount) ([long]$record.pid) ([long]$record.startMillis)
if([string]$first.runtimeSid -cne [string]$record.runtimeSid){throw 'Refuse termination: service runtime SID changed'}
$live=Get-CimInstance Win32_Process -Filter "ProcessId=$($record.pid)"
$expectedExe=[IO.Path]::GetFullPath((Join-Path $neo4jHome 'bin\tools\prunsrv-amd64.exe'))
if(!$live -or [string]$live.ExecutablePath -ine $expectedExe -or [string]$live.CommandLine -cnotmatch ('(?i)//RS//'+[regex]::Escape([string]$record.serviceName)+'(?:\s|$)')){throw 'Refuse termination: live service image or command does not match the recorded installation'}
$held=Get-Process -Id ([int]$record.pid) -ErrorAction Stop
[void]$held.Handle
try {
    $heldStartMillis=([DateTimeOffset]$held.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
    if($heldStartMillis -ne [long]$record.startMillis){throw 'Refuse termination: held process incarnation changed'}
    $second=Get-DepositServiceProcessIdentity $neo4jHome ([string]$state.serviceName) ([string]$state.serviceAccount) ([long]$record.pid) ([long]$record.startMillis)
    if([string]$second.runtimeSid -cne [string]$record.runtimeSid){throw 'Refuse termination: service runtime SID changed during validation'}
    $live=Get-CimInstance Win32_Process -Filter "ProcessId=$($record.pid)"
    if(!$live -or [string]$live.ExecutablePath -ine $expectedExe -or [string]$live.CommandLine -cnotmatch ('(?i)//RS//'+[regex]::Escape([string]$record.serviceName)+'(?:\s|$)')){throw 'Refuse termination: service image changed during validation'}
    Stop-Process -InputObject $held -Force -ErrorAction Stop
    if(!$held.WaitForExit(15000)){throw 'Recorded service process did not exit after termination'}
} finally {$held.Dispose()}
$deadline=[DateTime]::UtcNow.AddSeconds(15)
do {
    $service=Get-CimInstance Win32_Service -Filter "Name='$($record.serviceName)'"
    if(!$service -or [string]$service.State -eq 'Stopped'){break}
    if([long]$service.ProcessId -gt 0 -and [long]$service.ProcessId -ne [long]$record.pid){throw 'Service restarted automatically after the controlled crash; refusing to touch its replacement'}
    Start-Sleep -Milliseconds 100
} while([DateTime]::UtcNow -lt $deadline)
if($service -and [string]$service.State -ne 'Stopped'){throw 'Service did not reach Stopped after the recorded process exited'}
Write-Output "Terminated recorded Deposit service process $($record.pid) for crash recovery testing"
