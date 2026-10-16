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
. (Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1') -JournalPath $env:DEPOSIT_SUITE_JOURNAL -LibraryOnly
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
$freshJobs=New-Object 'System.Collections.Generic.List[object]'
$trackFreshChildren=$false
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
function Move-OwnedTree([string]$From,[string]$To,[ValidateRange(0,60000)][int]$TimeoutMilliseconds=10000){
    $deadline=[DateTime]::UtcNow.AddMilliseconds($TimeoutMilliseconds)
    while($true){
        Assert-Tree $From;Assert-Tree $To
        if(Test-Path -LiteralPath $To){throw "Move destination exists: $To"}
        try{
            # Rename atomically on the approved volume. Move-Item can partially
            # traverse a tree before a retained diagnostic handle blocks it.
            [IO.Directory]::Move($From,$To)
            return
        }catch{
            $cause=$_.Exception
            while($cause.InnerException){$cause=$cause.InnerException}
            $nativeCode=$cause.HResult -band 0xffff
            if($nativeCode -notin @(5,32,33)){throw}
            if([DateTime]::UtcNow -ge $deadline){throw [IO.IOException]::new("Timed out preserving tree; source retained at $From",$cause)}
            # Stopped launchers can briefly retain inherited redirect handles.
            # Never terminate a process or discard files to release those handles.
            Start-Sleep -Milliseconds 100
        }
    }
}
function Invoke-Logged([string]$File,[string[]]$Arguments,[string]$Label){
    $output=$LogPath+'.'+$Label+'.log'
    $executable=$File
    if([IO.Path]::GetExtension($File) -ieq '.bat'){
        $executable=Join-Path $env:SystemRoot 'System32\cmd.exe'
        $commandArguments='/d /c ""'+$File+'" '+(($Arguments|ForEach-Object {'"'+$_+'"'}) -join ' ')+'"'
    }else{$commandArguments=($Arguments|ForEach-Object {'"'+$_.Replace('"','\"')+'"'}) -join ' '}
    $gate=$null;$child=$null
    try{
        if($trackFreshChildren){
            # The coordinator must remain outside this nested job. Start a gated
            # wrapper so every fresh descendant inherits containment before work.
            $identity=[Guid]::NewGuid().ToString('N')
            $gate=Join-Path $preserved ($identity+'.gate')
            $quote={param([string]$value) "'"+$value.Replace("'","''")+"'"}
            $command='$ErrorActionPreference="Stop"; while (!(Test-Path -LiteralPath '+(& $quote $gate)+')) { Start-Sleep -Milliseconds 50 }; '+
                '$p=Start-Process -FilePath '+(& $quote $executable)+' -ArgumentList '+(& $quote $commandArguments)+' -WindowStyle Hidden -PassThru -RedirectStandardOutput '+(& $quote $output)+' -RedirectStandardError '+(& $quote ($output+'.err'))+'; '+
                'try { $null=$p.Handle; if (!$p.WaitForExit(900000)) { throw "Fresh command timed out" }; $p.Refresh(); if ($null -eq $p.ExitCode) { throw "Fresh command exit code unavailable" }; $result=[int]$p.ExitCode } finally { $p.Dispose() }; exit $result'
            $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
            $child=Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput ($output+'.wrapper') -RedirectStandardError ($output+'.wrapper.err')
            [void]$child.Handle
            $jobName='Local\DepositClean-'+$identity
            [DepositSuiteJobs]::Create($jobName,$child.Id)
            $freshJobs.Add([pscustomobject]@{pid=$child.Id;startTicks=$child.StartTime.ToUniversalTime().Ticks;jobName=$jobName;label=$Label})
            [IO.File]::WriteAllText($gate,'go')
        }else{
            $child=Start-Process -FilePath $executable -ArgumentList $commandArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $output -RedirectStandardError ($output+'.err')
        }
        [void]$child.Handle
        if(!$child.WaitForExit(900000)){throw "$Label child did not exit within 900 seconds"}
        $child.Refresh()
        if($null -eq $child.ExitCode){throw "$Label child exit code is unavailable"}
        $code=[int]$child.ExitCode
    }finally{
        if($child){
            if($gate -and !(Test-Path -LiteralPath $gate) -and !$child.HasExited){$child.Kill();[void]$child.WaitForExit(5000)}
            $child.Dispose()
        }
        if($gate -and (Test-Path -LiteralPath $gate)){[IO.File]::Delete($gate)}
    }
    Write-ScenarioLog "$Label exit=$code log=$output"
    if($code -ne 0){throw "$Label failed ($code); see $output"}
}
function Assert-FreshRuntimeStopped {
    Assert-Tree $run
    $runtimeHome=Join-Path $run 'neo4j'
    $installationPath=Join-Path $run 'distribution\deposit.install.json'
    Assert-Tree $installationPath
    # Install can fail before publishing its state. The coordinator already
    # captured the original installation's exact service identity before moving it.
    $serviceNames=@($serviceName)
    if(Test-Path -LiteralPath $installationPath){
        $installation=Get-Content -LiteralPath $installationPath -Raw|ConvertFrom-Json
        if($installation.mode -cne 'local' -or [IO.Path]::GetFullPath([string]$installation.neo4jHome).TrimEnd('\') -ine $runtimeHome -or $installation.serviceName -cnotmatch '^DepositNeo4j-[a-f0-9]{8}$'){throw 'Fresh retirement installation identity mismatch'}
        # Fresh setup generates a new service identity for this same canonical
        # installation. Both its name and the removed original must be absent.
        $serviceNames+=@([string]$installation.serviceName)
    }
    foreach($name in $serviceNames|Select-Object -Unique){
        if($name -cnotmatch '^DepositNeo4j-[a-f0-9]{8}$'){throw 'Fresh retirement service identity is invalid'}
        if(Get-CimInstance Win32_Service -Filter "Name='$name'" -ErrorAction Stop){throw 'Fresh retirement refuses a registered service'}
    }
    foreach($recordPath in @((Join-Path $run 'process.json'),(Join-Path $runtimeHome 'deposit-process.json'))){
        Assert-Tree $recordPath
        if(!(Test-Path -LiteralPath $recordPath)){continue}
        $record=Get-Content -LiteralPath $recordPath -Raw|ConvertFrom-Json
        if([long]$record.pid -le 0){throw 'Fresh runtime record has an invalid PID'}
        $created=[DateTimeOffset]::Parse([string]$record.created,[Globalization.CultureInfo]::InvariantCulture).UtcDateTime.Ticks
        $process=$null
        try{
            try{$process=[Diagnostics.Process]::GetProcessById([int]$record.pid)}catch [ArgumentException]{}
            if($process -and !$process.HasExited -and [Math]::Abs($process.StartTime.ToUniversalTime().Ticks-$created) -lt [TimeSpan]::TicksPerMillisecond){throw 'Fresh retirement refuses a live recorded runtime'}
        }finally{if($process){$process.Dispose()}}
    }
    $runtime=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='prunsrv-amd64.exe'" -ErrorAction Stop|Where-Object {$_.CommandLine -and $_.CommandLine.IndexOf($runtimeHome,[StringComparison]::OrdinalIgnoreCase) -ge 0})
    if($runtime.Count){throw 'Fresh retirement requires the runtime to be stopped'}
}
function Retire-FreshChildren {
    Assert-FreshRuntimeStopped
    # Validate every root and member before terminating any process. The enclosing
    # suite job contains this coordinator and must never be swept here.
    $seen=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $evidence=@()
    foreach($identity in $freshJobs){
        if($identity.jobName -cnotmatch '^Local\\DepositClean-[a-f0-9]{32}$' -or !$seen.Add([string]$identity.jobName) -or [long]$identity.pid -le 0 -or [long]$identity.startTicks -le 0){throw 'Invalid fresh containment identity'}
        $rootProcess=$null
        try{
            try{$rootProcess=[Diagnostics.Process]::GetProcessById([int]$identity.pid)}catch [ArgumentException]{}
            if($rootProcess){
                [void]$rootProcess.Handle
                if(!$rootProcess.HasExited -and $rootProcess.StartTime.ToUniversalTime().Ticks -eq [long]$identity.startTicks){throw 'Fresh retirement refuses a live command root'}
            }
        }catch [InvalidOperationException]{if(!$rootProcess -or !$rootProcess.HasExited){throw}}
        catch [ComponentModel.Win32Exception]{if(!$rootProcess -or !$rootProcess.HasExited){throw}}
        finally{if($rootProcess){$rootProcess.Dispose()}}
        $members=@([DepositSuiteJobs]::Members([string]$identity.jobName))
        try{
            foreach($member in $members){
                try{
                if($member.HasExited){continue}
                if($member.Id -eq $PID){throw 'Fresh retirement refuses its coordinator'}
                if($member.ProcessName -in @('java','prunsrv-amd64')){throw 'Fresh retirement refuses a contained runtime process'}
                $metadata=Get-CimInstance Win32_Process -Filter "ProcessId=$($member.Id)" -ErrorAction Stop
                if(!$metadata -or !$metadata.CommandLine -or !$metadata.CreationDate){if($member.HasExited){continue};throw 'Cannot verify a fresh contained process'}
                $evidence+=@([pscustomobject]@{label=$identity.label;jobName=$identity.jobName;pid=$member.Id;startTicks=$member.StartTime.ToUniversalTime().Ticks;name=$member.ProcessName;commandLine=$metadata.CommandLine})
                }catch [InvalidOperationException]{if(!$member.HasExited){throw}}
                catch [ComponentModel.Win32Exception]{if(!$member.HasExited){throw}}
            }
        }finally{foreach($member in $members){$member.Dispose()}}
    }
    $evidence|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $preserved 'fresh-retirement-members.json') -Encoding UTF8
    Write-ScenarioLog "Retiring $($evidence.Count) verified fresh descendants from $($freshJobs.Count) completed command jobs."
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    foreach($identity in $freshJobs){Stop-SuiteChildTree $identity -RejectRuntime -Deadline $deadline}
    foreach($identity in $freshJobs){
        $members=@([DepositSuiteJobs]::Members([string]$identity.jobName))
        try{if($members.Count){throw 'Fresh contained processes remain; restoration refused'}}finally{foreach($member in $members){$member.Dispose()}}
    }
}
function Invoke-Phase([string]$Phase,[string[]]$Additional=@()){
    Invoke-Logged (Join-Path $PSHOME 'powershell.exe') (@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Stage0.ps1'),'-Phase',$Phase,'-Root',$rootPath,'-Port',[string]$Port)+$Additional) ('phase-'+$Phase+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6))
}
function Invoke-Selection([string]$Tests,[string]$Label){
    Invoke-Logged (Join-Path $PSHOME 'powershell.exe') @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'Invoke-Tests.ps1'),'-Kind','Integration','-Test',$Tests,'-Root',$rootPath,'-Port',[string]$Port,'-LogPath',($LogPath+'.'+$Label+'.tests.log')) $Label
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
    return @(Get-ChildItem -LiteralPath $Path -File -Recurse -Force|Sort-Object FullName|ForEach-Object {
        $relative=$_.FullName.Substring($prefix.Length)
        if($relative.StartsWith('logs\',[StringComparison]::OrdinalIgnoreCase) -or $relative -in @('neo4j\logs\deposit-console.out.log','neo4j\logs\deposit-console.err.log')){
            # A stopped launcher's redirected diagnostic stream can retain a
            # dormant writer handle. Preserve its bytes without excluding logs.
            $_.Refresh();$length=$_.Length;$modified=$_.LastWriteTimeUtc.Ticks
            $share=[IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
            $stream=[IO.File]::Open($_.FullName,[IO.FileMode]::Open,[IO.FileAccess]::Read,$share)
            $sha=[Security.Cryptography.SHA256]::Create()
            try{
                $hash=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','')
                if($stream.Length -ne $length){throw "Diagnostic log changed during preservation hashing: $relative"}
            }finally{$sha.Dispose();$stream.Dispose()}
            $_.Refresh()
            if($_.Length -ne $length -or $_.LastWriteTimeUtc.Ticks -ne $modified){throw "Diagnostic log changed during preservation hashing: $relative"}
        }else{$length=$_.Length;$hash=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
        [ordered]@{path=$relative;bytes=$length;sha256=$hash}
    })
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
    $preservedHashes=Get-TreeHashes $original
    if((ConvertTo-Json -InputObject $before -Depth 4 -Compress) -cne (ConvertTo-Json -InputObject $preservedHashes -Depth 4 -Compress)){throw 'Original tree changed while waiting for preservation handles to close'}
    Write-ScenarioLog "Preserved $($before.Count) original files; no original service is registered during local provisioning."
    $trackFreshChildren=$true
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
            }
            Retire-FreshChildren
            $trackFreshChildren=$false
            if(Test-Path -LiteralPath $run){
                $freshBefore=Get-TreeHashes $run
                $freshBefore|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $preserved 'fresh-files.json') -Encoding UTF8
                Move-OwnedTree $run $fresh
                $freshAfter=Get-TreeHashes $fresh
                if((ConvertTo-Json -InputObject $freshBefore -Depth 4 -Compress) -cne (ConvertTo-Json -InputObject $freshAfter -Depth 4 -Compress)){throw 'Fresh tree differs from preserved file hashes'}
                Write-ScenarioLog "Preserved all $($freshAfter.Count) fresh files with identical SHA256 and lengths."
            }
            $trackFreshChildren=$false
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
