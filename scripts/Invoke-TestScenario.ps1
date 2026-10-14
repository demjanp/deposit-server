[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_#+,*-]+$')][string]$SeedTest,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_#+,*-]+$')][string]$VerifyTest,
    [Parameter(Mandatory=$true)][ValidateSet('Restart','KillRestart','ProcessCrashRestart','StartupCrashRestart')][string]$Transition,
    [ValidateSet('stage4','stage5')][string]$CrashSuite,
    [string]$CrashPoint,
    [ValidateRange(1024,65535)][int]$Port = 17770,
    [string]$Root = 'C:\data_processed\Deposit_v2\stage0',
    [string]$LogPath
)

$ErrorActionPreference = 'Stop'
$approvedRoot = [IO.Path]::GetFullPath('C:\data_processed\Deposit_v2\stage0').TrimEnd('\')
$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\')
if (![string]::Equals($rootPath,$approvedRoot,[StringComparison]::OrdinalIgnoreCase)) { throw "Scenario runner is limited to $approvedRoot" }
if (!$LogPath) {
    $directory = Join-Path $env:TEMP 'DepositTests'
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $LogPath = Join-Path $directory ((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ') + '-scenario.log')
}
$LogPath = [IO.Path]::GetFullPath($LogPath)
New-Item -ItemType Directory -Force -Path (Split-Path $LogPath -Parent) | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$log = New-Object System.Collections.Generic.List[string]
$run = Join-Path $rootPath 'runs\stage0-host'

function Write-ScenarioLog([string]$Text) {
    $log.Add($Text)
    [IO.File]::WriteAllLines($LogPath,$log,$utf8)
}
function Get-OwnedServer {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains((Join-Path $run 'neo4j')) -and
        $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'
    } | Select-Object -First 1
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
    if (!$process.Start()) { throw 'Could not start the scenario subprocess.' }
    return [pscustomobject]@{ Process=$process; StandardOutput=$process.StandardOutput.ReadToEndAsync(); StandardError=$process.StandardError.ReadToEndAsync() }
}
function Complete-PowerShellChild($Child,[string]$OutputPath,[string]$ErrorPath,[int]$TimeoutMilliseconds) {
    $process = $Child.Process
    if (!$process.WaitForExit($TimeoutMilliseconds)) { $process.Kill(); $null = $process.WaitForExit(15000); throw 'Scenario subprocess timed out.' }
    $process.WaitForExit()
    $stdout = if ($Child.StandardOutput.Wait(5000)) { try { $Child.StandardOutput.Result } catch { "Scenario stdout collection failed: $($_.Exception.Message)" } }
        else { 'Scenario child exited, but a descendant kept stdout open; remaining output was omitted.' }
    $stderr = if ($Child.StandardError.Wait(5000)) { try { $Child.StandardError.Result } catch { "Scenario stderr collection failed: $($_.Exception.Message)" } }
        else { 'Scenario child exited, but a descendant kept stderr open; remaining output was omitted.' }
    [IO.File]::WriteAllText($OutputPath,$stdout,$utf8)
    [IO.File]::WriteAllText($ErrorPath,$stderr,$utf8)
    return $process.ExitCode
}
function Invoke-ChildScript([string]$ScriptPath,[string[]]$ScriptArguments,[string]$Label) {
    $childLog = [IO.Path]::ChangeExtension($LogPath,".$Label.log")
    $childErrorLog = [IO.Path]::ChangeExtension($LogPath,".$Label.err.log")
    $command = '& ' + (Quote-PowerShell $ScriptPath) + ' ' + (Format-PowerShellArguments $ScriptArguments)
    $child = Start-PowerShellChild $command
    $code = Complete-PowerShellChild $child $childLog $childErrorLog 180000
    foreach ($line in Get-Content -LiteralPath $childLog -ErrorAction SilentlyContinue) { Write-ScenarioLog ([string]$line) }
    foreach ($line in Get-Content -LiteralPath $childErrorLog -ErrorAction SilentlyContinue) { Write-ScenarioLog ([string]$line) }
    if ($code -ne 0) { throw "$Label failed; see $childLog" }
}
function Invoke-Stage0([string]$Phase) {
    Invoke-ChildScript "$PSScriptRoot\Stage0.ps1" @('-Phase',$Phase,'-Root',$rootPath,'-Port',[string]$Port) "stage0-$Phase"
}
function Invoke-SelectedTest([string]$Selection,[string]$Label) {
    $testLog = [IO.Path]::ChangeExtension($LogPath,".$Label.log")
    $childArguments = @('-Kind','Integration','-Test',$Selection,'-TestMode',$(if($Label -eq 'seed'){'seed'}else{'verify'}),'-Port',[string]$Port,'-Root',$rootPath,'-LogPath',$testLog)
    if ($CrashPoint) { $childArguments += @('-CrashPoint',$CrashPoint) }
    Invoke-ChildScript "$PSScriptRoot\Invoke-Tests.ps1" $childArguments "$Label-runner"
}

if ($Transition -in @('ProcessCrashRestart','StartupCrashRestart') -and (!$CrashSuite -or !$CrashPoint)) { throw 'Crash transitions require -CrashSuite and -CrashPoint.' }
if ($Transition -eq 'Restart' -and ($CrashSuite -or $CrashPoint)) { throw 'Crash settings are valid only for a crash transition.' }

$wasRunning = [bool](Get-OwnedServer)
$failure = $null
try {
    if (!$wasRunning) { Invoke-Stage0 'Start' }
    Invoke-SelectedTest $SeedTest 'seed'
    switch ($Transition) {
        Restart { Invoke-Stage0 'Stop'; Invoke-Stage0 'Start' }
        KillRestart { Invoke-Stage0 'Kill'; Invoke-Stage0 'Start' }
        ProcessCrashRestart {
            if (Get-OwnedServer) { throw 'The selected crash test returned while its owned server is still running.' }
            Invoke-Stage0 'Start'
        }
        StartupCrashRestart {
            Invoke-Stage0 'Stop'
            if ($CrashSuite -eq 'stage5') {
                $marker = Join-Path $run 'stage5-startup-paused.txt'
                $childLog = [IO.Path]::ChangeExtension($LogPath,'.startup.log')
                $childErrorLog = [IO.Path]::ChangeExtension($LogPath,'.startup.err.log')
                $command = '& ' + (Quote-PowerShell "$PSScriptRoot\Stage0.ps1") + ' ' + (Format-PowerShellArguments @('-Phase','Start','-Root',$rootPath,'-Port',[string]$Port))
                $starter = Start-PowerShellChild $command
                try {
                    $deadline = [DateTime]::UtcNow.AddSeconds(60)
                    while (!(Test-Path -LiteralPath $marker) -and [DateTime]::UtcNow -lt $deadline -and !$starter.Process.HasExited) { Start-Sleep -Milliseconds 100 }
                    if (!(Test-Path -LiteralPath $marker) -or !(Get-Content -LiteralPath $marker -Raw).StartsWith($CrashPoint + '/')) { throw 'Expected startup recovery barrier was not reached.' }
                    [IO.File]::WriteAllText((Join-Path $run 'stage5-startup-release.txt'),'continue')
                    Invoke-ChildScript "$PSScriptRoot\Kill-TestOwnedJvm.ps1" @() 'kill-startup-jvm'
                    $startCode = Complete-PowerShellChild $starter $childLog $childErrorLog 60000
                    if ($startCode -eq 0) { throw 'Startup attempt unexpectedly passed after its JVM was terminated.' }
                    foreach ($line in Get-Content -LiteralPath $childLog -ErrorAction SilentlyContinue) { Write-ScenarioLog ([string]$line) }
                    foreach ($line in Get-Content -LiteralPath $childErrorLog -ErrorAction SilentlyContinue) { Write-ScenarioLog ([string]$line) }
                } finally { if (!$starter.Process.HasExited) { $starter.Process.Kill(); $null = $starter.Process.WaitForExit(15000) } }
                Invoke-Stage0 'Start'
            } else { throw 'StartupCrashRestart currently supports Stage 5 history startup scenarios.' }
        }
    }
    Invoke-SelectedTest $VerifyTest 'verify'
} catch {
    $failure = $_.Exception.Message
    Write-ScenarioLog "FAIL: $failure"
} finally {
    $isRunning = [bool](Get-OwnedServer)
    if ($wasRunning -and !$isRunning) {
        try { Invoke-Stage0 'Start' } catch { Write-ScenarioLog "Restore failure: $($_.Exception.Message)"; if (!$failure) { $failure = $_.Exception.Message } }
    } elseif (!$wasRunning -and $isRunning) {
        try { Invoke-Stage0 'Stop' } catch { Write-ScenarioLog "Restore failure: $($_.Exception.Message)"; if (!$failure) { $failure = $_.Exception.Message } }
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
