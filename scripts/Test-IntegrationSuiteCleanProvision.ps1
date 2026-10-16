[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$principal=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if(!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Run this selective service-transition check from Administrator PowerShell.'}
. (Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1') -LibraryOnly
$root=Get-HarnessRoot;$repo=Get-DepositServerRoot;$port=17770;$run=Join-Path $root 'runs\stage0-host';$private=Join-Path $root 'private\suite'
Set-OwnerOnlyDirectory $private
$journalPath=Join-Path $private 'active.json';$lockPath=Join-Path $private 'suite.lock';Assert-HarnessPlainPath $lockPath
$suiteLock=$null
$invocation=[Guid]::NewGuid().ToString('N');$logs=Join-Path $root ('results\focused-boundary-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'-'+$invocation.Substring(0,8))
$watchdog=$null;$journalCreated=$false;$failure=$null;$saved=@{}
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Invoke-IntegrationSuite.ps1'),[ref]$null,[ref]$null)
foreach($name in @('Quote-Suite','Invoke-SuiteChild','Invoke-SuiteStage','Invoke-SuiteNative')){
    $definition=$ast.Find({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
    if(!$definition){throw "Missing suite helper: $name"}
    Invoke-Expression ($definition.Extent.Text.Replace('$PSScriptRoot',("'"+$PSScriptRoot.Replace("'","''")+"'")))
}
function Capture-LocalResidue([string]$Label){
    $journal=Read-SuiteJournal $journalPath;$rows=@()
    foreach($child in $journal.children){
        foreach($member in @([DepositSuiteJobs]::Members([string]$child.jobName))){
            try{$record=Get-CimInstance Win32_Process -Filter "ProcessId=$($member.Id)";$rows+=@([pscustomobject]@{label=$child.label;jobName=$child.jobName;pid=$member.Id;startTicks=$member.StartTime.ToUniversalTime().Ticks;name=$member.ProcessName;commandLine=$record.CommandLine})}finally{$member.Dispose()}
        }
    }
    $rows|ConvertTo-Json -Depth 5|Set-Content (Join-Path $logs ($Label+'-members.json')) -Encoding UTF8
    $locked=@()
    foreach($file in @(Get-ChildItem (Join-Path $run 'logs'),(Join-Path $run 'neo4j\logs') -Recurse -File)){
        try{$stream=[IO.File]::Open($file.FullName,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None);$stream.Dispose()}catch{$locked+=@($file.FullName)}
    }
    $locked|ConvertTo-Json|Set-Content (Join-Path $logs ($Label+'-locked.json')) -Encoding UTF8
    Write-Host "Capture $Label : $($rows.Count) residual job members, $($locked.Count) locked diagnostic files"
    $rows|Select-Object label,pid,name|Format-Table|Out-Host
}
try{
    $suiteLock=[IO.File]::Open($lockPath,'OpenOrCreate','ReadWrite','None')
    if(Test-Path $journalPath){
        $prior=Read-SuiteJournal $journalPath
        $completedAt=[DateTimeOffset]::MinValue
        $validTimestamp=[DateTimeOffset]::TryParse([string]$prior.cleanedUtc,[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::RoundtripKind,[ref]$completedAt)
        if($prior.schemaVersion -ne 1 -or [string]$prior.invocationId -cnotmatch '^[a-f0-9]{32}$' -or
            $prior.ownerSid -cne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value -or
            $prior.state -cne 'cleaned' -or $prior.terminalCleanup -isnot [bool] -or !$prior.terminalCleanup -or
            $null -ne $prior.cleanupError -or !$validTimestamp){throw 'Previous suite is not safely cleaned'}
        $archiveJournal=Join-Path $private ('journal-'+$prior.invocationId+'.json')
        Assert-HarnessPlainPath $archiveJournal
        if(Test-Path $archiveJournal){throw 'Archive exists'}
        [IO.File]::Move($journalPath,$archiveJournal)
    }
    $state=Get-Content (Join-Path $run 'distribution\deposit.install.json') -Raw|ConvertFrom-Json
    if($state.mode -cne 'local' -or $state.serviceName -notmatch '^DepositNeo4j-[a-f0-9]{8}$' -or $state.neo4jHome -ine (Join-Path $run 'neo4j')){throw 'Fixture mismatch'}
    if(Get-Service -Name $state.serviceName -ErrorAction SilentlyContinue){throw 'Service exists'}
    if(Get-CimInstance Win32_Process|Where-Object {$_.Name -in @('java.exe','prunsrv-amd64.exe') -and $_.CommandLine -and $_.CommandLine.Contains([string]$state.neo4jHome)}){throw 'Runtime active'}
    New-Item -ItemType Directory -Path $logs -Force|Out-Null;Set-OwnerOnlyDirectory $logs
    foreach($key in @('TEMP','TMP','STAGE0_ROOT','DEPOSIT_SUITE_JOURNAL')){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
    $env:TEMP=Join-Path $root 'tmp';$env:TMP=$env:TEMP;$env:STAGE0_ROOT=$root;$env:DEPOSIT_SUITE_JOURNAL=$journalPath
    $journal=[ordered]@{schemaVersion=1;invocationId=$invocation;ownerSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;runnerPid=$PID;runnerStartTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks;neo4jHome=(Join-Path $run 'neo4j');runtimeOwned=$true;serviceName=$null;serviceAccount=$null;installationPhase='none';allowedInitialServiceAccount='LocalSystem';children=@();state='running';terminalCleanup=$false;watchdogAcknowledged=$false;watchdogPid=$null;watchdogStartTicks=$null;cleanupError=$null;cleanupDiagnostics=@();cleanedUtc=$null;logs=$logs}
    Write-SuiteJournal $journalPath ($journal|ConvertTo-Json -Depth 10);$journalCreated=$true
    $command='& '+(Quote-Suite (Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1'))+' -JournalPath '+(Quote-Suite $journalPath);$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $watchdog=Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs 'watchdog.stdout.log') -RedirectStandardError (Join-Path $logs 'watchdog.stderr.log')
    $deadline=[DateTime]::UtcNow.AddSeconds(30);while(!(Read-SuiteJournal $journalPath).watchdogAcknowledged){if($watchdog.HasExited -or [DateTime]::UtcNow -gt $deadline){throw 'Watchdog failed'};Start-Sleep -Milliseconds 100}
    # Terminal cleanup removes the service but retains its last process record.
    # Preserve that obsolete evidence before deliberately testing local mode.
    $recordPath=Join-Path $run 'process.json'
    Assert-HarnessPlainPath $recordPath
    if(Test-Path -LiteralPath $recordPath){
        $record=Get-Content -LiteralPath $recordPath -Raw|ConvertFrom-Json
        if($record.PSObject.Properties['mode'] -and $record.mode -ceq 'service'){
            if($record.serviceName -cne $state.serviceName -or $record.neo4jHome -ine $state.neo4jHome -or [long]$record.pid -le 0){throw 'Stale service record identity mismatch'}
            $recordStart=[DateTimeOffset]::Parse([string]$record.created,[Globalization.CultureInfo]::InvariantCulture).UtcDateTime.Ticks
            $recordProcess=$null
            try{
                try{$recordProcess=[Diagnostics.Process]::GetProcessById([int]$record.pid)}catch [ArgumentException]{}
                if($recordProcess -and !$recordProcess.HasExited -and $recordProcess.StartTime.ToUniversalTime().Ticks -eq $recordStart){throw 'Recorded service process remains active'}
            }finally{if($recordProcess){$recordProcess.Dispose()}}
            [IO.File]::Move($recordPath,(Join-Path $logs 'prior-service-process.json'))
        }
    }
    Invoke-SuiteStage 'Start';Invoke-SuiteStage 'Stop';Capture-LocalResidue 'graceful'
    Invoke-SuiteStage 'Start';Invoke-SuiteStage 'Kill';Invoke-SuiteStage 'Start';Invoke-SuiteStage 'Stop';Capture-LocalResidue 'recovery'
    Invoke-SuiteLocalRetirement $journalPath $invocation
    Capture-LocalResidue 'retired'
    Update-SuiteJournal $journalPath {param($j)$j.serviceName=[string]$state.serviceName;$j.serviceAccount='NT SERVICE\'+$state.serviceName;$j.installationPhase='installing'}
    Invoke-SuiteNative (Join-Path $run 'distribution\deposit.bat') @('install-service') 'install-temporary-service'
    Update-SuiteJournal $journalPath {param($j)$j.installationPhase='configured'}
    Invoke-SuiteStage 'Start'
    Invoke-SuiteChild (Join-Path $PSScriptRoot 'Invoke-Stage11CleanProvisionScenario.ps1') @('-ReleasePackage',(Join-Path $root 'build\deposit-server-0.0.0-stage65.zip'),'-Root',$root,'-Port',[string]$port,'-LogPath',(Join-Path $logs 'clean-provision.log')) 'clean-provision' 3600
}catch{$failure=$_.Exception.Message}finally{
    if($journalCreated){try{Invoke-SuiteCleanup $journalPath $invocation}catch{$failure=($failure+' Cleanup failed: '+$_.Exception.Message).Trim()}}
    if($watchdog){$null=$watchdog.WaitForExit(10000);$watchdog.Dispose()}
    foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
    if($suiteLock){$suiteLock.Dispose()};[DepositSuiteJobs]::Release()
}
if($failure){throw "$failure Logs: $logs"}
Write-Output "PASS: selective local-to-service clean provisioning. Logs: $logs"
