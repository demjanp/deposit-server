[CmdletBinding()]
param(
    [ValidateRange(1024,65535)][int]$Port = 17770,
    [string]$Root = (Join-Path $PSScriptRoot '..\..\test_harness'),
    [string]$LogPath
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
Assert-HarnessRoot $Root
$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\')
Assert-HarnessRoot $rootPath
if (!$LogPath) {
    $directory = Join-Path (Join-Path $PSScriptRoot '..\..\test_harness') 'logs'
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $LogPath = Join-Path $directory ((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ') + '-stage11-service-shutdown.log')
}
$LogPath = [IO.Path]::GetFullPath($LogPath)
New-Item -ItemType Directory -Force -Path (Split-Path $LogPath -Parent) | Out-Null
$run = Join-Path $rootPath 'runs\stage0-host'
$distribution = Join-Path $run 'distribution'
$launcher = Join-Path $distribution 'deposit.bat'
$statePath = Join-Path $distribution 'deposit.install.json'
$ready = Join-Path $run 'secrets\stage11-drain-ready.txt'
$release = Join-Path $run 'secrets\stage11-drain-release.txt'
$fixture = Join-Path $run 'secrets\stage11-drain-fixture.properties'
$utf8 = New-Object Text.UTF8Encoding($false)
$scenarioLog = New-Object System.Collections.Generic.List[string]

function Write-ScenarioLog([string]$Text) {
    $scenarioLog.Add($Text)
    [IO.File]::WriteAllLines($LogPath,$scenarioLog,$utf8)
}
function Quote-PowerShell([string]$Value) { "'" + $Value.Replace("'", "''") + "'" }
function Start-PowerShellChild([string]$Command) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Command))
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = Join-Path $PSHOME 'powershell.exe'
    $startInfo.Arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    if (!$process.Start()) { throw 'Could not start a Stage 11 service scenario subprocess.' }
    return [pscustomobject]@{ Process=$process; StandardOutput=$process.StandardOutput.ReadToEndAsync(); StandardError=$process.StandardError.ReadToEndAsync() }
}
function Complete-PowerShellChild($Child,[string]$Label,[int]$TimeoutMilliseconds) {
    $process = $Child.Process
    if (!$process.WaitForExit($TimeoutMilliseconds)) { throw "$Label timed out; see $LogPath" }
    $process.WaitForExit()
    $stdout = if ($Child.StandardOutput.Wait(5000)) { try { $Child.StandardOutput.Result } catch { "stdout collection failed: $($_.Exception.Message)" } } else { 'Child exited, but a descendant kept stdout open.' }
    $stderr = if ($Child.StandardError.Wait(5000)) { try { $Child.StandardError.Result } catch { "stderr collection failed: $($_.Exception.Message)" } } else { 'Child exited, but a descendant kept stderr open.' }
    Write-ScenarioLog "$Label exit code: $($process.ExitCode)"
    if ($stdout) { foreach($line in ($stdout -split "`r?`n")) { if($line){Write-ScenarioLog "$Label stdout: $line"} } }
    if ($stderr) { foreach($line in ($stderr -split "`r?`n")) { if($line){Write-ScenarioLog "$Label stderr: $line"} } }
    $code = $process.ExitCode
    $process.Dispose()
    return $code
}
function Invoke-Launcher([string]$Operation,[string]$Label,[int]$TimeoutMilliseconds=330000) {
    $command = '& ' + (Quote-PowerShell $launcher) + ' ' + $Operation + '; exit $LASTEXITCODE'
    $child = Start-PowerShellChild $command
    $code = Complete-PowerShellChild $child $Label $TimeoutMilliseconds
    if($code -ne 0){throw "$Label failed with exit code $code"}
}
function Get-ServiceRecord([string]$Name) { Get-CimInstance Win32_Service -Filter "Name='$Name'" }
function Get-ServiceIdentity($State) {
    . (Join-Path $PSScriptRoot 'Request-DepositStop.ps1')
    Get-DepositServiceProcessIdentity $State.neo4jHome $State.serviceName $State.serviceAccount
}
function Get-DownloadProbeResult([string]$BaseUrl) {
    $request=[System.Net.HttpWebRequest]::Create([Uri]::new($BaseUrl.TrimEnd('/')+'/login'))
    $request.Method='GET';$request.AllowAutoRedirect=$false;$request.KeepAlive=$false;$request.Proxy=$null;$request.Timeout=5000
    try{$response=$request.GetResponse();$status=[int]$response.StatusCode;$response.Dispose();return [pscustomobject]@{Rejected=($status -eq 503);Detail="HTTP $status"}}
    catch [System.Net.WebException]{if($_.Exception.Response){$status=[int]$_.Exception.Response.StatusCode;$_.Exception.Response.Dispose();return [pscustomobject]@{Rejected=($status -eq 503);Detail="HTTP $status"}};$rejected=$_.Exception.Status -in @([System.Net.WebExceptionStatus]::ConnectFailure,[System.Net.WebExceptionStatus]::ConnectionClosed);return [pscustomobject]@{Rejected=$rejected;Detail="Request rejected: $($_.Exception.Status)"}}
    catch{return [pscustomobject]@{Rejected=$false;Detail="Probe failed: $($_.Exception.GetType().Name)"}}
    finally{$request.Abort()}
}
function Read-Json([string]$Path) { Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -ErrorAction Stop }
function Wait-ServiceStatus([string]$Name,[string]$Status,[int]$Seconds) {
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    do{$service=Get-Service -Name $Name -ErrorAction SilentlyContinue;if($service -and [string]$service.Status -eq $Status){return $service};Start-Sleep -Milliseconds 250}while([DateTime]::UtcNow -lt $deadline)
    throw "Service $Name did not reach $Status within $Seconds seconds"
}
function Wait-BoltPort([int]$Port,[int]$Seconds) {
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    do{
        $client=New-Object Net.Sockets.TcpClient
        try{
            $pending=$client.BeginConnect([Net.IPAddress]::Loopback,$Port,$null,$null)
            if($pending.AsyncWaitHandle.WaitOne(500)){$client.EndConnect($pending);return}
        }catch{}finally{$client.Dispose()}
        Start-Sleep -Milliseconds 250
    }while([DateTime]::UtcNow -lt $deadline)
    throw "Bolt listener on 127.0.0.1:$Port did not become reachable within $Seconds seconds"
}
function Invoke-Test([string]$TestName,[string]$Label) {
    $script=Join-Path $PSScriptRoot 'Invoke-Tests.ps1'
    $command='& '+(Quote-PowerShell $script)+' -Kind Integration -Test '+(Quote-PowerShell $TestName)+' -Port '+$Port+' -LogPath '+(Quote-PowerShell ([IO.Path]::ChangeExtension($LogPath,".$Label.log")))+'; exit $LASTEXITCODE'
    $child=Start-PowerShellChild $command
    $code=Complete-PowerShellChild $child $Label 330000
    if($code -ne 0){throw "$Label failed with exit code $code"}
}

$testChild=$null
$stopChild=$null
$failure=$null
$released=$false
$backup=[IO.Path]::ChangeExtension($LogPath,'.prior-state')
$state=$null
$serviceName=$null
$wasRunning=$false
$identity=$null
$control=$null
$descriptor=$null
$ackDirectory=$null
try {
    if(!(Test-Path -LiteralPath $statePath -PathType Leaf) -or !(Test-Path -LiteralPath $launcher -PathType Leaf)){throw 'The configured packaged Deposit distribution is incomplete.'}
    $state=Read-Json $statePath
    if($state.mode -ne 'service' -or !$state.serviceName -or !$state.serviceAccount){throw 'Stage 11 service scenario requires an installed service-mode distribution.'}
    $serviceName=[string]$state.serviceName
    $service=Get-ServiceRecord $serviceName
    if(!$service){throw "Configured Deposit service $serviceName is not installed."}
    $wasRunning=([string]$service.State -eq 'Running')
    if(!$wasRunning){Invoke-Launcher 'start' 'service-start-before-scenario';Wait-ServiceStatus $serviceName 'Running' 90}
    $identity=Get-ServiceIdentity $state
    Write-ScenarioLog "Bound service $serviceName to PID $($identity.pid), start $($identity.startMillis), account $($identity.serviceAccount)."

    New-Item -ItemType Directory -Path $backup | Out-Null
    . (Join-Path $PSScriptRoot 'Credentials.ps1')
    Set-OwnerOnlyDirectory $backup
    foreach($path in @($ready,$release,$fixture)){if(Test-Path -LiteralPath $path){Copy-Item -LiteralPath $path -Destination (Join-Path $backup ([IO.Path]::GetFileName($path)))}}
    $previousReady=if(Test-Path -LiteralPath $ready){[IO.File]::ReadAllText($ready)}else{''}
    $testScript=Join-Path $PSScriptRoot 'Invoke-Tests.ps1'
    $testCommand='& '+(Quote-PowerShell $testScript)+' -Kind Integration -Test '+(Quote-PowerShell 'Stage11ShutdownIT#holdTransactionAndDownloadUntilShutdownSignalsRelease')+' -Port '+$Port+' -LogPath '+(Quote-PowerShell ([IO.Path]::ChangeExtension($LogPath,'.hold-test.log')))+'; exit $LASTEXITCODE'
    $testChild=Start-PowerShellChild $testCommand
    Write-ScenarioLog 'Started the selected Stage 11 transaction and download hold test.'
    $readyDeadline=[DateTime]::UtcNow.AddSeconds(180)
    while([DateTime]::UtcNow -lt $readyDeadline){
        if((Test-Path -LiteralPath $ready) -and [IO.File]::ReadAllText($ready) -ne $previousReady){break}
        if($testChild.Process.HasExited){throw 'Hold test exited before READY; inspect the hold-test log.'}
        Start-Sleep -Milliseconds 100
    }
    if(!(Test-Path -LiteralPath $ready) -or [IO.File]::ReadAllText($ready) -eq $previousReady){throw 'Hold test did not create a fresh READY signal within 180 seconds.'}
    Write-ScenarioLog 'Observed fresh READY after the transaction, download reader, and handler were admitted.'

    $config=Get-Content -LiteralPath (Join-Path $state.neo4jHome 'conf\deposit.properties') -Raw
    $secretRoot=([regex]::Match($config,'(?m)^secretRoot=(.+)$')).Groups[1].Value.Trim()
    $control=Join-Path $secretRoot 'deposit-control'
    $descriptor=Read-Json (Join-Path $control 'run.json')
    if([long]$descriptor.pid -ne [long]$identity.pid -or [long]$descriptor.startMillis -ne [long]$identity.startMillis -or $descriptor.transactions -lt 1 -or $descriptor.readers -lt 1 -or $descriptor.handlers -lt 1){throw 'Runtime descriptor does not bind the service incarnation and all admitted work.'}
    $ackDirectory=Join-Path $control ([string]$descriptor.incarnation)
    $baseUrl=([regex]::Match($config,'(?m)^download\.publicBaseUrl=(.+)$')).Groups[1].Value.Trim()
    if(!$baseUrl){throw 'Configured download public base URL is missing.'}
    $stopCommand='& '+(Quote-PowerShell $launcher)+' stop; exit $LASTEXITCODE'
    $stopChild=Start-PowerShellChild $stopCommand
    Write-ScenarioLog 'Started the real packaged service-mode deposit.bat stop command.'

    $progressDeadline=[DateTime]::UtcNow.AddSeconds(20)
    $draining=$null
    $lastProbe='No probe completed.'
    while([DateTime]::UtcNow -lt $progressDeadline){
        $serviceNow=Get-ServiceRecord $serviceName
        if(!$serviceNow -or [string]$serviceNow.State -ne 'Running' -or [long]$serviceNow.ProcessId -ne [long]$identity.pid){throw 'SCM service incarnation stopped or changed before admitted work was released.'}
        if($stopChild.Process.HasExited){throw 'Packaged Stop exited before the held transaction and stream were released.'}
        foreach($ackFile in Get-ChildItem -LiteralPath $ackDirectory -Filter 'ack-*.json' -ErrorAction SilentlyContinue){
            $candidate=Read-Json $ackFile.FullName
            if($candidate.state -eq 'admissionClosed' -and $candidate.transactions -gt 0 -and $candidate.readers -gt 0 -and $candidate.handlers -gt 0){$draining=$candidate;break}
        }
        $probe=Get-DownloadProbeResult $baseUrl;$lastProbe=$probe.Detail
        if($draining -and $probe.Rejected){break}
        Start-Sleep -Milliseconds 100
    }
    if(!$draining){throw 'No admissionClosed acknowledgement preserved the in-flight transaction, reader, and handler.'}
    if(!$probe.Rejected){throw "New listener requests were not rejected during the held service drain: $lastProbe"}
    Write-ScenarioLog "Admission closed with tx=$($draining.transactions), readers=$($draining.readers), handlers=$($draining.handlers); new request rejected ($($probe.Detail)); SCM still owns PID $($identity.pid)."

    [IO.File]::WriteAllText($release,'release'+[Environment]::NewLine,$utf8);$released=$true
    Write-ScenarioLog 'Released the held write transaction and active download stream.'
    if($testChild.Process.WaitForExit(150000)){$code=Complete-PowerShellChild $testChild 'hold-test' 1000;$testChild=$null;if($code -ne 0){throw "Hold test failed with exit code $code"}}else{throw 'Hold test did not finish after release.'}
    if($stopChild.Process.WaitForExit(330000)){$code=Complete-PowerShellChild $stopChild 'service-stop' 1000;$stopChild=$null;if($code -ne 0){throw "Packaged service stop failed with exit code $code"}}else{throw 'Packaged service stop did not finish after the drain completed.'}
    [void](Wait-ServiceStatus $serviceName 'Stopped' 30)
    $completed=Read-Json (Join-Path $ackDirectory "ack-$($draining.requestId).json")
    if($completed.state -ne 'complete' -or $completed.transactions -ne 0 -or $completed.readers -ne 0 -or $completed.handlers -ne 0 -or !$completed.storageClosed){throw 'Terminal acknowledgement did not confirm zero in-flight work and closed storage.'}
    Write-ScenarioLog 'Confirmed the complete terminal acknowledgement and SCM service stop.'

    Invoke-Launcher 'start' 'service-restart'
    $newIdentity=$null;$newDescriptor=$null;$restartDeadline=[DateTime]::UtcNow.AddSeconds(90)
    while([DateTime]::UtcNow -lt $restartDeadline){
        try{
            $candidateIdentity=Get-ServiceIdentity $state
            if([long]$candidateIdentity.pid -ne [long]$identity.pid -or [long]$candidateIdentity.startMillis -ne [long]$identity.startMillis){
                $candidateDescriptor=Read-Json (Join-Path $control 'run.json')
                if([long]$candidateDescriptor.pid -eq [long]$candidateIdentity.pid -and [long]$candidateDescriptor.startMillis -eq [long]$candidateIdentity.startMillis){$newIdentity=$candidateIdentity;$newDescriptor=$candidateDescriptor;break}
            }
        }catch{}
        Start-Sleep -Milliseconds 250
    }
    if(!$newIdentity -or !$newDescriptor){throw 'Restarted service did not publish a fresh runtime descriptor bound to its new SCM process.'}
    if([string]$newDescriptor.databaseId -cne [string]$descriptor.databaseId -or [string]$newDescriptor.incarnation -ceq [string]$descriptor.incarnation){throw 'Restart descriptor does not preserve database identity under a fresh service incarnation.'}
    Wait-BoltPort $Port 60
    Write-ScenarioLog "Restarted as PID $($newIdentity.pid), new incarnation $($newDescriptor.incarnation), database $($newDescriptor.databaseId)."
    Invoke-Test 'Stage11ShutdownIT#verifyCommittedTransactionSurvivedServiceShutdown' 'verify-committed-transaction'
    Write-ScenarioLog 'The committed-transaction integration verifier passed after service restart.'
} catch {
    $failure=$_.Exception.Message
    Write-ScenarioLog "FAIL: $failure"
} finally {
    if(!$released -and $testChild -and !$testChild.Process.HasExited){try{[IO.File]::WriteAllText($release,'release'+[Environment]::NewLine,$utf8);$released=$true;Write-ScenarioLog 'Cleanup released the held test after orchestration failure.'}catch{Write-ScenarioLog "Cleanup release failed: $($_.Exception.Message)"}}
    if($testChild){try{if($testChild.Process.WaitForExit(150000)){$code=Complete-PowerShellChild $testChild 'hold-test-cleanup' 1000;$testChild=$null;if($code -ne 0 -and !$failure){$failure='Hold test failed during cleanup.'}}else{Write-ScenarioLog 'Hold test remains active after cleanup wait.'}}catch{Write-ScenarioLog "Hold-test cleanup issue: $($_.Exception.Message)"}}
    if($stopChild){try{if($stopChild.Process.WaitForExit(330000)){$code=Complete-PowerShellChild $stopChild 'service-stop-cleanup' 1000;$stopChild=$null;if($code -ne 0 -and !$failure){$failure='Service stop failed during cleanup.'}}else{Write-ScenarioLog 'Packaged service stop remains active after cleanup wait.'}}catch{Write-ScenarioLog "Service-stop cleanup issue: $($_.Exception.Message)"}}
    if($testChild){try{$testChild.Process.Dispose()}catch{}}
    if($stopChild){try{$stopChild.Process.Dispose()}catch{}}
    if(!(Test-HarnessTerminalCleanup) -and $state -and $wasRunning){$serviceNow=Get-Service -Name $serviceName -ErrorAction SilentlyContinue;if($serviceNow -and $serviceNow.Status -eq 'Stopped'){try{Invoke-Launcher 'start' 'restore-service-start';Wait-ServiceStatus $serviceName 'Running' 90}catch{Write-ScenarioLog "Service restoration failed: $($_.Exception.Message)";if(!$failure){$failure=$_.Exception.Message}}}}
    foreach($pair in @(@($fixture,'generated-stage11-drain-fixture.properties'),@($ready,'generated-stage11-drain-ready.txt'),@($release,'generated-stage11-drain-release.txt'))){if((Test-Path -LiteralPath $pair[0]) -and (Test-Path -LiteralPath $backup)){Copy-Item -LiteralPath $pair[0] -Destination (Join-Path $backup $pair[1]) -Force}}
}

if($failure){Write-Output 'FAIL: Stage 11 service shutdown scenario';Write-Output "Log: $LogPath";exit 1}
Write-Output 'PASS: Stage 11 service shutdown scenario'
Write-Output "Log: $LogPath"
exit 0
