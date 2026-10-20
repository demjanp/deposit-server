[CmdletBinding()]
param(
    [ValidateRange(1024,65535)][int]$Port = 17770,
    [string]$Root = (Join-Path $PSScriptRoot '..\..\test_harness'),
    [string]$LogPath,
    [ValidatePattern('^[A-Za-z0-9_#+,*-]*$')][string]$ProbeTest='DataImportExportIT',
    [ValidateSet('chunk-flushed','publication-before','publication-after','ready-before-commit','staging-cleanup-before','staging-cleanup-after','collection-before','collection-after','collection-partial')][string]$CrashPoint='collection-before',
    [switch]$HistoryRecoveryChecks,
    [ValidateSet('chunk-flushed','publication-before','publication-after','ready-before-commit','staging-cleanup-before','staging-cleanup-after','collection-before','collection-after','collection-partial')][string[]]$AdditionalCrashPoints=@()
)

$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
Assert-HarnessRoot $Root
$rootPath=[IO.Path]::GetFullPath($Root).TrimEnd('\')
Assert-HarnessRoot $rootPath
if(!$LogPath){$directory=Join-Path (Join-Path $PSScriptRoot '..\..\test_harness') 'logs';New-Item -ItemType Directory -Force -Path $directory|Out-Null;$LogPath=Join-Path $directory ((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ')+'-operations-service-crash.log')}
$LogPath=[IO.Path]::GetFullPath($LogPath)
New-Item -ItemType Directory -Force -Path (Split-Path $LogPath -Parent)|Out-Null
$run=Join-Path $rootPath 'runs\test-host'
$neo4j=Join-Path $run 'neo4j'
$distribution=Join-Path $run 'distribution'
$launcher=Join-Path $distribution 'deposit.bat'
$statePath=Join-Path $distribution 'deposit.install.json'
$config=Join-Path $neo4j 'conf\neo4j.conf'
$probe=Join-Path $rootPath 'build\deposit-runtime-probe\target\deposit-runtime-probe-0.0.0-dev.jar'
$probeTarget=Join-Path $neo4j 'plugins\deposit-runtime-probe-0.0.0-dev.jar'
$utf8=New-Object Text.UTF8Encoding($false)
$log=New-Object System.Collections.Generic.List[string]
$evidence=Join-Path (Split-Path $LogPath -Parent) ([IO.Path]::GetFileNameWithoutExtension($LogPath)+'.preserved-state')
$configBackup=Join-Path $evidence 'neo4j.conf'
$hadProbe=$false
$failure=$null
$configChanged=$false
$probeAdded=$false
$state=$null
$serviceName=$null

function Write-ScenarioLog([string]$Text){$log.Add($Text);[IO.File]::WriteAllLines($LogPath,$log,$utf8)}
function Wait-ServiceStatus([string]$Name,[string]$Status,[int]$Seconds){$deadline=[DateTime]::UtcNow.AddSeconds($Seconds);do{$service=Get-Service -Name $Name -ErrorAction SilentlyContinue;if($service -and [string]$service.Status -eq $Status){return};Start-Sleep -Milliseconds 250}while([DateTime]::UtcNow -lt $deadline);throw "Service $Name did not reach $Status within $Seconds seconds"}
function Wait-Bolt([int]$Seconds){$deadline=[DateTime]::UtcNow.AddSeconds($Seconds);do{$client=New-Object Net.Sockets.TcpClient;try{$pending=$client.BeginConnect([Net.IPAddress]::Loopback,$Port,$null,$null);if($pending.AsyncWaitHandle.WaitOne(500)){$client.EndConnect($pending);return}}catch{}finally{$client.Dispose()};Start-Sleep -Milliseconds 250}while([DateTime]::UtcNow -lt $deadline);throw "Bolt listener 127.0.0.1:$Port did not become reachable"}
function Invoke-Child([string]$Script,[string[]]$Arguments,[string]$Label,[int]$TimeoutSeconds=600){
    $stdout=Join-Path (Split-Path $LogPath -Parent) "$Label.stdout.log";$stderr=Join-Path (Split-Path $LogPath -Parent) "$Label.stderr.log"
    $child=Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList (@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$Script)+$Arguments) -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    try{[void]$child.Handle;if(!$child.WaitForExit($TimeoutSeconds*1000)){throw "$Label timed out; see $LogPath"};$child.Refresh();if(!$child.HasExited -or $null -eq $child.ExitCode){throw "$Label did not return an available exit code"};$code=[int]$child.ExitCode}finally{$child.Dispose()}
    foreach($line in Get-Content -LiteralPath $stdout -ErrorAction SilentlyContinue){Write-ScenarioLog "$Label stdout: $line"}
    foreach($line in Get-Content -LiteralPath $stderr -ErrorAction SilentlyContinue){Write-ScenarioLog "$Label stderr: $line"}
    if($code -ne 0){throw "$Label failed with exit code $code"}
}

try{
    if(!(Test-Path -LiteralPath $launcher -PathType Leaf) -or !(Test-Path -LiteralPath $config -PathType Leaf) -or !(Test-Path -LiteralPath $probe -PathType Leaf)){throw 'Packaged launcher, Neo4j configuration, or test-only probe artifact is missing.'}
    . (Join-Path $PSScriptRoot 'Credentials.ps1')
    $state=Get-Content -LiteralPath $statePath -Raw|ConvertFrom-Json
    if($state.mode -ne 'service' -or !$state.serviceName -or !$state.serviceAccount){throw 'Crash scenario requires the configured service-mode installation.'}
    $serviceName=[string]$state.serviceName
    if(!(Get-Service -Name $serviceName -ErrorAction SilentlyContinue)){throw "Configured Deposit service $serviceName is not installed."}
    New-Item -ItemType Directory -Path $evidence|Out-Null
    Set-OwnerOnlyDirectory $evidence
    Copy-Item -LiteralPath $config -Destination $configBackup
    $hadProbe=Test-Path -LiteralPath $probeTarget
    if($hadProbe){Copy-Item -LiteralPath $probeTarget -Destination (Join-Path $evidence 'prior-probe.jar')}
    & $launcher stop
    if($LASTEXITCODE -ne 0){throw "Packaged service stop failed before enabling the crash probe ($LASTEXITCODE)"}
    Wait-ServiceStatus $serviceName 'Stopped' 90
    Copy-Item -LiteralPath $probe -Destination $probeTarget -Force
    $probeAdded=!$hadProbe
    $configuration=[IO.File]::ReadAllText($config)
    $allowlist='dbms.security.procedures.allowlist=deposit.*,testprobe.geometry.*,testprobe.security.*,testprobe.storage.*,apoc.version'
    if($configuration -match '(?m)^dbms\.security\.procedures\.allowlist=.*$'){$configuration=[regex]::Replace($configuration,'(?m)^dbms\.security\.procedures\.allowlist=.*$',[System.Text.RegularExpressions.MatchEvaluator]{param($match)$allowlist})}else{$configuration+="`r`n$allowlist`r`n"}
    if($configuration -notmatch '(?m)^dbms\.security\.procedures\.unrestricted='){$configuration+="dbms.security.procedures.unrestricted=testprobe.geometry.*,testprobe.security.*,testprobe.storage.*`r`n"}
    [IO.File]::WriteAllText($config,$configuration,$utf8);$configChanged=$true
    Write-ScenarioLog 'Enabled the test-only test harness probe on the isolated test harness host; the original config and prior probe state are preserved.'
    Invoke-Child (Join-Path $PSScriptRoot 'Invoke-TestHarnessPhase.ps1') @('-Phase','Start','-Root',$rootPath,'-Port',[string]$Port) 'probe-service-start' 300
    if($ProbeTest){Invoke-Child (Join-Path $PSScriptRoot 'Invoke-Tests.ps1') @('-Kind','Integration','-Test',$ProbeTest,'-Port',[string]$Port,'-Root',$rootPath,'-LogPath',[IO.Path]::ChangeExtension($LogPath,'.probe-selection.log')) 'probe-selection' 1200}
    if($HistoryRecoveryChecks){
        foreach($point in @('history-before-receipt','history-after-commit')){
            Invoke-Child (Join-Path $PSScriptRoot 'Invoke-TestScenario.ps1') @('-SeedTest','HistoryCrashRecoveryIT#triggerAction','-VerifyTest','HistoryCrashRecoveryIT#verifyAction','-Transition','ProcessCrashRestart','-CrashSuite','history','-CrashPoint',$point,'-Port',[string]$Port,'-Root',$rootPath,'-LogPath',[IO.Path]::ChangeExtension($LogPath,".$point.log")) $point 600
        }
        Invoke-Child (Join-Path $PSScriptRoot 'Invoke-TestScenario.ps1') @('-SeedTest','HistoryCrashRecoveryIT#seedStartup','-VerifyTest','HistoryCrashRecoveryIT#verifyStartup','-Transition','StartupCrashRestart','-CrashSuite','history','-CrashPoint','history-cleanup-batch','-Port',[string]$Port,'-Root',$rootPath,'-LogPath',[IO.Path]::ChangeExtension($LogPath,'.history-startup-recovery.log')) 'history-startup-recovery' 600
    }
    & $launcher stop
    if($LASTEXITCODE -ne 0){throw "Could not drain the service after the test-only query export regression ($LASTEXITCODE)"}
    Wait-ServiceStatus $serviceName 'Stopped' 90
    $scenario=Join-Path $PSScriptRoot 'Invoke-TestScenario.ps1'
    foreach($point in @($CrashPoint)+$AdditionalCrashPoints){
        Invoke-Child $scenario @('-SeedTest','ResourceCrashRecoveryIT#trigger','-VerifyTest','ResourceCrashRecoveryIT#verify','-Transition','ProcessCrashRestart','-CrashSuite','resource','-CrashPoint',$point,'-Port',[string]$Port,'-Root',$rootPath,'-LogPath',[IO.Path]::ChangeExtension($LogPath,".crash-$point.log")) ('real-service-crash-'+$point)
        Write-ScenarioLog "The selected $point test triggered a real process crash, restarted the service, and passed its recovery verifier."
    }
}catch{$failure=$_.Exception.Message;Write-ScenarioLog "FAIL: $failure"}
finally{
    $terminalCleanup=Test-HarnessTerminalCleanup
    try{
        if($serviceName){$service=Get-Service -Name $serviceName -ErrorAction SilentlyContinue;if($service -and $service.Status -ne 'Stopped'){Wait-Bolt 90;& $launcher stop;if($LASTEXITCODE -ne 0){throw "Could not drain the service before restoring the normal package ($LASTEXITCODE)"};Wait-ServiceStatus $serviceName 'Stopped' 90}}
        if($configChanged -and (Test-Path -LiteralPath $configBackup)){Copy-Item -LiteralPath $configBackup -Destination $config -Force;$configChanged=$false}
        if($probeAdded -and (Test-Path -LiteralPath $probeTarget)){Remove-Item -LiteralPath $probeTarget -Force;$probeAdded=$false}
        elseif($hadProbe -and (Test-Path -LiteralPath (Join-Path $evidence 'prior-probe.jar'))){Copy-Item -LiteralPath (Join-Path $evidence 'prior-probe.jar') -Destination $probeTarget -Force}
        if(!$terminalCleanup){
            if($serviceName){$service=Get-Service -Name $serviceName -ErrorAction SilentlyContinue;if($service -and $service.Status -eq 'Stopped'){& $launcher start;if($LASTEXITCODE -ne 0){throw "Normal service restart after probe restoration failed ($LASTEXITCODE)"};Wait-ServiceStatus $serviceName 'Running' 90;Wait-Bolt 90}}
            $verify=Join-Path $PSScriptRoot 'Invoke-Tests.ps1'
            Invoke-Child $verify @('-Kind','Integration','-Test','ResourceCrashRecoveryIT#verify','-Port',[string]$Port,'-Root',$rootPath,'-LogPath',[IO.Path]::ChangeExtension($LogPath,'.normal-package-crash-recovery-verify.log')) 'normal-package-crash-recovery-verify' 300
            Write-ScenarioLog 'The crash-recovery fixture verifier passed after restoring the normal package.'
            Invoke-Child $verify @('-Kind','Integration','-Test','ShutdownDrainIT#verifyCommittedTransactionSurvivedServiceShutdown','-Port',[string]$Port,'-Root',$rootPath,'-LogPath',[IO.Path]::ChangeExtension($LogPath,'.normal-package-verify.log')) 'normal-package-post-restore-verify' 300
        }
    }catch{Write-ScenarioLog "RESTORE FAILURE: $($_.Exception.Message)";if(!$failure){$failure=$_.Exception.Message}}
}

if($failure){Write-Output 'FAIL: operational service crash scenario';Write-Output "Log: $LogPath";exit 1}
Write-Output 'PASS: operational service crash scenario'
Write-Output "Log: $LogPath"
