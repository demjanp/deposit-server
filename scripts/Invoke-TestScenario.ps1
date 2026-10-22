[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_#+,*-]+$')][string]$SeedTest,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_#+,*-]+$')][string]$VerifyTest,
    [Parameter(Mandatory=$true)][ValidateSet('Restart','KillRestart','ProcessCrashRestart','StartupCrashRestart')][string]$Transition,
    [ValidateSet('resource','history')][string]$CrashSuite,
    [string]$CrashPoint,
    [ValidateRange(1024,65535)][int]$Port = 17770,
    [string]$Root = (Join-Path $PSScriptRoot '..\..\test_harness'),
    [string]$LogPath,
    [ValidateRange(30,3600)][int]$TestTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ScenarioPrimitives.ps1')
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
Assert-HarnessRoot $Root
$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\')
. "$PSScriptRoot\IntegrationRunContext.ps1"
$activeRun=Get-ValidatedIntegrationRunContext $rootPath
if(!$activeRun) {
    $ownerParameters=@{RecoverySeedTest=$SeedTest;RecoveryVerifyTest=$VerifyTest;RecoveryTransition=$Transition;Root=$rootPath;Port=$Port;RecoveryTestTimeoutSeconds=$TestTimeoutSeconds}
    if($CrashSuite){$ownerParameters.RecoveryCrashSuite=$CrashSuite}
    if($CrashPoint){$ownerParameters.RecoveryCrashPoint=$CrashPoint}
    if($LogPath){$ownerParameters.RecoveryLogPath=[IO.Path]::GetFullPath($LogPath)}
    & (Join-Path $PSScriptRoot 'Invoke-IntegrationSuite.ps1') @ownerParameters
    return
}
Assert-HarnessRoot $rootPath
if (!$LogPath) {
    $directory = Join-Path (Join-Path $PSScriptRoot '..\..\test_harness') 'logs'
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $LogPath = Join-Path $directory ((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ') + '-scenario.log')
}
$LogPath = [IO.Path]::GetFullPath($LogPath)
New-Item -ItemType Directory -Force -Path (Split-Path $LogPath -Parent) | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$log = New-Object System.Collections.Generic.List[string]
$run = Join-Path $rootPath 'runs\test-host'

function Write-ScenarioLog([string]$Text) {
    $log.Add($Text)
    [IO.File]::WriteAllLines($LogPath,$log,$utf8)
}
function Get-OwnedServer {
    $identityPath=Join-Path $run 'process.json'
    if(Test-Path -LiteralPath $identityPath) {
        $identity=Get-Content -LiteralPath $identityPath -Raw|ConvertFrom-Json
        if($identity.PSObject.Properties['mode'] -and $identity.mode -eq 'service') {
            $service=Get-CimInstance Win32_Service -Filter "Name='$($identity.serviceName)'"
            if(!$service -or [string]$service.State -eq 'Stopped'){return $null}
            . (Join-Path $PSScriptRoot 'Request-DepositStop.ps1')
            $state=Get-Content (Join-Path $run 'distribution\deposit.install.json') -Raw|ConvertFrom-Json
            $authorizedHome=[IO.Path]::GetFullPath((Join-Path $run 'neo4j')).TrimEnd('\')
            if($state.mode -ne 'service' -or $identity.serviceName -cne [string]$state.serviceName -or $identity.serviceAccount -cne [string]$state.serviceAccount -or ![string]::Equals([IO.Path]::GetFullPath([string]$identity.neo4jHome).TrimEnd('\'),$authorizedHome,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned Deposit service record does not match the configured installation'}
            $bound=Get-DepositServiceProcessIdentity $authorizedHome ([string]$state.serviceName) ([string]$state.serviceAccount)
            if($bound.serviceAccount -ine [string]$identity.serviceAccount -or $bound.runtimeSid -cne [string]$identity.runtimeSid){throw 'Owned Deposit service identity changed'}
            $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($bound.pid)"
            if([long]$identity.pid -ne [long]$bound.pid -or [long]$identity.startMillis -ne [long]$bound.startMillis){
                [IO.File]::WriteAllText($identityPath,(@{pid=$bound.pid;created=$process.CreationDate.ToUniversalTime().ToString('o');startMillis=$bound.startMillis;runtimeSid=$bound.runtimeSid;serviceName=$bound.name;serviceAccount=$bound.serviceAccount;neo4jHome=$authorizedHome;mode='service';releaseMode=$true}|ConvertTo-Json -Depth 5),(New-Object Text.UTF8Encoding($false)))
            }
            return $process
        }
    }
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains((Join-Path $run 'neo4j')) -and
        $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'
    } | Select-Object -First 1
}
function Format-PowerShellArguments([string[]]$Values) {
    ($Values | ForEach-Object { if ([string]$_ -match '^-') { [string]$_ } else { Quote-ScenarioPowerShellString ([string]$_) } }) -join ' '
}
function Complete-PowerShellChild($Child,[string]$OutputPath,[string]$ErrorPath,[int]$TimeoutMilliseconds) {
    $process = $Child.Process
    if (!$process.WaitForExit($TimeoutMilliseconds)) { $process.Kill(); $null = $process.WaitForExit(15000); throw 'Scenario subprocess timed out.' }
    $process.WaitForExit()
    $stdout = Get-ScenarioChildOutput $Child.StandardOutput 'Scenario stdout collection failed: ' 'Scenario child exited, but a descendant kept stdout open; remaining output was omitted.'
    $stderr = Get-ScenarioChildOutput $Child.StandardError 'Scenario stderr collection failed: ' 'Scenario child exited, but a descendant kept stderr open; remaining output was omitted.'
    [IO.File]::WriteAllText($OutputPath,$stdout,$utf8)
    [IO.File]::WriteAllText($ErrorPath,$stderr,$utf8)
    return $process.ExitCode
}
function Invoke-ChildScript([string]$ScriptPath,[string[]]$ScriptArguments,[string]$Label,[int]$TimeoutMilliseconds=180000) {
    $childLog = [IO.Path]::ChangeExtension($LogPath,".$Label.log")
    $childErrorLog = [IO.Path]::ChangeExtension($LogPath,".$Label.err.log")
    $command = '& ' + (Quote-ScenarioPowerShellString $ScriptPath) + ' ' + (Format-PowerShellArguments $ScriptArguments)
    $child = Start-ScenarioPowerShellChild $command 'Could not start the scenario subprocess.'
    $timeout = if ($Label -eq 'harness-stop') { 330000 } else { $TimeoutMilliseconds }
    $code = Complete-PowerShellChild $child $childLog $childErrorLog $timeout
    foreach ($line in Get-Content -LiteralPath $childLog -ErrorAction SilentlyContinue) { Write-ScenarioLog ([string]$line) }
    foreach ($line in Get-Content -LiteralPath $childErrorLog -ErrorAction SilentlyContinue) { Write-ScenarioLog ([string]$line) }
    if ($code -ne 0) { throw "$Label failed; see $childLog" }
}
function Invoke-HarnessPhase([string]$Phase) {
    Invoke-ChildScript "$PSScriptRoot\Invoke-TestHarnessPhase.ps1" @('-Phase',$Phase,'-Root',$rootPath,'-Port',[string]$Port) "harness-phase-$Phase"
}
function Invoke-SelectedTest([string]$Selection,[string]$Label) {
    $testLog = [IO.Path]::ChangeExtension($LogPath,".$Label.log")
    $childArguments = @('-Kind','Integration','-Test',$Selection,'-TestMode',$(if($Label -eq 'seed'){'seed'}else{'verify'}),'-Port',[string]$Port,'-Root',$rootPath,'-LogPath',$testLog)
    if ($CrashPoint) { $childArguments += @('-CrashPoint',$CrashPoint) }
    Invoke-ChildScript "$PSScriptRoot\Invoke-Tests.ps1" $childArguments "$Label-runner" ($TestTimeoutSeconds*1000)
}
function Assert-StartupMarkerPermissions {
    $markerDirectory=Join-Path $run 'resources\history-startup-test'
    $armMarker=Join-Path $markerDirectory 'arm.txt'
    if(!(Test-Path -LiteralPath $markerDirectory -PathType Container) -or !(Test-Path -LiteralPath $armMarker -PathType Leaf)){throw 'Startup marker directory or arm marker was not created.'}
    $installationState=Get-Content -LiteralPath (Join-Path $run 'distribution\deposit.install.json') -Raw | ConvertFrom-Json
    if($installationState.mode -ne 'service' -or !$installationState.serviceAccount){throw 'Startup marker permission check requires the configured service installation.'}
    $serviceSid=(New-Object Security.Principal.NTAccount([string]$installationState.serviceAccount)).Translate([Security.Principal.SecurityIdentifier]).Value
    $modify=[int][Security.AccessControl.FileSystemRights]::Modify
    foreach($path in @($markerDirectory,$armMarker)){
        $acl=Get-Acl -LiteralPath $path
        $serviceCanReadWrite=$false
        foreach($rule in $acl.Access){
            try{$ruleSid=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value}catch{continue}
            if($ruleSid -eq $serviceSid -and $rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and $rule.IsInherited -and (([int]$rule.FileSystemRights -band $modify) -eq $modify)){$serviceCanReadWrite=$true;break}
        }
        if(!$serviceCanReadWrite){throw "Startup marker path does not inherit Modify access for service account $($installationState.serviceAccount): $path"}
    }
    Write-ScenarioLog "Verified inherited service Modify access on startup marker directory and arm file for $($installationState.serviceAccount)."
}

if ($Transition -in @('ProcessCrashRestart','StartupCrashRestart') -and (!$CrashSuite -or !$CrashPoint)) { throw 'Crash transitions require -CrashSuite and -CrashPoint.' }
if ($Transition -eq 'Restart' -and ($CrashSuite -or $CrashPoint)) { throw 'Crash settings are valid only for a crash transition.' }

$wasRunning = [bool](Get-OwnedServer)
$failure = $null
try {
    if (!$wasRunning) { Invoke-HarnessPhase 'Start' }
    $ownedRecord=if(Test-Path -LiteralPath (Join-Path $run 'process.json')){Get-Content (Join-Path $run 'process.json') -Raw|ConvertFrom-Json}else{$null}
    if($Transition -eq 'ProcessCrashRestart' -and $ownedRecord -and $ownedRecord.PSObject.Properties['mode'] -and $ownedRecord.mode -eq 'service') {
        Invoke-ChildScript "$PSScriptRoot\Test-KillTestOwnedJvm.ps1" @() 'crash-helper-refusal-checks'
    }
    Invoke-SelectedTest $SeedTest 'seed'
    switch ($Transition) {
        Restart { Invoke-HarnessPhase 'Stop'; Invoke-HarnessPhase 'Start' }
        KillRestart { Invoke-HarnessPhase 'Kill'; Invoke-HarnessPhase 'Start' }
        ProcessCrashRestart {
            if (Get-OwnedServer) { throw 'The selected crash test returned while its owned server is still running.' }
            Invoke-HarnessPhase 'Start'
        }
        StartupCrashRestart {
            Invoke-HarnessPhase 'Stop'
            if ($CrashSuite -eq 'history') {
                Assert-StartupMarkerPermissions
                $markerDirectory=Join-Path $run 'resources\history-startup-test'
                $marker = Join-Path $markerDirectory 'paused.txt'
                $childLog = [IO.Path]::ChangeExtension($LogPath,'.startup.log')
                $childErrorLog = [IO.Path]::ChangeExtension($LogPath,'.startup.err.log')
                $command = '& ' + (Quote-ScenarioPowerShellString "$PSScriptRoot\Invoke-TestHarnessPhase.ps1") + ' ' + (Format-PowerShellArguments @('-Phase','Start','-Root',$rootPath,'-Port',[string]$Port))
                $starter = Start-ScenarioPowerShellChild $command 'Could not start the scenario subprocess.'
                try {
                    $deadline = [DateTime]::UtcNow.AddSeconds(60)
                    while (!(Test-Path -LiteralPath $marker) -and [DateTime]::UtcNow -lt $deadline -and !$starter.Process.HasExited) { Start-Sleep -Milliseconds 100 }
                    if (!(Test-Path -LiteralPath $marker) -or !(Get-Content -LiteralPath $marker -Raw).StartsWith($CrashPoint + '/')) {
                        $barrierFailure=if($starter.Process.HasExited){'Expected startup recovery barrier was not reached before the startup subprocess exited.'}else{'Expected startup recovery barrier was not reached within 60 seconds.'}
                        if(!$starter.Process.HasExited){$starter.Process.Kill();$null=$starter.Process.WaitForExit(15000)}
                        $startCode=Complete-PowerShellChild $starter $childLog $childErrorLog 15000
                        foreach($line in Get-Content -LiteralPath $childLog -ErrorAction SilentlyContinue){Write-ScenarioLog ([string]$line)}
                        foreach($line in Get-Content -LiteralPath $childErrorLog -ErrorAction SilentlyContinue){Write-ScenarioLog ([string]$line)}
                        throw "$barrierFailure Startup subprocess exit code $startCode; diagnostics saved to $childLog and $childErrorLog."
                    }
                    $barrierReachedUtc=[DateTime]::UtcNow
                    Invoke-SelectedTest 'StartupReadinessIT#incompleteInitializationExcludesReadinessAndDownloadListener' 'recovery-gating'
                    Invoke-ChildScript "$PSScriptRoot\Kill-TestOwnedJvm.ps1" @() 'kill-startup-jvm'
                    $crashElapsed=[DateTime]::UtcNow-$barrierReachedUtc
                    if($crashElapsed.TotalSeconds -ge 120){throw "Recorded JVM termination occurred after the 120-second startup probe timeout ($([math]::Round($crashElapsed.TotalSeconds,1)) seconds)."}
                    # TestHarness retains its normal 90-second readiness deadline after
                    # the controlled JVM crash; observe that failure to completion.
                    $startCode = Complete-PowerShellChild $starter $childLog $childErrorLog 180000
                    foreach ($line in Get-Content -LiteralPath $childLog -ErrorAction SilentlyContinue) { Write-ScenarioLog ([string]$line) }
                    foreach ($line in Get-Content -LiteralPath $childErrorLog -ErrorAction SilentlyContinue) { Write-ScenarioLog ([string]$line) }
                    if ($startCode -eq 0) { throw 'Startup attempt unexpectedly passed after its JVM was terminated.' }
                } finally { if (!$starter.Process.HasExited) { $starter.Process.Kill(); $null = $starter.Process.WaitForExit(15000) } }
                Invoke-HarnessPhase 'Start'
            } else { throw 'StartupCrashRestart currently supports history startup scenarios.' }
        }
    }
    Invoke-SelectedTest $VerifyTest 'verify'
} catch {
    $failure = $_.Exception.Message
    Write-ScenarioLog "FAIL: $failure"
} finally {
    $terminalCleanup = Test-HarnessTerminalCleanup
    $isRunning = [bool](Get-OwnedServer)
    if (!$terminalCleanup -and $wasRunning -and !$isRunning) {
        try { Invoke-HarnessPhase 'Start' } catch { Write-ScenarioLog "Restore failure: $($_.Exception.Message)"; if (!$failure) { $failure = $_.Exception.Message } }
    } elseif (!$terminalCleanup -and !$wasRunning -and $isRunning) {
        try { Invoke-HarnessPhase 'Stop' } catch { Write-ScenarioLog "Restore failure: $($_.Exception.Message)"; if (!$failure) { $failure = $_.Exception.Message } }
    }
}

if ($failure) {
    Write-Output 'FAIL: selected test scenario'
    Write-Output "Log: $LogPath"
    exit 1
}
Write-Output 'PASS: selected test scenario'
Write-Output "Log: $LogPath"
exit 0
