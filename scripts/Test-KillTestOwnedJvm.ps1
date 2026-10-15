[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$run='D:\data_processed\Deposit_v2\stage0\runs\stage0-host'
$recordPath=Join-Path $run 'process.json'
$helper=Join-Path $PSScriptRoot 'Kill-TestOwnedJvm.ps1'
$state=Get-Content (Join-Path $run 'distribution\deposit.install.json') -Raw|ConvertFrom-Json
$record=Get-Content -LiteralPath $recordPath -Raw|ConvertFrom-Json
$service=Get-CimInstance Win32_Service -Filter "Name='$($state.serviceName)'"
if($state.mode -ne 'service' -or !$service -or [string]$service.State -ne 'Running' -or [long]$record.pid -ne [long]$service.ProcessId){throw 'Refusal checks require the configured Deposit service to be running and recorded.'}
$original=Get-Content -LiteralPath $recordPath -Raw
$cases=@(
    @{name='missing identity fields';change={param($value)$value.PSObject.Properties.Remove('runtimeSid');$value.PSObject.Properties.Remove('serviceName')}},
    @{name='wrong installation';change={param($value)$value.neo4jHome='C:\Windows'}},
    @{name='wrong service name';change={param($value)$value.serviceName='OtherService'}},
    @{name='wrong service account';change={param($value)$value.serviceAccount='LocalSystem'}},
    @{name='wrong runtime SID';change={param($value)$value.runtimeSid='S-1-5-18'}},
    @{name='wrong PID';change={param($value)$value.pid=[long]$value.pid+1}},
    @{name='wrong process start';change={param($value)$value.startMillis=[long]$value.startMillis+1000}}
)
try {
    foreach($case in $cases){
        $candidate=ConvertFrom-Json $original
        $change=$case.change
        & $change $candidate
        [IO.File]::WriteAllText($recordPath,($candidate|ConvertTo-Json -Depth 5),(New-Object Text.UTF8Encoding($false)))
        $startInfo=New-Object Diagnostics.ProcessStartInfo
        $startInfo.FileName=Join-Path $PSHOME 'powershell.exe'
        $startInfo.Arguments='-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+$helper+'"'
        $startInfo.UseShellExecute=$false;$startInfo.CreateNoWindow=$true;$startInfo.RedirectStandardOutput=$true;$startInfo.RedirectStandardError=$true
        $child=New-Object Diagnostics.Process;$child.StartInfo=$startInfo
        if(!$child.Start()){throw "Could not start crash-helper refusal check '$($case.name)'"}
        $stdout=$child.StandardOutput.ReadToEndAsync();$stderr=$child.StandardError.ReadToEndAsync()
        if(!$child.WaitForExit(15000)){$child.Kill();throw "Crash-helper refusal check '$($case.name)' timed out"}
        $child.WaitForExit();$code=$child.ExitCode;$output=($stdout.Result+$stderr.Result);$child.Dispose()
        $stillRunning=Get-CimInstance Win32_Service -Filter "Name='$($state.serviceName)'"
        if($code -eq 0 -or !$stillRunning -or [string]$stillRunning.State -ne 'Running' -or [long]$stillRunning.ProcessId -ne [long]$record.pid){throw "Refusal check failed for '$($case.name)': helper exit=$code; output=$output"}
        Write-Output "PASS: crash helper refused $($case.name) without terminating the service"
    }
} finally {[IO.File]::WriteAllText($recordPath,$original,(New-Object Text.UTF8Encoding($false)))}
