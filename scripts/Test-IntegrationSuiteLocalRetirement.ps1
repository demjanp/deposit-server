[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
$fixture=Join-Path (Get-HarnessRoot) ('private\suite\local-retirement-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture -Force | Out-Null

# Load the exact production types/helpers without initializing the real DPAPI
# store. This regression owns only its isolated fixture, even without elevation.
. (Join-Path $PSScriptRoot 'IntegrationHarnessAcl.ps1')
$secretsAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'IntegrationHarnessSecrets.ps1'),[ref]$null,[ref]$null)
$plainPath=$secretsAst.Find({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Assert-HarnessPlainPath'},$true)
Invoke-Expression $plainPath.Extent.Text
$watchdogAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1'),[ref]$null,[ref]$null)
foreach($statement in $watchdogAst.EndBlock.Statements){
    if($statement -is [Management.Automation.Language.IfStatementAst] -and $statement.Extent.Text -match "^if \(-not \('DepositSuite(Jobs|JournalFiles)' ") {Invoke-Expression $statement.Extent.Text}
    if($statement -is [Management.Automation.Language.FunctionDefinitionAst]){Invoke-Expression $statement.Extent.Text}
}
function Get-HarnessRoot {return $fixture}
$run=Join-Path $fixture 'runs\test-host'
$runtimeHome=Join-Path $run 'neo4j'
$journalPath=Join-Path $fixture 'active.json'
$invocation=[Guid]::NewGuid().ToString('N')
$owned=New-Object 'System.Collections.Generic.List[object]'
$unrelated=$null
function Quote-Retirement([string]$Value){return "'"+$Value.Replace("'","''")+"'"}
function Write-CaseJournal($Value){
    if([IO.File]::Exists($journalPath)){[IO.File]::Delete($journalPath)}
    Write-SuiteJournal $journalPath ($Value|ConvertTo-Json -Depth 12)
}
function Wait-FixtureFile([string]$Path){
    $deadline=[DateTime]::UtcNow.AddSeconds(10)
    while(!(Test-Path -LiteralPath $Path)){
        if([DateTime]::UtcNow -ge $deadline){throw "Fixture did not publish $Path"}
        Start-Sleep -Milliseconds 25
    }
}
function Start-ContainedHolder([string]$Executable,[string]$Label,[ValidateSet('hold','spawn','drain')][string]$Mode='hold'){
    $gate=Join-Path $fixture ($Label+'.gate')
    $ready=Join-Path $fixture ($Label+'.ready')
    $log=Join-Path $run ($Label+'.log')
    $command='while(!(Test-Path -LiteralPath '+(Quote-Retirement $gate)+')){Start-Sleep -Milliseconds 25}; Start-Process -FilePath '+(Quote-Retirement $Executable)+' -WindowStyle Hidden -ArgumentList @('+(Quote-Retirement ('"'+$log+'"'))+','+(Quote-Retirement ('"'+$ready+'"'))+','+(Quote-Retirement $mode)+') | Out-Null'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $rootProcess=Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -WindowStyle Hidden -PassThru -ArgumentList @('-NoProfile','-NonInteractive','-EncodedCommand',$encoded)
    $null=$rootProcess.Handle
    $jobName='Local\DepositIT-'+$invocation+'-'+[Guid]::NewGuid().ToString('N')
    [DepositSuiteJobs]::Create($jobName,$rootProcess.Id)
    $entry=[pscustomobject]@{pid=$rootProcess.Id;startTicks=$rootProcess.StartTime.ToUniversalTime().Ticks;label=$Label;jobName=$jobName;jobAcknowledged=$true}
    $owned.Add($entry)
    try {
        [IO.File]::WriteAllText($gate,'go')
        if(!$rootProcess.WaitForExit(10000)){throw 'Fixture scenario root did not exit'}
        if($rootProcess.ExitCode -ne 0){throw 'Fixture scenario root failed'}
        Wait-FixtureFile $ready
    } finally {$rootProcess.Dispose()}
    return $entry
}
function Assert-Refusal([scriptblock]$Action,[string]$Expected){
    $refused=$false
    try{& $Action}catch{
        if($_.Exception.Message -notlike $Expected){throw}
        $refused=$true
    }
    if(!$refused){throw "Expected retirement refusal: $Expected"}
    $members=@([DepositSuiteJobs]::Members($entry.jobName))
    try{if(!$members.Count){throw 'Rejected retirement terminated an earlier validated job'}}finally{foreach($member in $members){$member.Dispose()}}
}

try {
    New-Item -ItemType Directory -Path $runtimeHome,(Join-Path $run 'distribution') -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $run 'distribution\deposit.install.json'),(@{mode='local';neo4jHome=$runtimeHome;serviceName='DepositNeo4j-fefefefe'}|ConvertTo-Json))
    $holderExe=Join-Path $fixture 'holder.exe'
    Add-Type -OutputAssembly $holderExe -OutputType ConsoleApplication -TypeDefinition @'
using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
public static class DepositRetirementHolder {
    public static void Main(string[] args) {
        using(var stream=File.Open(args[0],FileMode.Create,FileAccess.Write,FileShare.ReadWrite)) {
            byte[] content=System.Text.Encoding.UTF8.GetBytes("retained diagnostic output");
            stream.Write(content,0,content.Length);stream.Flush();
            File.WriteAllText(args[1],Process.GetCurrentProcess().Id.ToString());
            if(args.Length>2 && args[2]=="spawn") {
                Thread.Sleep(1500);
                Process.Start(new ProcessStartInfo(Process.GetCurrentProcess().MainModule.FileName,
                    "\""+args[0]+".descendant\" \""+args[1]+".descendant\" hold") {UseShellExecute=false,CreateNoWindow=true});
            }
            if(args.Length>2 && args[2]=="drain") {
                while(!File.Exists(args[0]+".exit"))Thread.Sleep(5);
                return;
            }
            Thread.Sleep(Timeout.Infinite);
        }
    }
}
'@
    $javaExe=Join-Path $fixture 'java.exe'
    [IO.File]::Copy($holderExe,$javaExe)
    $unrelatedReady=Join-Path $fixture 'unrelated.ready'
    $unrelated=Start-Process -FilePath $holderExe -WindowStyle Hidden -PassThru -ArgumentList @(('"'+(Join-Path $fixture 'unrelated.log')+'"'),('"'+$unrelatedReady+'"'),'hold')
    $null=$unrelated.Handle
    Wait-FixtureFile $unrelatedReady
    $entry=Start-ContainedHolder $holderExe 'retained'
    $runner=Get-Process -Id $PID
    try {$runnerTicks=$runner.StartTime.ToUniversalTime().Ticks} finally {$runner.Dispose()}
    $journal=[pscustomobject]@{schemaVersion=1;invocationId=$invocation;ownerSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;runnerPid=$PID;runnerStartTicks=$runnerTicks;neo4jHome=$runtimeHome;runtimeOwned=$true;serviceName=$null;serviceAccount=$null;installationPhase='none';children=@($entry);runtimeJobs=@();state='running';terminalCleanup=$false;watchdogAcknowledged=$true}
    Write-CaseJournal $journal
    $stream=[IO.File]::Open((Join-Path $run 'retained.log'),'Open','Read',([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    $sha=[Security.Cryptography.SHA256]::Create()
    try{$beforeHash=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','')}finally{$sha.Dispose();$stream.Dispose()}
    $blocked=$false
    try{[IO.Directory]::Move($run,$run+'.preserved')}catch [IO.IOException]{$blocked=$true}
    if(!$blocked){throw 'Fixture did not reproduce retained descendant directory lock'}
    Assert-Refusal {Invoke-SuiteLocalRetirement $journalPath ('0'*32)} '*active nonterminal invocation*'
    $journal.runnerStartTicks++
    Write-CaseJournal $journal
    Assert-Refusal {Invoke-SuiteLocalRetirement $journalPath $invocation} '*active nonterminal invocation*'
    $journal.runnerStartTicks=$runnerTicks
    $foreign=$entry|ConvertTo-Json|ConvertFrom-Json
    $foreign.jobName='Local\DepositIT-'+('0'*32)+'-'+[Guid]::NewGuid().ToString('N')
    $journal.children=@($entry,$foreign)
    Write-CaseJournal $journal
    Assert-Refusal {Invoke-SuiteLocalRetirement $journalPath $invocation} '*invalid scenario job identity*'
    $journal.children=@($entry,$entry)
    Write-CaseJournal $journal
    Assert-Refusal {Invoke-SuiteLocalRetirement $journalPath $invocation} '*invalid scenario job identity*'
    $missing=$entry|ConvertTo-Json|ConvertFrom-Json
    $missing.jobName='Local\DepositIT-'+$invocation+'-'+[Guid]::NewGuid().ToString('N')
    $journal.children=@($entry,$missing)
    Write-CaseJournal $journal
    Assert-Refusal {Invoke-SuiteLocalRetirement $journalPath $invocation} '*system cannot find the file specified*'
    $journal.children=@($entry)
    $journal.terminalCleanup=$true
    Write-CaseJournal $journal
    Assert-Refusal {Invoke-SuiteLocalRetirement $journalPath $invocation} '*active nonterminal invocation*'
    $journal.terminalCleanup=$false
    $live=$entry|ConvertTo-Json|ConvertFrom-Json
    $live.pid=$unrelated.Id;$live.startTicks=$unrelated.StartTime.ToUniversalTime().Ticks
    $journal.children=@($live)
    Write-CaseJournal $journal
    Assert-Refusal {Invoke-SuiteLocalRetirement $journalPath $invocation} '*live scenario root*'
    $journal.children=@($entry)
    Write-CaseJournal $journal
    $recordPath=Join-Path $run 'process.json'
    [IO.File]::WriteAllText($recordPath,(@{pid=$unrelated.Id;created=$unrelated.StartTime.ToUniversalTime().ToString('o')}|ConvertTo-Json))
    Assert-Refusal {Invoke-SuiteLocalRetirement $journalPath $invocation} '*live recorded runtime*'
    [IO.File]::Delete($recordPath)

    # Refuse a live JVM in a later job before touching the earlier locked job.
    $javaEntry=Start-ContainedHolder $javaExe 'runtime'
    $journal.children=@($entry,$javaEntry)
    Write-CaseJournal $journal
    Assert-Refusal {Invoke-SuiteLocalRetirement $journalPath $invocation} '*contained runtime process*'
    Stop-SuiteChildTree $javaEntry
    $journal.children=@($entry)
    Write-CaseJournal $journal
    # Missing CIM metadata is uncertainty, even if containment is known.
    function Get-CimInstance {
        param([string]$ClassName,[string]$Filter,[string]$ErrorAction)
        if($ClassName -eq 'Win32_Process' -and $Filter -match '^ProcessId='){return}
        return CimCmdlets\Get-CimInstance -ClassName $ClassName -Filter $Filter -ErrorAction Stop
    }
    try{Assert-Refusal {Invoke-SuiteLocalRetirement $journalPath $invocation} '*cannot verify a contained process*'}finally{Remove-Item Function:\Get-CimInstance}

    # A drain that cannot retire its members must prevent the service boundary.
    $productionStop=(Get-Item Function:\Stop-SuiteChildTree).ScriptBlock
    function Stop-SuiteChildTree {param($Identity,[switch]$PreserveRuntime,[DateTime]$Deadline,[switch]$RejectRuntime)}
    try{Assert-Refusal {Invoke-SuiteLocalRetirement $journalPath $invocation} '*service installation is forbidden*'}finally{Set-Item Function:\Stop-SuiteChildTree $productionStop}
    $beforeJournal=[IO.File]::ReadAllText($journalPath)
    Invoke-SuiteLocalRetirement $journalPath $invocation
    if([IO.File]::ReadAllText($journalPath) -cne $beforeJournal){throw 'Local retirement changed watchdog supervision state'}
    [IO.Directory]::Move($run,$run+'.preserved')
    [IO.Directory]::Move($run+'.preserved',$run)
    if((Get-FileHash (Join-Path $run 'retained.log')).Hash -cne $beforeHash){throw 'Local retirement changed preserved diagnostic contents'}
    if($unrelated.HasExited){throw 'Local retirement terminated an unrelated process'}

    $drainEntry=Start-ContainedHolder $holderExe 'natural' -Mode drain
    $journal.children=@($entry,$javaEntry,$drainEntry)
    Write-CaseJournal $journal
    function Get-CimInstance {
        param([string]$ClassName,[string]$Filter,[string]$ErrorAction)
        if($ClassName -eq 'Win32_Process' -and $Filter -match '^ProcessId='){
            [IO.File]::WriteAllText((Join-Path $run 'natural.log.exit'),'drain')
            $natural=[Diagnostics.Process]::GetProcessById([int]($Filter.Substring(10)))
            try{if(!$natural.WaitForExit(5000)){throw 'Fixture failed to drain naturally'}}finally{$natural.Dispose()}
            return
        }
        return CimCmdlets\Get-CimInstance -ClassName $ClassName -Filter $Filter -ErrorAction Stop
    }
    try{Invoke-SuiteLocalRetirement $journalPath $invocation}finally{Remove-Item Function:\Get-CimInstance}

    $spawnEntry=Start-ContainedHolder $holderExe 'spawner' -Mode spawn
    $journal.children=@($entry,$javaEntry,$spawnEntry)
    Write-CaseJournal $journal
    # Delay into the holder's spawning interval while Invoke-SuiteLocalRetirement
    # still drains naturally. The descendant inherits the already-owned job.
    Start-Sleep -Milliseconds 700
    Invoke-SuiteLocalRetirement $journalPath $invocation
    if(!(Test-Path -LiteralPath (Join-Path $fixture 'spawner.ready.descendant'))){throw 'The spawning fixture did not exercise a new descendant'}
    $members=@([DepositSuiteJobs]::Members($spawnEntry.jobName))
    try{if($members.Count){throw 'A child born during natural drain survived retirement'}}finally{foreach($member in $members){$member.Dispose()}}
    Write-Output 'PASS: completed-job retirement releases diagnostic locks without changing contents or watchdog state; unrelated processes survive; invalid identities, live roots/runtime, uncertain metadata and failed drains are rejected'
} finally {
    foreach($item in $owned){try{Stop-SuiteChildTree $item}catch{Write-Warning $_.Exception.Message}}
    if($unrelated){if(!$unrelated.HasExited){$unrelated.Kill();$null=$unrelated.WaitForExit(5000)};$unrelated.Dispose()}
    [DepositSuiteJobs]::Release()
    Assert-HarnessPlainPath $fixture
    if([IO.Directory]::Exists($fixture)){[IO.Directory]::Delete($fixture,$true)}
}
