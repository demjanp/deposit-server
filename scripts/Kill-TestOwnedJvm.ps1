# Independent crash harness helper; accepts no arbitrary target process or path.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$run='C:\data_processed\Deposit_v2\stage0\runs\stage0-host'
$record=Get-Content (Join-Path $run 'process.json') -Raw | ConvertFrom-Json
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$($record.pid)"
if(!$process -or !$process.CommandLine -or !$process.CommandLine.Contains($run) -or !$process.CommandLine.Contains('org.neo4j') -or
    [Math]::Abs(($process.CreationDate.ToUniversalTime()-[DateTime]::Parse($record.created).ToUniversalTime()).TotalSeconds) -ge 2) {
    throw 'Refuse termination: recorded test JVM identity did not match'
}
Stop-Process -Id $record.pid -Force
Write-Output "Terminated recorded test JVM $($record.pid)"
