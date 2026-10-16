[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ReleasePackage,
    [ValidateRange(1024,65535)][int]$Port=17770,
    [string]$Root=(Join-Path $PSScriptRoot '..\..\test_harness'),
    [string]$LogPath,
    [switch]$FreshAccountProbe
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
. (Join-Path $PSScriptRoot 'IntegrationHarnessSecrets.ps1')
Assert-HarnessRoot $Root
$rootPath=[IO.Path]::GetFullPath($Root).TrimEnd('\')
$env:STAGE0_ROOT=$rootPath
$run=Join-Path $rootPath 'runs\stage0-host'
$repo=Split-Path $PSScriptRoot -Parent
if(!$LogPath){$LogPath=Join-Path (Join-Path $rootPath 'logs') ('stage11-clean-provision-'+(Get-Date -Format 'yyyyMMddTHHmmss')+'.log')}
$LogPath=[IO.Path]::GetFullPath($LogPath)
$archive=[IO.Path]::GetFullPath($ReleasePackage)
$preserved=Join-Path $rootPath ('preserved\stage11-clean-'+[Guid]::NewGuid().ToString('N'))
$original=Join-Path $preserved 'original-host'
$fresh=Join-Path $preserved 'fresh-host'
$credentialTarget='Deposit/Stage0/stage0-host'
$backupCredential='Deposit/Stage11/'+[Guid]::NewGuid().ToString('N')
$lines=New-Object 'System.Collections.Generic.List[string]'
$utf8=New-Object Text.UTF8Encoding($false)
New-Item -ItemType Directory -Force (Split-Path $LogPath -Parent),$preserved|Out-Null
. (Join-Path $PSScriptRoot 'Credentials.ps1')
Set-OwnerOnlyDirectory $preserved
function Write-ScenarioLog([string]$Message){$lines.Add($Message);[IO.File]::WriteAllLines($LogPath,$lines,$utf8)}
function Assert-Tree([string]$Path){
    $full=[IO.Path]::GetFullPath($Path).TrimEnd('\')
    if(!$full.StartsWith($rootPath+'\',[StringComparison]::OrdinalIgnoreCase)){throw "Path escaped approved root: $full"}
    $current=$full
    while($current -and $current.Length -ge $rootPath.Length){
        if((Test-Path -LiteralPath $current) -and ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw "Reparse point refused: $current"}
        $current=Split-Path $current -Parent
    }
    if((Test-Path -LiteralPath $full) -and @(Get-ChildItem -LiteralPath $full -Recurse -Force|Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count){throw "Reparse point inside $full"}
}
function Move-OwnedTree([string]$From,[string]$To){Assert-Tree $From;Assert-Tree $To;if(Test-Path -LiteralPath $To){throw "Move destination exists: $To"};Move-Item -LiteralPath $From -Destination $To}
function Invoke-Logged([string]$File,[string[]]$Arguments,[string]$Label){
    $output=$LogPath+'.'+$Label+'.log'
    $executable=$File
    if([IO.Path]::GetExtension($File) -ieq '.bat'){
        $executable=Join-Path $env:SystemRoot 'System32\cmd.exe'
        $commandArguments='/d /c ""'+$File+'" '+(($Arguments|ForEach-Object {'"'+$_+'"'}) -join ' ')+'"'
    }else{$commandArguments=($Arguments|ForEach-Object {'"'+$_.Replace('"','\"')+'"'}) -join ' '}
    $child=Start-Process -FilePath $executable -ArgumentList $commandArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $output -RedirectStandardError ($output+'.err')
    try{
        [void]$child.Handle
        if(!$child.WaitForExit(900000)){throw "$Label child did not exit within 900 seconds"}
        $child.Refresh()
        if($null -eq $child.ExitCode){throw "$Label child exit code is unavailable"}
        $code=[int]$child.ExitCode
    }finally{$child.Dispose()}
    Write-ScenarioLog "$Label exit=$code log=$output"
    if($code -ne 0){throw "$Label failed ($code); see $output"}
}
function Invoke-Phase([string]$Phase,[string[]]$Additional=@()){
    Invoke-Logged (Join-Path $PSHOME 'powershell.exe') (@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Stage0.ps1'),'-Phase',$Phase,'-Port',[string]$Port)+$Additional) ('phase-'+$Phase+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6))
}
function Invoke-Selection([string]$Tests,[string]$Label){
    Invoke-Logged (Join-Path $PSHOME 'powershell.exe') @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Invoke-Tests.ps1'),'-Kind','Integration','-Test',$Tests,'-Port',[string]$Port,'-LogPath',($LogPath+'.'+$Label+'.tests.log')) $Label
    $testLog=[IO.File]::ReadAllText($LogPath+'.'+$Label+'.tests.log')
    if($testLog -notmatch '\[INFO\] BUILD SUCCESS' -or $testLog -match '\[ERROR\] Tests run:|\[INFO\] BUILD FAILURE'){throw "$Label lacks successful Maven evidence"}
}
function Get-ScmSettings([string]$Name){
    $settings=[ordered]@{}
    foreach($operation in @('qc','qfailure','qfailureflag','qsidtype','qprivs','qtriggerinfo','sdshow')){
        $value=(& sc.exe $operation $Name 2>&1|Out-String).Trim()
        if($LASTEXITCODE -ne 0){throw "Cannot capture SCM $operation"}
        $settings[$operation]=$value
    }
    return $settings
}
function Get-TreeHashes([string]$Path){
    $prefix=[IO.Path]::GetFullPath($Path).TrimEnd('\')+'\'
    return @(Get-ChildItem -LiteralPath $Path -File -Recurse -Force|Sort-Object FullName|ForEach-Object {[ordered]@{path=$_.FullName.Substring($prefix.Length);bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}})
}
$failure=$null;$moved=$false;$registrationRemoved=$false;$credentialSaved=$false;$restored=$false
try{
    if(!(Test-Path -LiteralPath $archive -PathType Leaf)){throw 'Exact release archive is missing'}
    Assert-Tree $run;Assert-Tree $preserved
    $owner=Get-Content (Join-Path $run 'owner.json') -Raw|ConvertFrom-Json
    if($owner.root -ine $rootPath -or $owner.runId -cne 'stage0-host' -or $owner.port -ne $Port){throw 'Single-installation ownership mismatch'}
    $statePath=Join-Path $run 'distribution\deposit.install.json'
    $state=Get-Content -LiteralPath $statePath -Raw|ConvertFrom-Json
    if($state.mode -ne 'service' -or $state.serviceAccount -ine ('NT SERVICE\'+$state.serviceName)){throw 'Scenario requires the owned virtual-account service'}
    $serviceName=[string]$state.serviceName
    $service=Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
    if(!$service -or $service.StartName -ine $state.serviceAccount){throw 'Configured service identity mismatch'}
    $wasRunning=$service.State -eq 'Running'
    $startMode=[string]$service.StartMode
    $settings=Get-ScmSettings $serviceName
    $settings|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $preserved 'service-settings.json') -Encoding UTF8
    Copy-Item -LiteralPath $statePath -Destination (Join-Path $preserved 'deposit.install.before.json')
    [DepositHarnessSecretStore]::Write($backupCredential,[DepositHarnessSecretStore]::Read($credentialTarget));$credentialSaved=$true
    @{originalRun=$run;preservedRun=$original;freshRun=$fresh;credentialBackup=$backupCredential;serviceName=$serviceName;startMode=$startMode;wasRunning=$wasRunning;releasePackage=$archive;releaseSha256=(Get-FileHash $archive -Algorithm SHA256).Hash}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $preserved 'restore-state.json') -Encoding UTF8
    Write-ScenarioLog "Preservation state: $preserved; candidate SHA256=$((Get-FileHash $archive -Algorithm SHA256).Hash)"
    $launcher=Join-Path $run 'distribution\deposit.bat'
    Invoke-Logged $launcher @('stop') 'prior-service-stop'
    if((Get-Service $serviceName).Status -ne 'Stopped'){throw 'Original service did not stop'}
    Invoke-Logged $launcher @('remove-service') 'prior-service-remove';$registrationRemoved=$true
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    while((Get-Service $serviceName -ErrorAction SilentlyContinue) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 200}
    if(Get-Service $serviceName -ErrorAction SilentlyContinue){throw 'Original service registration is still present'}
    if(Get-CimInstance Win32_Process|Where-Object {$_.Name -in @('java.exe','neo4j.exe') -and $_.CommandLine -and $_.CommandLine.Contains((Join-Path $run 'neo4j'))}){throw 'Prior runtime still owns the installation'}
    $before=Get-TreeHashes $run
    $before|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $preserved 'original-files.json') -Encoding UTF8
    Move-OwnedTree $run $original;$moved=$true
    Write-ScenarioLog "Preserved $($before.Count) original files; no original service is registered during local provisioning."
    Invoke-Phase 'Install' @('-ReleasePackage',$archive)
    if($FreshAccountProbe){
        Copy-Item -LiteralPath (Join-Path $rootPath 'build\deposit-runtime-probe\target\deposit-runtime-probe-0.0.0-stage65.jar') -Destination (Join-Path $run 'neo4j\plugins')
        $config=Join-Path $run 'neo4j\conf\neo4j.conf'
        $text=[IO.File]::ReadAllText($config)
        $text=[regex]::Replace($text,'(?m)^dbms\.security\.procedures\.allowlist=.*$','dbms.security.procedures.allowlist=deposit.*,stage0.geometry.*,stage0.security.*,stage0.storage.*,apoc.version')
        $text+="`r`ndbms.security.procedures.unrestricted=stage0.geometry.*,stage0.security.*,stage0.storage.*`r`n"
        [IO.File]::WriteAllText($config,$text,$utf8)
    }
    Invoke-Phase 'Start'
    Invoke-Logged (Join-Path $run 'distribution\deposit.bat') @('status') 'fresh-status'
    Invoke-Logged (Join-Path $run 'distribution\deposit.bat') @('diagnose') 'fresh-diagnose'
    if($FreshAccountProbe){
        Invoke-Selection 'Stage2ProbeIT,Stage3ProbeIT' 'fresh-account-probe'
        Write-ScenarioLog 'PASS: Fresh bootstrap competition, account commit/expiry/principal probes and dependent batch probes.'
    }else{
    Invoke-Selection 'Stage1LifecycleIT' 'fresh-bootstrap-lifecycle'
    Invoke-Selection 'DistributionIT,EnvironmentIT#nativeRuntimeAndApoc+independentClientSessions,Stage11ScientificWorkflowIT,Stage11ImportIT#persistentCloneSharesFreshResourcesAndRestoresHistoryOnlyContent+cloneRetainsResourcesReferencedOnlyByAClosedEpoch+analyzedImportAndPatternFormAttachResourcesInReceivingNamespace+successfulCloneCallerRollbackLeavesNoReceivingGraphOrResourceRecords' 'fresh-release-science'
    Invoke-Phase 'Stop'
    Invoke-Phase 'Start'
    Invoke-Selection 'DistributionIT,Stage11ScientificWorkflowIT' 'fresh-local-restart'
    Write-ScenarioLog 'PASS: Exact release clean provisioning, bootstrap, scientific/browser workflow and local restart on this existing Windows host.'
    }
}catch{$failure=$_.Exception.Message;Write-ScenarioLog "FAIL: $failure"}
finally{
    try{
        $terminalCleanup=Test-HarnessTerminalCleanup
        if($moved){
            if(Test-Path -LiteralPath $run){
                if(Test-Path -LiteralPath (Join-Path $run 'owner.json')){Invoke-Phase 'Stop'}
                Move-OwnedTree $run $fresh
            }
            Move-OwnedTree $original $run;$moved=$false
            $after=Get-TreeHashes $run
            if((ConvertTo-Json -InputObject $before -Depth 4 -Compress) -cne (ConvertTo-Json -InputObject $after -Depth 4 -Compress)){throw 'Restored original tree differs from preserved file hashes'}
            Write-ScenarioLog "Restored all $($after.Count) original files with identical SHA256 and lengths."
        }
        if($credentialSaved){[DepositHarnessSecretStore]::Write($credentialTarget,[DepositHarnessSecretStore]::Read($backupCredential))}
        if($registrationRemoved -and !$terminalCleanup){
            Invoke-Logged (Join-Path $run 'distribution\deposit.bat') @('install-service') 'original-service-reinstall'
            $startup=switch($startMode){'Auto'{'Automatic'};'Manual'{'Manual'};'Disabled'{'Disabled'};default{throw "Unsupported prior startup mode: $startMode"}}
            Set-Service -Name $serviceName -StartupType $startup
            $actual=Get-ScmSettings $serviceName
            foreach($key in $settings.Keys){if($settings[$key] -cne $actual[$key]){throw "Original SCM configuration differs after restoration: $key; inspect preserved service-settings.json"}}
            Write-ScenarioLog 'Original service registration, account, startup mode, failure actions, privileges, triggers and security descriptor restored.'
        }
        if($registrationRemoved -and $wasRunning -and !$terminalCleanup){Invoke-Phase 'Start';Invoke-Selection 'DistributionIT' 'restored-original-release'}
        $restored=$true
        if($credentialSaved){[DepositHarnessSecretStore]::Delete($backupCredential)}
    }catch{Write-ScenarioLog "RESTORE FAILURE: $($_.Exception.Message)";if(!$failure){$failure=$_.Exception.Message}}
}
if($failure){Write-Output "FAIL: Stage 11 clean provisioning ($failure)";Write-Output "Restored=$restored; log=$LogPath; state=$preserved";exit 1}
Write-Output 'PASS: Stage 11 clean provisioning and original-state restoration'
Write-Output "Log: $LogPath"
Write-Output "Preserved evidence: $preserved"
