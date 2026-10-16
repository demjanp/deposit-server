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
    $LogPath = Join-Path $directory ((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ') + '-stage11-shutdown.log')
}
$LogPath = [IO.Path]::GetFullPath($LogPath)
$logDirectory = Split-Path $LogPath -Parent
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$run = Join-Path $rootPath 'runs\stage0-host'
$ready = Join-Path $run 'secrets\stage11-drain-ready.txt'
$release = Join-Path $run 'secrets\stage11-drain-release.txt'
$fixture = Join-Path $run 'secrets\stage11-drain-fixture.properties'
$scenarioLog = New-Object System.Collections.Generic.List[string]
$utf8 = New-Object Text.UTF8Encoding($false)

function Write-ScenarioLog([string]$Text) {
    $scenarioLog.Add($Text)
    [IO.File]::WriteAllLines($LogPath,$scenarioLog,$utf8)
}
function Quote-PowerShell([string]$Value) { "'" + $Value.Replace("'", "''") + "'" }
function Format-PowerShellArguments([string[]]$Values) {
    ($Values | ForEach-Object { if ([string]$_ -match '^-') { [string]$_ } else { Quote-PowerShell ([string]$_) } }) -join ' '
}
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
    if (!$process.Start()) { throw 'Could not start a Stage 11 scenario subprocess.' }
    return [pscustomobject]@{ Process=$process; StandardOutput=$process.StandardOutput.ReadToEndAsync(); StandardError=$process.StandardError.ReadToEndAsync() }
}
function Complete-PowerShellChild($Child,[string]$OutputPath,[string]$ErrorPath,[int]$TimeoutMilliseconds) {
    $process = $Child.Process
    if (!$process.WaitForExit($TimeoutMilliseconds)) { throw "Scenario subprocess timed out; see $OutputPath and $ErrorPath" }
    $process.WaitForExit()
    $exitCode = $process.ExitCode
    $stdout = if ($Child.StandardOutput.Wait(5000)) { try { $Child.StandardOutput.Result } catch { "Scenario stdout collection failed: $($_.Exception.Message)" } }
        else { 'Scenario child exited, but a descendant kept stdout open; remaining output was omitted.' }
    $stderr = if ($Child.StandardError.Wait(5000)) { try { $Child.StandardError.Result } catch { "Scenario stderr collection failed: $($_.Exception.Message)" } }
        else { 'Scenario child exited, but a descendant kept stderr open; remaining output was omitted.' }
    [IO.File]::WriteAllText($OutputPath,$stdout,$utf8)
    [IO.File]::WriteAllText($ErrorPath,$stderr,$utf8)
    $process.Dispose()
    return $exitCode
}
function Get-OwnedServer {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains((Join-Path $run 'neo4j')) -and
        $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'
    } | Select-Object -First 1
}
function Invoke-ChildScript([string]$ScriptPath,[string[]]$ScriptArguments,[string]$Label) {
    $command = '& ' + (Quote-PowerShell $ScriptPath) + ' ' + (Format-PowerShellArguments $ScriptArguments) + '; exit $LASTEXITCODE'
    $child = Start-PowerShellChild $command
    $base = [IO.Path]::ChangeExtension($LogPath,".$Label-runner")
    $code = Complete-PowerShellChild $child "$base.log" "$base.err.log" 330000
    foreach ($line in Get-Content -LiteralPath "$base.log" -ErrorAction SilentlyContinue) { Write-ScenarioLog ([string]$line) }
    foreach ($line in Get-Content -LiteralPath "$base.err.log" -ErrorAction SilentlyContinue) { Write-ScenarioLog ([string]$line) }
    if ($code -ne 0) { throw "$Label failed; see $base.log" }
}
function Start-Stage0 {
    Invoke-ChildScript (Join-Path $PSScriptRoot 'Stage0.ps1') @('-Phase','Start','-Root',$rootPath,'-Port',[string]$Port) 'stage0-start'
}
function Get-DownloadProbeResult([string]$BaseUrl) {
    $request = [System.Net.HttpWebRequest]::Create([Uri]::new($BaseUrl.TrimEnd('/') + '/login'))
    $request.Method = 'GET'
    $request.AllowAutoRedirect = $false
    $request.KeepAlive = $false
    $request.Proxy = $null
    $request.Timeout = 5000
    try {
        $response = $request.GetResponse()
        $status = [int]$response.StatusCode
        $response.Dispose()
        return [pscustomobject]@{ Rejected=($status -eq 503); Detail="HTTP $status" }
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
            $_.Exception.Response.Dispose()
            return [pscustomobject]@{ Rejected=($status -eq 503); Detail="HTTP $status" }
        }
        $rejected = $_.Exception.Status -in @([System.Net.WebExceptionStatus]::ConnectFailure,[System.Net.WebExceptionStatus]::ConnectionClosed)
        return [pscustomobject]@{ Rejected=$rejected; Detail="Request rejected: $($_.Exception.Status)" }
    } catch {
        return [pscustomobject]@{ Rejected=$false; Detail="Probe failed: $($_.Exception.GetType().Name)" }
    } finally { $request.Abort() }
}
function Wait-ChildExit($Child,[string]$Label,[int]$TimeoutSeconds) {
    if (!$Child.Process.WaitForExit($TimeoutSeconds * 1000)) { throw "$Label did not exit within $TimeoutSeconds seconds" }
    $base = [IO.Path]::ChangeExtension($LogPath,".$Label-runner")
    $code = Complete-PowerShellChild $Child "$base.log" "$base.err.log" 1000
    foreach ($line in Get-Content -LiteralPath "$base.log" -ErrorAction SilentlyContinue) { Write-ScenarioLog ([string]$line) }
    foreach ($line in Get-Content -LiteralPath "$base.err.log" -ErrorAction SilentlyContinue) { Write-ScenarioLog ([string]$line) }
    if ($code -ne 0) { throw "$Label failed; see $base.log" }
}

$testChild = $null
$stopChild = $null
$wasRunning = [bool](Get-OwnedServer)
$failure = $null
$released = $false
$stateBackup = [IO.Path]::ChangeExtension($LogPath,'.prior-state')
$launcherIdentity=Join-Path $run 'neo4j\deposit-process.json'
$identityPrepared=$false
try {
    if (!$wasRunning) { Start-Stage0 }
    $backup = New-Item -ItemType Directory -Path $stateBackup
    . (Join-Path $PSScriptRoot 'Credentials.ps1')
    Set-OwnerOnlyDirectory $backup.FullName
    foreach ($path in @($ready,$release,$fixture)) {
        if (Test-Path -LiteralPath $path) { Copy-Item -LiteralPath $path -Destination (Join-Path $backup.FullName ([IO.Path]::GetFileName($path))) }
    }
    $previousReady=if(Test-Path -LiteralPath $ready){[IO.File]::ReadAllText($ready)}else{''}

    $testLog = [IO.Path]::ChangeExtension($LogPath,'.hold-test.log')
    $testCommand = '& ' + (Quote-PowerShell (Join-Path $PSScriptRoot 'Invoke-Tests.ps1')) + ' -Kind Integration -Test ' + (Quote-PowerShell 'Stage11ShutdownIT#holdTransactionAndDownloadUntilShutdownSignalsRelease') + ' -Port ' + $Port + ' -LogPath ' + (Quote-PowerShell $testLog) + '; exit $LASTEXITCODE'
    $testChild = Start-PowerShellChild $testCommand
    Write-ScenarioLog 'Started the selected Stage11 hold test.'

    $readyDeadline = [DateTime]::UtcNow.AddSeconds(150)
    $freshReady=$false
    while ([DateTime]::UtcNow -lt $readyDeadline) {
        if ((Test-Path -LiteralPath $ready) -and [IO.File]::ReadAllText($ready) -ne $previousReady) { $freshReady=$true;break }
        if ($testChild.Process.HasExited) { throw "Hold test exited before its READY signal; see $testLog" }
        Start-Sleep -Milliseconds 100
    }
    if (!$freshReady) { throw "Hold test did not create its READY signal before timeout; see $testLog" }
    Write-ScenarioLog 'Observed a fresh READY signal after the transaction and download were admitted.'

    # Stage a task-owned launcher around the existing installation; exercise the actual production Stop implementation.
    $launcher=Join-Path $stateBackup 'launcher';New-Item -ItemType Directory -Path (Join-Path $launcher 'helpers') -Force|Out-Null
    Copy-Item -LiteralPath (Join-Path $repo 'runtime\distribution\deposit.ps1'),(Join-Path $repo 'runtime\artifacts.json') -Destination $launcher
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Request-DepositStop.ps1'),(Join-Path $PSScriptRoot 'Stop-Console.ps1') -Destination (Join-Path $launcher 'helpers')
    $owned=Get-OwnedServer;if(!$owned){throw 'Owned runtime is absent before production Stop'}
    if(![IO.Path]::GetFullPath($launcherIdentity).StartsWith([IO.Path]::GetFullPath($run).TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Launcher process identity escaped the approved run root'}
    if(Test-Path -LiteralPath $launcherIdentity){Copy-Item -LiteralPath $launcherIdentity -Destination (Join-Path $stateBackup 'prior-deposit-process.json')}
    $identityPrepared=$true
    [IO.File]::WriteAllText($launcherIdentity,(@{pid=[int]$owned.ProcessId;created=$owned.CreationDate.ToUniversalTime().ToString('o');mode='local'}|ConvertTo-Json),$utf8)
    $manifest=Get-Content (Join-Path $repo 'runtime\artifacts.json') -Raw|ConvertFrom-Json
    $configuration=[IO.File]::ReadAllText((Join-Path $run 'neo4j\conf\deposit.properties'))
    $secretRoot=([regex]::Match($configuration,'(?m)^secretRoot=(.+)$')).Groups[1].Value.Trim()
    $control=Join-Path $secretRoot 'deposit-control';$descriptor=Get-Content (Join-Path $control 'run.json') -Raw|ConvertFrom-Json
    if($descriptor.pid -ne $owned.ProcessId -or $descriptor.transactions -lt 1 -or $descriptor.readers -lt 1 -or $descriptor.handlers -lt 1){throw 'The control descriptor does not confirm the owned live transaction/reader/handler'}
    [IO.File]::WriteAllText((Join-Path $launcher 'deposit.install.json'),(@{neo4jHome=(Join-Path $run 'neo4j');javaHome=(Join-Path $rootPath $manifest.javaHome);dataRoot=(Join-Path $run 'resources');exportRoot=(Join-Path $run 'exports');secretRoot=$secretRoot;mode='local';serviceName='DepositTriageNoService'}|ConvertTo-Json),$utf8)
    $stopCommand = '& ' + (Quote-PowerShell (Join-Path $launcher 'deposit.ps1')) + ' stop; exit $LASTEXITCODE'
    $stopChild = Start-PowerShellChild $stopCommand
    Write-ScenarioLog 'Started the production launcher Stop for the owned Stage0 process.'

    $publicBase = ([regex]::Match([IO.File]::ReadAllText((Join-Path $run 'neo4j\conf\deposit.properties')),'(?m)^download\.publicBaseUrl=(.+)$')).Groups[1].Value.Trim()
    if (!$publicBase) { throw 'The configured download public base URL is missing.' }
    $rejectDeadline = [DateTime]::UtcNow.AddSeconds(15)
    $rejection = $null
    $lastProbeDetail = 'No probe completed.'
    while ([DateTime]::UtcNow -lt $rejectDeadline) {
        $owned = Get-OwnedServer
        if (!$owned) { throw 'The owned server exited before the held transaction was released.' }
        if($stopChild.Process.HasExited){Wait-ChildExit $stopChild 'graceful-stop' 1;throw 'Production Stop exited without holding the admitted work'}
        $probe = Get-DownloadProbeResult $publicBase
        $lastProbeDetail = $probe.Detail
        if ($probe.Rejected) { $rejection = $probe; break }
        if ($probe.Detail -ne 'HTTP 200') { Write-ScenarioLog "Shutdown probe remains inconclusive: $($probe.Detail)." }
        Start-Sleep -Milliseconds 100
    }
    if (!$rejection) { throw "A new download-listener request was not rejected after Stop began; last probe: $lastProbeDetail" }
    $ackDirectory=Join-Path $control $descriptor.incarnation
    $progressDeadline=[DateTime]::UtcNow.AddSeconds(5);$draining=$null
    while([DateTime]::UtcNow -lt $progressDeadline){
        foreach($ackFile in Get-ChildItem -LiteralPath $ackDirectory -Filter 'ack-*.json'){$candidate=Get-Content -LiteralPath $ackFile.FullName -Raw|ConvertFrom-Json;if($candidate.state -eq 'admissionClosed' -and $candidate.transactions -gt 0 -and $candidate.readers -gt 0 -and $candidate.handlers -gt 0){$draining=$candidate;break}}
        if($draining){break};Start-Sleep -Milliseconds 100
    }
    if(!$draining){throw 'No admission-closed acknowledgement confirms the admitted transaction/reader/handler'}
    Write-ScenarioLog "Admission closed with $($draining.transactions) transaction(s), $($draining.readers) server reader(s), $($draining.handlers) handler(s) admitted."
    Write-ScenarioLog "Confirmed new download request rejection while owned server PID $($owned.ProcessId) remained in its drain: $($rejection.Detail)."

    [IO.File]::WriteAllText($release,'release' + [Environment]::NewLine,$utf8)
    $released = $true
    Write-ScenarioLog 'Released the admitted transaction and download handler.'
    Wait-ChildExit $testChild 'hold-test' 120
    $testChild = $null
    Wait-ChildExit $stopChild 'graceful-stop' 330
    $stopChild = $null
    if (Get-OwnedServer) { throw 'The owned Stage0 process remained after graceful shutdown completed.' }
    $completed=Get-Content -LiteralPath (Join-Path $ackDirectory "ack-$($draining.requestId).json") -Raw|ConvertFrom-Json
    if($completed.state -ne 'complete' -or $completed.transactions -ne 0 -or $completed.readers -ne 0 -or $completed.handlers -ne 0 -or !$completed.storageClosed){throw 'The terminal acknowledgement did not establish full cleanup before the process signal'}
    Write-ScenarioLog 'Confirmed the owned process exited after admitted work completed cleanup.'

    Start-Stage0
    $verifyLog = [IO.Path]::ChangeExtension($LogPath,'.verify-test.log')
    Invoke-ChildScript (Join-Path $PSScriptRoot 'Invoke-Tests.ps1') @('-Kind','Integration','-Test','Stage11ShutdownIT#verifyCommittedTransactionSurvivedServiceShutdown','-Port',[string]$Port,'-LogPath',$verifyLog) 'verify-test'
    Write-ScenarioLog 'The post-restart transaction verifier passed.'
} catch {
    $failure = $_.Exception.Message
    Write-ScenarioLog "FAIL: $failure"
} finally {
    if (!$released -and $testChild -and !$testChild.Process.HasExited) {
        try { [IO.File]::WriteAllText($release,'release' + [Environment]::NewLine,$utf8); $released = $true; Write-ScenarioLog 'Cleanup released the held test after orchestration failure.' } catch { Write-ScenarioLog "Cleanup release failed: $($_.Exception.Message)" }
    }
    if ($testChild) {
        try { Wait-ChildExit $testChild 'hold-test-cleanup' 60; $testChild = $null } catch { Write-ScenarioLog "Hold-test cleanup is still incomplete: $($_.Exception.Message)" }
    }
    if ($stopChild) {
        try { Wait-ChildExit $stopChild 'graceful-stop-cleanup' 330; $stopChild = $null } catch { Write-ScenarioLog "Graceful-stop cleanup is still incomplete: $($_.Exception.Message)" }
    }
    if ($testChild) { try { $testChild.Process.Dispose() } catch {} }
    if ($stopChild) { try { $stopChild.Process.Dispose() } catch {} }
    if($identityPrepared){
        $live=Get-OwnedServer
        if($live){
            $currentIdentity=$null
            if(Test-Path -LiteralPath $launcherIdentity){try{$currentIdentity=Get-Content -LiteralPath $launcherIdentity -Raw|ConvertFrom-Json -ErrorAction Stop}catch{}}
            $matchesLive=$currentIdentity -and [long]$currentIdentity.pid -eq [long]$live.ProcessId -and ([DateTimeOffset]$live.CreationDate.ToUniversalTime()).ToUnixTimeMilliseconds() -eq ([DateTimeOffset]([DateTime]$currentIdentity.created).ToUniversalTime()).ToUnixTimeMilliseconds()
            if(!$matchesLive){
                $stage0Identity=Get-Content -LiteralPath (Join-Path $run 'process.json') -Raw|ConvertFrom-Json
                if([long]$stage0Identity.pid -ne [long]$live.ProcessId -or ([DateTimeOffset]$live.CreationDate.ToUniversalTime()).ToUnixTimeMilliseconds() -ne ([DateTimeOffset]([DateTime]$stage0Identity.created).ToUniversalTime()).ToUnixTimeMilliseconds()){throw 'Stage 0 runtime identity does not match the restarted launcher process'}
                [IO.File]::WriteAllText($launcherIdentity,(@{pid=[long]$live.ProcessId;created=$live.CreationDate.ToUniversalTime().ToString('o');workerPid=[long]$stage0Identity.launcherPid;mode='local'}|ConvertTo-Json),$utf8)
            }
        }elseif(Test-Path -LiteralPath $launcherIdentity){Remove-Item -LiteralPath $launcherIdentity -Force}
    }
    foreach ($pair in @(@($fixture,'generated-stage11-drain-fixture.properties'),@($ready,'generated-stage11-drain-ready.txt'),@($release,'generated-stage11-drain-release.txt'))) {
        if ((Test-Path -LiteralPath $pair[0]) -and (Test-Path -LiteralPath $stateBackup)) {
            Copy-Item -LiteralPath $pair[0] -Destination (Join-Path $stateBackup $pair[1]) -Force
        }
    }
    $isRunning = [bool](Get-OwnedServer)
    $terminalCleanup = Test-HarnessTerminalCleanup
    if (!$terminalCleanup -and $wasRunning -and !$isRunning) {
        try { Start-Stage0 } catch { Write-ScenarioLog "Restore failure: $($_.Exception.Message)"; if (!$failure) { $failure = $_.Exception.Message } }
    } elseif (!$terminalCleanup -and !$wasRunning -and $isRunning -and !$stopChild) {
        try { Invoke-ChildScript (Join-Path $PSScriptRoot 'Stage0.ps1') @('-Phase','Stop','-Root',$rootPath,'-Port',[string]$Port) 'restore-stop' } catch { Write-ScenarioLog "Restore failure: $($_.Exception.Message)"; if (!$failure) { $failure = $_.Exception.Message } }
    }
}

if ($failure) {
    Write-Output 'FAIL: Stage 11 shutdown scenario'
    Write-Output "Log: $LogPath"
    exit 1
}
Write-Output 'PASS: Stage 11 shutdown scenario'
Write-Output "Log: $LogPath"
exit 0
