[CmdletBinding()]
param([string]$LogPath=(Join-Path $env:TEMP ('DepositTests\'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'-stop-control.log')))
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Credentials.ps1')
. (Join-Path $PSScriptRoot 'Request-DepositStop.ps1')
$fixture=Join-Path $env:TEMP ('DepositStopControl-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture|Out-Null;Set-OwnerOnlyDirectory $fixture
$log=New-Object System.Collections.Generic.List[string]
$log.Add("Fixture retained: $fixture")
function Check([bool]$Condition,[string]$Message){if(!$Condition){throw $Message};$log.Add("PASS: $Message")}
function Expect-Failure([scriptblock]$Action,[string]$Expected){$observed=$null;try{& $Action}catch{$observed=$_.Exception.Message};Check ($observed -and $observed.Contains($Expected)) "Rejects $Expected"}
try{
    $newServiceState='{"schemaVersion":1,"mode":"local","neo4jHome":"C:\\neo4j","javaHome":"C:\\jdk","dataRoot":"C:\\data","exportRoot":"C:\\exports","serviceName":"DepositNeo4j-test"}'|ConvertFrom-Json
    Set-DepositServiceState $newServiceState 'NT SERVICE\DepositNeo4j-test'
    Check ($newServiceState.mode -eq 'service' -and $newServiceState.serviceAccount -eq 'NT SERVICE\DepositNeo4j-test') 'Service state adds the dedicated account to a local installation object'
    $roundTrip=$newServiceState|ConvertTo-Json -Compress|ConvertFrom-Json
    Check ($roundTrip.mode -eq 'service' -and $roundTrip.serviceAccount -eq 'NT SERVICE\DepositNeo4j-test') 'Service state persists the account and mode in JSON'
    $installation=Join-Path $fixture 'neo4j';$secrets=Join-Path $installation 'deposit-secrets';$control=Join-Path $secrets 'deposit-control'
    New-Item -ItemType Directory -Path (Join-Path $installation 'conf'),$control -Force|Out-Null
    Set-OwnerOnlyDirectory $secrets;Set-OwnerOnlyDirectory $control
    [IO.File]::WriteAllText((Join-Path $installation 'conf\deposit.properties'),'secretRoot='+$secrets.Replace('\','/'))
    $incarnation=[guid]::NewGuid().ToString();$runDirectory=Join-Path $control $incarnation;New-Item -ItemType Directory -Path $runDirectory|Out-Null;Set-OwnerOnlyDirectory $runDirectory
    $script:identity=[ordered]@{version=1;installation=$installation;databaseId=[guid]::NewGuid().ToString();pid=12345;startMillis=123000;incarnation=$incarnation}
    $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    function Set-TestRecordOwner([string]$Path){$acl=Get-Acl -LiteralPath $Path;$acl.SetOwner([System.Security.Principal.SecurityIdentifier]$sid);Set-Acl -LiteralPath $Path -AclObject $acl;$actual=(Get-Acl -LiteralPath $Path).GetOwner([System.Security.Principal.SecurityIdentifier]).Value;if($actual -ne $sid){throw "Could not set fixture owner for $Path"}}
    function Write-Descriptor { $path=Join-Path $control 'run.json';[IO.File]::WriteAllText($path,($script:identity|ConvertTo-Json -Compress));Set-TestRecordOwner $path }
    Write-Descriptor
    $script:mode='complete'
    function Get-CimInstance {
        param($ClassName,$Filter)
        foreach($requestFile in Get-ChildItem -LiteralPath $runDirectory -Filter 'request-*.json'){
            $request=Get-Content -LiteralPath $requestFile.FullName -Raw|ConvertFrom-Json
            $ackPath=Join-Path $runDirectory "ack-$($request.requestId).json"
            if($script:mode -ne 'timeout' -and !(Test-Path -LiteralPath $ackPath)){$ack=[ordered]@{};foreach($key in $script:identity.Keys){$ack[$key]=$script:identity[$key]};$ack.requestId=$request.requestId;$ack.state=$script:mode;$ack.transactions=0;$ack.readers=0;$ack.handlers=0;$ack.storageClosed=$true;if($script:mode -eq 'mismatch'){$ack.startMillis++};if($script:mode -eq 'incomplete'){$ack.state='complete';$ack.handlers=1};$text=if($script:mode -eq 'malformed'){'{'}else{$ack|ConvertTo-Json -Compress};[IO.File]::WriteAllText($ackPath,$text);Set-TestRecordOwner $ackPath}
        }
        return [pscustomobject]@{ProcessId=12345;CommandLine=$installation;CreationDate=[DateTimeOffset]::FromUnixTimeMilliseconds(123000).UtcDateTime}
    }
    function Invoke-CimMethod {param($InputObject,$MethodName);return [pscustomobject]@{ReturnValue=0;Sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value}}
    $script:completedDrain=Request-DepositStop -Neo4jHome $installation -TargetProcessId 12345 -TimeoutSeconds 1
    Check $true 'Bound complete acknowledgement returns successfully'
    $staleAfterDrain=$script:completedDrain.PSObject.Copy();$staleAfterDrain.startMillis++
    Expect-Failure {[void](Assert-DepositStopIdentity $staleAfterDrain)} 'incarnation is no longer running'
    $script:mode='failed';Expect-Failure {Request-DepositStop -Neo4jHome $installation -TargetProcessId 12345 -TimeoutSeconds 1} 'cleanup failed'
    $script:mode='mismatch';Expect-Failure {Request-DepositStop -Neo4jHome $installation -TargetProcessId 12345 -TimeoutSeconds 1} 'mismatched'
    $script:mode='incomplete';Expect-Failure {Request-DepositStop -Neo4jHome $installation -TargetProcessId 12345 -TimeoutSeconds 1} 'Incomplete'
    $script:mode='unsupported';Expect-Failure {Request-DepositStop -Neo4jHome $installation -TargetProcessId 12345 -TimeoutSeconds 1} 'Unsupported'
    $script:mode='malformed';Expect-Failure {Request-DepositStop -Neo4jHome $installation -TargetProcessId 12345 -TimeoutSeconds 1} 'Invalid object'
    $script:mode='timeout';Expect-Failure {Request-DepositStop -Neo4jHome $installation -TargetProcessId 12345 -TimeoutSeconds 1} 'cleanup timed out'
    $script:identity.startMillis=122999;Write-Descriptor;Expect-Failure {Request-DepositStop -Neo4jHome $installation -TargetProcessId 12345 -TimeoutSeconds 1} 'Stale or unsupported';$script:identity.startMillis=123000;Write-Descriptor
    $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $serviceAccount=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $serviceExe=Join-Path $installation 'bin\tools\prunsrv-amd64.exe'
    $serviceFixture=[pscustomobject]@{Name='DepositNeo4j-test';State='Running';StartName=$serviceAccount;ProcessId=12345;PathName=('"'+$serviceExe+'" //RS//DepositNeo4j-test')}
    $processFixture=[pscustomobject]@{Name='prunsrv-amd64.exe';ProcessId=12345;CreationDate=[DateTimeOffset]::FromUnixTimeMilliseconds(123000).UtcDateTime}
    $bound=Assert-DepositServiceBinding $serviceFixture $processFixture $sid $installation 'DepositNeo4j-test' $serviceAccount 12345 123000
    Check ($bound.pid -eq 12345 -and $bound.startMillis -eq 123000 -and $bound.runtimeSid -eq $sid) 'Service binding captures exact process, account, and start time'
    $bad=$serviceFixture.PSObject.Copy();$bad.PathName='C:\other\prunsrv-amd64.exe //RS//DepositNeo4j-test';Expect-Failure {Assert-DepositServiceBinding $bad $processFixture $sid $installation 'DepositNeo4j-test' $serviceAccount} 'does not match the pinned runtime'
    $bad=$serviceFixture.PSObject.Copy();$bad.PathName='"'+$serviceExe+'" //RS//OtherService';Expect-Failure {Assert-DepositServiceBinding $bad $processFixture $sid $installation 'DepositNeo4j-test' $serviceAccount} 'does not match the pinned runtime'
    Expect-Failure {Assert-DepositServiceBinding $serviceFixture $processFixture $sid $installation 'DepositNeo4j-test' $serviceAccount 12346} 'incarnation changed'
    Expect-Failure {Assert-DepositServiceBinding $serviceFixture $processFixture $sid $installation 'DepositNeo4j-test' $serviceAccount 12345 123001} 'incarnation changed'
    Expect-Failure {Assert-DepositServiceBinding $serviceFixture $processFixture 'S-1-5-18' $installation 'DepositNeo4j-test' $serviceAccount} 'different identity'
    Assert-DepositControlPath (Join-Path $control 'run.json') @($sid) -File;Check $true 'Protected FileInfo path validates under StrictMode'
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Threading;
using System.Threading.Tasks;
public static class DepositAtomicReadFixture {
 public static Task Start(string path,string json,string ownerSid) { return Task.Run(() => {
  for(int i=0;i<10;i++) { string part=path+".test.partial";File.WriteAllText(part,json);
   var info=new FileInfo(part);var acl=info.GetAccessControl();acl.SetOwner(new SecurityIdentifier(ownerSid));info.SetAccessControl(acl);
   try { File.Replace(part,path,null); } catch(IOException) { File.Delete(part); }
   Thread.Sleep(5);
  }
 }); }
}
'@
    $descriptorPath=Join-Path $control 'run.json';$writer=[DepositAtomicReadFixture]::Start($descriptorPath,($script:identity|ConvertTo-Json -Compress),$sid)
    for($index=0;$index -lt 100;$index++){$record=Read-DepositControlRecord $descriptorPath @($sid);if($record.incarnation -ne $incarnation){throw 'Atomic control record changed identity'}}
    $writer.GetAwaiter().GetResult();Check $true 'Atomic descriptor replacement and concurrent shared reads preserve complete records'
    $unsafe=Join-Path $fixture 'unsafe';New-Item -ItemType Directory -Path $unsafe|Out-Null;Set-OwnerOnlyDirectory $unsafe;$acl=Get-Acl -LiteralPath $unsafe;$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule('Everyone','Read','Allow')));Set-Acl -LiteralPath $unsafe -AclObject $acl
    Expect-Failure {Assert-DepositControlPath $unsafe @($sid)} 'grants another identity'
    $junction=Join-Path $fixture 'junction';& cmd.exe /c mklink /J $junction $control|Out-Null;if($LASTEXITCODE -ne 0){throw 'Could not create reparse fixture'}
    try{Expect-Failure {Assert-DepositControlPath $junction @($sid)} 'reparse'}finally{[IO.Directory]::Delete($junction)}
    # Load the actual orchestration functions, without executing the launcher's top-level command switch.
    $ast=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'runtime\distribution\deposit.ps1'),[ref]$null,[ref]$null)
    foreach($name in @('Fail','Stop-Local','Service','Save-State','Migrate-DepositSecrets','Set-SecretDirectoryAccess','Get-DepositServiceIncarnation','Assert-DepositServiceIncarnation','Drain-ServiceInstallation','Assert-NoInstalledDepositService','Set-ControlDirectoryAccess')){$definition=$ast.Find({param($node)$node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true);Invoke-Expression $definition.Extent.Text}
    $script:StatePath=Join-Path $fixture 'deposit.install.json'
    $stateToPersist='{"schemaVersion":1,"mode":"local","neo4jHome":"C:\\neo4j","javaHome":"C:\\jdk","dataRoot":"C:\\data","exportRoot":"C:\\exports","serviceName":"DepositNeo4j-test"}'|ConvertFrom-Json
    Save-State $stateToPersist
    $firstPersist=Get-Content -LiteralPath $script:StatePath -Raw|ConvertFrom-Json
    Check ($firstPersist.mode -eq 'local' -and !$firstPersist.PSObject.Properties['serviceAccount']) 'State creation serializes a real local installation without service identity'
    Set-DepositServiceState $stateToPersist 'NT SERVICE\DepositNeo4j-test'
    Save-State $stateToPersist
    $persisted=Get-Content -LiteralPath $script:StatePath -Raw|ConvertFrom-Json
    $stateArtifacts=@(Get-ChildItem -LiteralPath $fixture -File|Where-Object {$_.Name -match '^deposit\.install\.json\.'})
    Check ($persisted.mode -eq 'service' -and $persisted.PSObject.Properties['serviceAccount'].Value -eq 'NT SERVICE\DepositNeo4j-test' -and $stateArtifacts.Count -eq 0) 'Atomic state replacement persists service identity and leaves no backup or partial file'
    $stateToPersist.mode='local';$stateToPersist.PSObject.Properties.Remove('serviceAccount');Save-State $stateToPersist
    $localAgain=Get-Content -LiteralPath $script:StatePath -Raw|ConvertFrom-Json
    Check ($localAgain.mode -eq 'local' -and !$localAgain.PSObject.Properties['serviceAccount']) 'Atomic state replacement returns service installation to local mode'
    $held=[IO.File]::Open($script:StatePath,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);$replaceFailed=$false
    try{try{Set-DepositServiceState $stateToPersist 'NT SERVICE\DepositNeo4j-test';Save-State $stateToPersist}catch{$replaceFailed=$true}}finally{$held.Dispose()}
    $afterFailure=Get-Content -LiteralPath $script:StatePath -Raw|ConvertFrom-Json
    $stateArtifacts=@(Get-ChildItem -LiteralPath $fixture -File|Where-Object {$_.Name -match '^deposit\.install\.json\.'})
    Check ($replaceFailed -and $afterFailure.mode -eq 'local' -and !$afterFailure.PSObject.Properties['serviceAccount'] -and $stateArtifacts.Count -eq 0) 'Failed state replacement keeps the prior record and cleans temporary artifacts'
    Set-DepositServiceState $stateToPersist 'NT SERVICE\DepositNeo4j-test';Save-State $stateToPersist
    $afterRecovery=Get-Content -LiteralPath $script:StatePath -Raw|ConvertFrom-Json
    Check ($afterRecovery.mode -eq 'service' -and $afterRecovery.PSObject.Properties['serviceAccount'].Value -eq 'NT SERVICE\DepositNeo4j-test') 'State replacement succeeds after a failed attempt'
    $legacyNeo4j=Join-Path $fixture 'legacy-neo4j';$legacyConf=Join-Path $legacyNeo4j 'conf';New-Item -ItemType Directory -Path $legacyConf -Force|Out-Null
    $legacyConfig=Join-Path $legacyConf 'deposit.properties';$secretSource=Join-Path $legacyConf 'deposit-bootstrap.secret';[IO.File]::WriteAllText($legacyConfig,"resourceRoot=C:/data`nexportRoot=C:/exports`nexistingSetting=keep`n");[IO.File]::WriteAllText($secretSource,'fixture-secret')
    $legacyState=[pscustomobject]@{neo4jHome=$legacyNeo4j;secretRoot=(Join-Path $legacyNeo4j 'deposit-secrets')};$operator=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $secretRoot=Migrate-DepositSecrets $legacyState $operator;$legacyText=Get-Content -LiteralPath $legacyConfig -Raw;$migratedSecret=Join-Path $secretRoot 'deposit-bootstrap.secret'
    Check ($legacyText.Contains('existingSetting=keep') -and $legacyText.Contains('secretRoot=') -and (Test-Path -LiteralPath $migratedSecret) -and !(Test-Path -LiteralPath $secretSource)) 'Legacy secret-root configuration update preserves settings and moves the secret atomically'
    function Set-RuntimeEnvironment {param($State)}
    function Get-Service {param($Name,$ErrorAction);$value=[pscustomobject]@{Status='Running'};$value|Add-Member ScriptMethod WaitForStatus {param($Status,$Timeout)$script:events.Add('wait')};return $value}
    $script:servicePid=12345
    $script:changeServiceDuringDrain=$false
    function Get-CimInstance {
        param($ClassName,$Filter)
        if($ClassName -eq 'Win32_Service'){return [pscustomobject]@{Name='fake';State='Running';ProcessId=$script:servicePid}}
        foreach($requestFile in Get-ChildItem -LiteralPath $runDirectory -Filter 'request-*.json'){
            $request=Get-Content -LiteralPath $requestFile.FullName -Raw|ConvertFrom-Json
            if($script:mode -ne 'timeout'){$ack=[ordered]@{};foreach($key in $script:identity.Keys){$ack[$key]=$script:identity[$key]};$ack.requestId=$request.requestId;$ack.state=$script:mode;$ack.transactions=0;$ack.readers=0;$ack.handlers=0;$ack.storageClosed=$true;if($script:mode -eq 'mismatch'){$ack.startMillis++};if($script:mode -eq 'incomplete'){$ack.state='complete';$ack.handlers=1};$text=if($script:mode -eq 'malformed'){'{'}else{$ack|ConvertTo-Json -Compress};[IO.File]::WriteAllText((Join-Path $runDirectory "ack-$($request.requestId).json"),$text)}
        }
        return [pscustomobject]@{ProcessId=12345;CommandLine=$installation;CreationDate=[DateTimeOffset]::FromUnixTimeMilliseconds(123000).UtcDateTime}
    }
    function Stop-Service {param($Name,$ErrorAction);$script:events.Add('signal')}
    function Drain-Installation {param($State);$script:events.Add('drain');if($script:drainFails){throw 'injected cleanup failure'};if($script:changeServiceDuringDrain){$script:servicePid++};return $script:completedDrain}
    function Get-DepositServiceIncarnation {param($State);return [pscustomobject]@{name=$State.serviceName;pid=$script:servicePid;startMillis=123000;serviceAccount=$State.serviceAccount;runtimeSid=$sid}}
    function Drain-ServiceInstallation {param($State,$Identity);$script:events.Add('drain');if($script:drainFails){throw 'injected cleanup failure'};if($script:changeServiceDuringDrain){$script:servicePid++};return $script:completedDrain}
    $state=[pscustomobject]@{neo4jHome=$installation;javaHome=$fixture;serviceName='fake';mode='service';serviceAccount='NT SERVICE\fake'}
    foreach($operation in @('stop','remove')){
        $script:events=New-Object System.Collections.Generic.List[string];$script:drainFails=$true
        Expect-Failure {Service $operation $state} 'injected cleanup failure';Check (($script:events -join ',') -eq 'drain') "Service $operation does not signal after cleanup failure"
    }
    $script:events=New-Object System.Collections.Generic.List[string];$script:drainFails=$false;Service 'stop' $state
    Check (($script:events -join ',') -eq 'drain,signal,wait') 'Service stop drains once before its signal and exit wait (mocked)'
    $script:events=New-Object System.Collections.Generic.List[string];$script:changeServiceDuringDrain=$true
    Expect-Failure {Service 'stop' $state} 'restarted during its drain';Check (($script:events -join ',') -eq 'drain') 'Service stop refuses to signal a replacement service incarnation'
    $script:changeServiceDuringDrain=$false;$script:servicePid=23456
    [IO.File]::WriteAllText((Join-Path $installation 'deposit-process.json'),(@{pid=12345;created=([DateTimeOffset]::FromUnixTimeMilliseconds(123000).UtcDateTime).ToString('o')}|ConvertTo-Json))
    function Get-Process {param($Id);return [pscustomobject]@{Handle=[IntPtr]::Zero}}
    $script:events=New-Object System.Collections.Generic.List[string];$script:drainFails=$true
    Expect-Failure {Stop-Local $state} 'injected cleanup failure';Check (($script:events -join ',') -eq 'drain') 'Local stop does not signal after cleanup failure'

    # Exercise the packaged launcher's actual Service functions in a separate module scope.
    # This catches helper functions that were accidentally available only in this test's scope.
    New-Item -ItemType Directory -Path (Join-Path $installation 'bin') -Force|Out-Null
    $uninstallMarker=Join-Path $fixture 'service-uninstalled.marker'
    [IO.File]::WriteAllText((Join-Path $installation 'bin\neo4j.bat'),"@echo off`r`necho uninstall> `"$uninstallMarker`"`r`nexit /b 0`r`n")
    $freshModule=New-Module -ArgumentList @((Join-Path $repo 'runtime\distribution\deposit.ps1'),(Join-Path $repo 'scripts\Request-DepositStop.ps1'),$installation,$script:completedDrain,$sid) -ScriptBlock {
        param($launcherPath,$helperPath,$installPath,$drainedIdentity,$currentSid)
        Set-StrictMode -Version Latest;$ErrorActionPreference='Stop'
        . $helperPath
        $drainedIdentity|Add-Member -NotePropertyName serviceName -NotePropertyValue 'fresh' -Force
        $drainedIdentity|Add-Member -NotePropertyName serviceAccount -NotePropertyValue 'NT SERVICE\fresh' -Force
        $ast=[System.Management.Automation.Language.Parser]::ParseFile($launcherPath,[ref]$null,[ref]$null)
        foreach($name in @('Fail','Service','Get-DepositServiceIncarnation','Assert-DepositServiceIncarnation','Drain-ServiceInstallation','Assert-NoInstalledDepositService')){$definition=$ast.Find({param($node)$node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true);Invoke-Expression $definition.Extent.Text}
        $script:events=New-Object System.Collections.Generic.List[string];$script:servicePid=12345;$script:replaceDuringDrain=$false
        function Get-Service {param($Name,$ErrorAction);$value=[pscustomobject]@{Status='Running'};$value|Add-Member ScriptMethod WaitForStatus {param($Status,$Timeout)$script:events.Add('wait')};return $value}
        function Set-RuntimeEnvironment {param($State)}
        function Get-CimInstance {param($ClassName,$Filter);if($ClassName -eq 'Win32_Service'){return [pscustomobject]@{Name='fresh';State='Running';ProcessId=$script:servicePid}};return [pscustomobject]@{ProcessId=12345;CommandLine=$installPath;CreationDate=[DateTimeOffset]::FromUnixTimeMilliseconds(123000).UtcDateTime}}
        function Invoke-CimMethod {param($InputObject,$MethodName);return [pscustomobject]@{ReturnValue=0;Sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value}}
        function Get-DepositServiceProcessIdentity {param($Neo4jHome,$ServiceName,$ServiceAccount,$ExpectedPid=0,$ExpectedStartMillis=0);if($ExpectedPid -gt 0 -and $ExpectedPid -ne $script:servicePid){throw 'Deposit service process incarnation changed'};return [pscustomobject]@{name=$ServiceName;pid=$script:servicePid;startMillis=123000;serviceAccount=$ServiceAccount;runtimeSid=$currentSid}}
        function Request-DepositStop {param($Neo4jHome,$TargetProcessId,$ServiceName,$ServiceAccount);$script:events.Add('drain');if($script:replaceDuringDrain){$script:servicePid++};return $drainedIdentity}
        function Stop-Service {param($Name,$ErrorAction);$script:events.Add('signal')}
        function Migrate-DepositSecrets {param($State,$ServiceAccount);$script:events.Add('migrate')}
        function Save-State {param($State);$script:events.Add('save')}
        function Drain-Installation {param($State);$script:events.Add('drain');if($script:replaceDuringDrain){$script:servicePid++};return $drainedIdentity}
        function Invoke-FreshScopeChecks {
        $state=[pscustomobject]@{neo4jHome=$installPath;javaHome=$installPath;serviceName='fresh';mode='service';serviceAccount='NT SERVICE\fresh'}
        try{Assert-NoInstalledDepositService $state;throw 'A duplicate service was not rejected'}catch{if(!$_.Exception.Message.Contains('remove-service from an elevated shell')){throw}}
        Service 'stop' $state
        if(($script:events -join ',') -ne 'drain,signal,wait'){throw "Fresh-scope service stop order was $($script:events -join ',')"}
        $script:events.Clear();$state=[pscustomobject]@{neo4jHome=$installPath;javaHome=$installPath;serviceName='fresh';mode='service';serviceAccount='NT SERVICE\fresh'};Service 'remove' $state
        if(($script:events -join ',') -ne 'drain,signal,wait,migrate,save'){throw "Fresh-scope service removal order was $($script:events -join ',')"}
        if($state.PSObject.Properties['serviceAccount']){throw 'Partial service removal left the nonexistent account property present'}
        $script:events.Clear();$state.mode='service';$state|Add-Member -NotePropertyName serviceAccount -NotePropertyValue 'NT SERVICE\fresh' -Force;$script:replaceDuringDrain=$true
        try{Service 'stop' $state;throw 'Replacement service was not rejected'}catch{if(!$_.Exception.Message.Contains('restarted during its drain')){throw}}
        if(($script:events -join ',') -ne 'drain'){throw "Fresh-scope replacement service was signalled: $($script:events -join ',')"}
        'fresh-scope stop, remove, and replacement checks passed'
        }
        Export-ModuleMember -Function Invoke-FreshScopeChecks
    }
    Import-Module $freshModule
    $freshResult=Invoke-FreshScopeChecks
    Remove-Module $freshModule.Name
    Check ($freshResult -contains 'fresh-scope stop, remove, and replacement checks passed') 'Fresh module scope resolves launcher helper for successful service stop/removal and refuses changed incarnation'
    Check (Test-Path -LiteralPath $uninstallMarker) 'Fresh-scope service removal invokes the Neo4j service uninstall command'
    $principal=[System.Security.Principal.WindowsIdentity]::GetCurrent().User
    Set-ControlDirectoryAccess $secrets $principal $principal
    Assert-DepositControlPath $control @($sid);Check $true 'Known control tree identity migration retains protected access'
    [IO.File]::WriteAllText((Join-Path $control 'unexpected.txt'),'unexpected')
    Expect-Failure {Set-ControlDirectoryAccess $secrets $principal $principal} 'Unexpected shutdown control file'
    $log.Add('Live Windows service validation was not run; service control was mocked.')
}catch{$log.Add('FAIL: '+$_.Exception.Message);throw}finally{
    New-Item -ItemType Directory -Force (Split-Path $LogPath -Parent)|Out-Null
    [IO.File]::WriteAllLines($LogPath,$log,(New-Object Text.UTF8Encoding($false)))
    Write-Output "Log: $LogPath"
    # Fixture is retained for diagnosis; no runtime or process is owned by this check.
}
Write-Output 'PASS: Deposit stop control checks'
