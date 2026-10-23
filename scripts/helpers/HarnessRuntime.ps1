. (Join-Path $PSScriptRoot '..\DepositBuildMetadata.ps1')

function Write-Json($Value,[string]$Path) {
    $temporary=$Path+'.'+[Guid]::NewGuid().ToString('N')+'.partial'
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 15), $utf8)
    if(Test-Path -LiteralPath $Path) {[IO.File]::Replace($temporary,$Path,$Path+'.previous')} else {[IO.File]::Move($temporary,$Path)}
}

function Assert-Run {
    $marker = Join-Path $run 'owner.json'
    if (!(Test-Path $marker)) { throw 'Installation has no test harness ownership marker' }
    $owner = Get-Content $marker -Raw | ConvertFrom-Json
    if ($owner.runId -ne $RunId -or $owner.root -ne $Root -or $owner.port -ne $Port) { throw 'Installation identity mismatch' }
    $resolved = [IO.Path]::GetFullPath($installation)
    if (!$resolved.StartsWith($Root+'\runs\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Installation escaped test harness root' }
}

function Invoke-Install($Context,[string]$ScriptsDir) {$Root=$Context.Root; $repo=$Context.Repo; $run=$Context.Run; $evidence=$Context.Evidence; $manifest=$Context.Manifest; $spatialLock=$Context.SpatialLock; $ocflLock=$Context.OcflLock; $formatLock=$Context.FormatLock; $RunId=$Context.RunId; $Port=$Context.Port; $ReleasePackage=$Context.ReleasePackage; $BoltUri=$Context.BoltUri; $DiagnosticBuild=$Context.DiagnosticBuild; $WithoutProbe=$Context.WithoutProbe; $StartupTimeoutSeconds=$Context.StartupTimeoutSeconds; $installation=$Context.Installation; $credentialTarget=$Context.CredentialTarget; $utf8=$Context.Utf8;
    Test-Artifacts
    $runtimeDirectory=Get-ToolDirectory $manifest 'neo4j' $Root
    $null=Get-ToolDirectory $manifest 'temurin' $Root
    $build=$null
    if (!$DiagnosticBuild) {$build=Get-DepositBuildMetadata $repo $Root;$pluginPath=Join-Path $Root "build\deposit-plugin\target\$($build.PluginFile)";if(!(Test-Path -LiteralPath $pluginPath -PathType Leaf)){throw 'Build the plugin before installation'};if((Get-DepositJarVersion $pluginPath) -cne $build.Version){throw 'Build metadata version differs from plugin JAR metadata'}}
    if (Test-Path "$run\owner.json") { Assert-Run; throw 'Stop and reset test-host before reinstalling' }
    $releaseMode=![string]::IsNullOrWhiteSpace($ReleasePackage)
    $distribution=Join-Path $run 'distribution'
    if($releaseMode -and (Test-Path -LiteralPath $distribution)) {throw 'Installed distribution already exists; reset test-host before installing'}
    Write-Json @{runId=$RunId;root=$Root;port=$Port;diagnostic=[bool]$DiagnosticBuild;releaseMode=$releaseMode} "$run\owner.json"
    Copy-Item -LiteralPath $runtimeDirectory -Destination $installation -Recurse
    New-Item -ItemType Directory -Force "$run\resources\staging","$run\resources\ocfl","$run\resources\ocfl-work","$run\exports","$run\secrets" | Out-Null
    Set-OwnerOnlyDirectory "$run\secrets"
    if($releaseMode) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archivePath=[IO.Path]::GetFullPath($ReleasePackage)
        if(!(Test-Path -LiteralPath $archivePath -PathType Leaf)){throw 'Release package does not exist'}
        New-Item -ItemType Directory -Path $distribution -Force|Out-Null
        $zip=[IO.Compression.ZipFile]::OpenRead($archivePath)
        try {
            foreach($entry in $zip.Entries){
                if(!$entry.Name){continue}
                $relative=$entry.FullName.Replace('/','\');$target=[IO.Path]::GetFullPath((Join-Path $distribution $relative))
                if(!$target.StartsWith([IO.Path]::GetFullPath($distribution).TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Release archive contains a path outside its extraction directory'}
                New-Item -ItemType Directory -Force (Split-Path $target -Parent)|Out-Null
                $input=$entry.Open();$output=[IO.File]::Open($target,'CreateNew','Write','None');try{$input.CopyTo($output)}finally{$output.Dispose();$input.Dispose()}
            }
        } finally {$zip.Dispose()}
        if(!(Test-Path -LiteralPath (Join-Path $distribution 'deposit.bat') -PathType Leaf) -or !(Test-Path -LiteralPath (Join-Path $distribution 'artifacts.json') -PathType Leaf)){throw 'Release archive is missing its launcher or runtime artifact inputs'}
        . "$ScriptsDir\ReleaseManifest.ps1"
        if(!(Test-DepositReleasePackage -Root $distribution)){throw 'Extracted release package failed manifest verification'}
        $env:JAVA_HOME=Join-Path $Root $manifest.javaHome;$env:NEO4J_HOME=$installation;$env:NEO4J_CONF=Join-Path $installation 'conf';$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
        $launcher=Join-Path $distribution 'deposit.bat';$arguments=@('setup',$installation,$env:JAVA_HOME,(Join-Path $run 'resources'),(Join-Path $run 'exports'),[string]$Port)
        $ErrorActionPreference='Continue';& $launcher @arguments *> "$evidence\release-setup.log";$ErrorActionPreference='Stop'
        if($LASTEXITCODE -ne 0){throw 'Packaged deposit.bat setup failed; see release-setup.log'}
        $expectedInstallation=@{neo4jHome=$installation;javaHome=$env:JAVA_HOME;dataRoot=(Join-Path $run 'resources');exportRoot=(Join-Path $run 'exports');mode='local'}
        if(!(Test-DepositReleasePackage -Root $distribution -ConfiguredInstallation -ExpectedInstallation $expectedInstallation)){throw 'Configured release installation failed manifest verification'}
    } else {
    if ($DiagnosticBuild) {
        $plugin="$Root\diagnostic-build\deposit-plugin.jar"
        $probe="$Root\diagnostic-build\deposit-runtime-probe.jar"
    } else {
        $plugin = Join-Path $Root "build\deposit-plugin\target\$($build.PluginFile)"
        $probe = Join-Path $Root "build\deposit-runtime-probe\target\$($build.ProbeFile)"
    }
    $files=@($plugin)
    if(!$WithoutProbe) {$files+=@($probe)}
    foreach ($file in $files) { Copy-Item -LiteralPath $file -Destination "$installation\plugins" }
    foreach ($id in @('apoc','spatial','bcrypt-provider')) {
        $artifact = $manifest.artifacts | Where-Object id -eq $id
        Copy-Item -LiteralPath (Join-Path $Root "cache\$($artifact.file)") -Destination "$installation\plugins"
    }
    foreach($artifact in @($spatialLock.dependencies | Where-Object ship)) {
        Copy-Item -LiteralPath (Join-Path $Root "cache\spatial-dependencies\$($artifact.file)") -Destination "$installation\plugins"
    }

    foreach($artifact in @($ocflLock.dependencies | Where-Object ship)) {
        Copy-Item -LiteralPath (Join-Path $Root "cache\ocfl-dependencies\$($artifact.file)") -Destination "$installation\plugins"
    }
    foreach($artifact in @($formatLock.dependencies | Where-Object ship)) {Copy-Item -LiteralPath (Join-Path $Root "cache\format-dependencies\$($artifact.file)") -Destination "$installation\plugins"}
    $configFile = (Join-Path $installation 'conf\deposit.properties').Replace('\','/')
    $resources = (Join-Path $run 'resources').Replace('\','/')
    $secretRoot=Join-Path $installation 'deposit-secrets';New-Item -ItemType Directory -Path $secretRoot -Force|Out-Null;Set-OwnerOnlyDirectory $secretRoot
    $permissions='dbms.security.procedures.allowlist=deposit.*,apoc.version'
    if(!$WithoutProbe) {$permissions="dbms.security.procedures.allowlist=deposit.*,testprobe.geometry.*,testprobe.security.*,testprobe.storage.*,apoc.version`ndbms.security.procedures.unrestricted=testprobe.geometry.*,testprobe.security.*,testprobe.storage.*"}
    & "$ScriptsDir\Configure-DepositDownloads.ps1" -ConfigPath $configFile -ResourceRoot $resources -ExportRoot (Join-Path $run 'exports') -SecretRoot $secretRoot | Out-Null
    $config = @"
server.default_listen_address=127.0.0.1
server.bolt.enabled=true
server.bolt.listen_address=127.0.0.1:$Port
server.bolt.advertised_address=127.0.0.1:$Port
server.http.enabled=false
server.https.enabled=false
dbms.security.auth_enabled=true
db.query.default_language=CYPHER_25
$permissions
server.memory.heap.initial_size=256m
server.memory.heap.max_size=512m
server.memory.pagecache.size=128m
server.jvm.additional=-Ddeposit.config=$configFile
server.jvm.additional=-Djava.io.tmpdir=$($env:TEMP.Replace('\','/'))
"@
    [IO.File]::WriteAllText("$installation\conf\neo4j.conf", $config, $utf8)
    }
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {$random.GetBytes($bytes)} finally {$random.Dispose()}
    $password = [Convert]::ToBase64String($bytes)
    [DepositHarnessSecretStore]::Write($credentialTarget, $password)
    # Picocli argument files keep the generated secret out of the process command line.
    $argFile = "$run\secrets\initial-password.args"
    [IO.File]::WriteAllText($argFile, "dbms`nset-initial-password`n$password`n", $utf8)
    $env:NEO4J_HOME = $installation
    $env:NEO4J_CONF = "$installation\conf"
    try {
        $ErrorActionPreference='Continue'
        & "$installation\bin\neo4j-admin.bat" "@$argFile" *> "$evidence\install.log"
        $ErrorActionPreference='Stop'
        if ($LASTEXITCODE -ne 0) { throw 'Initial password setup failed' }
    } finally { Remove-Item -LiteralPath $argFile -ErrorAction SilentlyContinue; $password=$null }
    Copy-Item "$installation\conf\neo4j.conf","$installation\conf\deposit.properties" $evidence
}

function Get-OwnedProcess {
    Assert-Run
    if (!(Test-Path "$run\process.json")) { return $null }
    $identity = Get-Content "$run\process.json" -Raw | ConvertFrom-Json
    if($identity.PSObject.Properties['mode'] -and $identity.mode -eq 'service') {
        . (Join-Path $ScriptsDir 'Request-DepositStop.ps1')
        $installState=Get-Content (Join-Path $run 'distribution\deposit.install.json') -Raw|ConvertFrom-Json
        if($installState.mode -ne 'service' -or $identity.serviceName -cne [string]$installState.serviceName -or $identity.serviceAccount -cne [string]$installState.serviceAccount) {throw 'Recorded service identity does not match the configured installation'}
        if([string]$identity.neo4jHome -ine [string]$installState.neo4jHome){throw 'Recorded Deposit service installation changed'}
        $service=Get-CimInstance Win32_Service -Filter "Name='$($installState.serviceName)'"
        if(!$service -or [string]$service.State -eq 'Stopped'){return $null}
        $serviceIdentity=Get-DepositServiceProcessIdentity $installation ([string]$installState.serviceName) ([string]$installState.serviceAccount)
        if([string]$serviceIdentity.runtimeSid -cne [string]$identity.runtimeSid){throw 'Recorded Deposit service runtime SID changed'}
        $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($serviceIdentity.pid)"
        if([long]$identity.pid -ne [long]$serviceIdentity.pid -or [long]$identity.startMillis -ne [long]$serviceIdentity.startMillis){Write-Json @{pid=$serviceIdentity.pid;created=$process.CreationDate.ToUniversalTime().ToString('o');startMillis=$serviceIdentity.startMillis;runtimeSid=$serviceIdentity.runtimeSid;serviceName=$serviceIdentity.name;serviceAccount=$serviceIdentity.serviceAccount;neo4jHome=$installation;mode='service';releaseMode=$true} (Join-Path $run 'process.json')}
        return $process
    }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($identity.pid)"
    if (!$process -or !$process.CreationDate -or !$process.CommandLine) { return $null }
    if ($process.CreationDate.ToUniversalTime().ToString('o') -ne $identity.created -or !$process.CommandLine.Contains($installation)) {
        throw 'PID no longer identifies the owned Neo4j process'
    }
    return $process
}

function Write-ServiceStartDiagnostics($State,[string]$RuntimeSid,[string]$Evidence,[string]$Run,[string]$Repo) {
    $diagnostic=[ordered]@{capturedUtc=[DateTime]::UtcNow.ToString('o');serviceName=[string]$State.serviceName;runtimeSid=$RuntimeSid;configuredSecretRoot=$null;secretRootState=[string]$State.secretRoot;pathComponents=@();helperFiles=@();captureErrors=@()}
    try {
        $config=Join-Path ([string]$State.neo4jHome) 'conf\deposit.properties'
        $matches=[regex]::Matches([IO.File]::ReadAllText($config),'(?m)^\s*secretRoot\s*=\s*(.*?)\s*$')
        if($matches.Count -eq 1 -and ![string]::IsNullOrWhiteSpace($matches[0].Groups[1].Value)) {
            $secretRoot=[IO.Path]::GetFullPath($matches[0].Groups[1].Value.Trim())
            $diagnostic.configuredSecretRoot=$secretRoot
            $pathRoot=[IO.Path]::GetPathRoot($secretRoot)
            $components=New-Object 'System.Collections.Generic.List[string]'
            $components.Add($pathRoot)
            $current=$pathRoot
            $relative=$secretRoot.Substring($pathRoot.Length).Trim('\')
            foreach($part in @([regex]::Split($relative,'\\')|Where-Object {$_})) {$current=Join-Path $current $part;$components.Add($current)}
            foreach($component in $components) {
                $entry=[ordered]@{path=$component;exists=$false;owner=$null;sddl=$null;runtimeAces=@();error=$null}
                try {
                    if(Test-Path -LiteralPath $component) {
                        $entry.exists=$true
                        $acl=Get-Acl -LiteralPath $component -ErrorAction Stop
                        $entry.owner=[string]$acl.Owner
                        $entry.sddl=$acl.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::All)
                        if($RuntimeSid) {
                            $entry.runtimeAces=@($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]) | Where-Object {$_.IdentityReference.Value -ceq $RuntimeSid} | ForEach-Object {@{sid=$_.IdentityReference.Value;type=[string]$_.AccessControlType;rights=[string]$_.FileSystemRights;inheritance=[string]$_.InheritanceFlags;propagation=[string]$_.PropagationFlags;isInherited=[bool]$_.IsInherited}})
                        }
                    }
                } catch {$entry.error=$_.Exception.GetType().FullName+': '+$_.Exception.Message}
                $diagnostic.pathComponents+=@($entry)
            }
        } else {$diagnostic.captureErrors+=@('Could not read exactly one configured secretRoot from deposit.properties')}
    } catch {$diagnostic.captureErrors+=@('Secret path capture failed: '+$_.Exception.GetType().FullName+': '+$_.Exception.Message)}
    foreach($file in @(@{role='installed';path=(Join-Path $Run 'distribution\helpers\LauncherService.ps1')},@{role='source';path=(Join-Path $Repo 'runtime\distribution\helpers\LauncherService.ps1')})) {
        $identity=[ordered]@{role=$file.role;path=$file.path;exists=$false;length=$null;lastWriteUtc=$null;sha256=$null;error=$null}
        try {
            if(Test-Path -LiteralPath $file.path -PathType Leaf) {
                $item=Get-Item -LiteralPath $file.path -ErrorAction Stop
                $identity.exists=$true;$identity.length=[long]$item.Length;$identity.lastWriteUtc=$item.LastWriteTimeUtc.ToString('o')
                $identity.sha256=(Get-FileHash -LiteralPath $file.path -Algorithm SHA256 -ErrorAction Stop).Hash
            }
        } catch {$identity.error=$_.Exception.GetType().FullName+': '+$_.Exception.Message}
        $diagnostic.helperFiles+=@($identity)
    }
    try {[IO.File]::WriteAllText((Join-Path $Evidence 'service-start-diagnostics.json'),($diagnostic|ConvertTo-Json -Depth 12),$utf8)} catch {}
}

function Test-LauncherAncestry($Process,$Launcher) {
    $current=$Process
    for($depth=0;$depth -lt 12;$depth++) {
        if (!$current -or !$current.CreationDate) {return $false}
        if ($current.ProcessId -eq $Launcher.Id) {return [Math]::Abs(($current.CreationDate-$Launcher.StartTime).TotalSeconds) -lt 1}
        if ($current.CreationDate -lt $Launcher.StartTime.AddSeconds(-1)) {return $false}
        $current=Get-CimInstance Win32_Process -Filter "ProcessId=$($current.ParentProcessId)"
    }
    return $false
}

function Invoke-Start($Context,[string]$ScriptsDir) {$Root=$Context.Root; $repo=$Context.Repo; $run=$Context.Run; $evidence=$Context.Evidence; $manifest=$Context.Manifest; $spatialLock=$Context.SpatialLock; $ocflLock=$Context.OcflLock; $formatLock=$Context.FormatLock; $RunId=$Context.RunId; $Port=$Context.Port; $ReleasePackage=$Context.ReleasePackage; $BoltUri=$Context.BoltUri; $DiagnosticBuild=$Context.DiagnosticBuild; $WithoutProbe=$Context.WithoutProbe; $StartupTimeoutSeconds=$Context.StartupTimeoutSeconds; $installation=$Context.Installation; $credentialTarget=$Context.CredentialTarget; $utf8=$Context.Utf8;
    Assert-Run
    if(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains($Root) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'}) {throw 'A test harness Neo4j process is already running; only one instance is authorized'}
    if(!(Test-Path -LiteralPath "$installation\bin\neo4j.bat" -PathType Leaf)) {throw 'Installed Neo4j launcher is missing'}
    $null=Get-ToolDirectory $manifest 'temurin' $Root
    $actualNames=@(Get-ChildItem "$installation\plugins" -Filter *.jar | ForEach-Object Name | Sort-Object)
    if(!$actualNames.Count) {throw 'The installed server has no plugin JARs'}
    if (Get-OwnedProcess) { throw 'Owned server is already running' }
    $owner=Get-Content (Join-Path $run 'owner.json') -Raw | ConvertFrom-Json
    if($owner.releaseMode){
        $archive=Join-Path $run 'distribution\deposit.bat'
        $env:JAVA_HOME=Join-Path $Root $manifest.javaHome;$env:NEO4J_HOME=$installation;$env:NEO4J_CONF=Join-Path $installation 'conf';$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
        $installState=Get-Content (Join-Path $run 'distribution\deposit.install.json') -Raw|ConvertFrom-Json
        if($installState.mode -eq 'service') {
            $launcherProcess=Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\cmd.exe') -ArgumentList ('/d /c ""'+$archive+'" start"') -PassThru -WindowStyle Hidden -RedirectStandardOutput "$evidence\release-start.out.log" -RedirectStandardError "$evidence\release-start.err.log"
            try {if(!$launcherProcess.WaitForExit(150000)){$launcherProcess.Kill();throw 'Packaged service start timed out'};$launcherExitCode=$launcherProcess.ExitCode} finally {$launcherProcess.Dispose()}
            . (Join-Path $ScriptsDir 'Request-DepositStop.ps1')
            $deadline=[DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds);$serviceIdentity=$null
            while([DateTime]::UtcNow -lt $deadline -and !$serviceIdentity){try{$serviceIdentity=Get-DepositServiceProcessIdentity $installation ([string]$installState.serviceName) ([string]$installState.serviceAccount)}catch{Start-Sleep -Milliseconds 250}}
            if(!$serviceIdentity){throw "Configured Deposit service did not start (launcher exit $launcherExitCode). Inspect release-start.out.log and release-start.err.log."}
            $serviceProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($serviceIdentity.pid)"
            $created=$serviceProcess.CreationDate.ToUniversalTime().ToString('o')
            Write-Json @{pid=$serviceIdentity.pid;created=$created;startMillis=$serviceIdentity.startMillis;runtimeSid=$serviceIdentity.runtimeSid;serviceName=$serviceIdentity.name;serviceAccount=$serviceIdentity.serviceAccount;neo4jHome=$installation;mode='service';releaseMode=$true} "$run\process.json"
            try {Write-ServiceStartDiagnostics $installState ([string]$serviceIdentity.runtimeSid) $evidence $run $repo} catch {}
            $readinessDeadline=[DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds);$ready=$false;$lastReadinessError=$null
            while([DateTime]::UtcNow -lt $readinessDeadline -and !$ready){try{Invoke-Readiness $readinessDeadline;$ready=$true}catch{$lastReadinessError=$_.Exception.Message;Start-Sleep -Milliseconds 500}}
            if(!$ready){throw "Deposit service startup readiness timed out: $lastReadinessError. Server retained for inspection."}
            return
        }
        $journalPath=[Environment]::GetEnvironmentVariable('DEPOSIT_SUITE_JOURNAL','Process')
        if([string]::IsNullOrWhiteSpace($journalPath)){throw 'Packaged local runtime start requires an active run journal'}
        $journal=Read-SuiteJournal $journalPath
        if($journal.state -cne 'running' -or $journal.terminalCleanup -or $journal.ownerSid -cne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value){throw 'Packaged local runtime start is not admitted by the active run owner'}
        foreach($previous in @($journal.runtimeJobs)){
            if($previous.state -cne 'retired'){$members=@([DepositSuiteJobs]::Members([string]$previous.jobName));try{if($members.Count){throw 'A previous local runtime containment job is still active'}}finally{foreach($member in $members){$member.Dispose()}};throw 'A previous local runtime generation was not retired'}
        }
        $generation=[Guid]::NewGuid().ToString('N');$jobName='Local\DepositRuntime-'+$journal.invocationId+'-'+$generation
        $gate=Join-Path $run ('tmp\runtime-'+$generation+'.gate')
        $localCommand='while(!(Test-Path -LiteralPath '''+$gate.Replace("'","''")+''')){Start-Sleep -Milliseconds 50}; & '''+$archive.Replace("'","''")+''' start; exit $LASTEXITCODE'
        $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($localCommand))
        $launcherProcess=Start-Process -FilePath $PSHOME\powershell.exe -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput "$evidence\release-start.out.log" -RedirectStandardError "$evidence\release-start.err.log"
        $runtimeEntry=$null
        try {
            $null=$launcherProcess.Handle
            $runtimeEntry=[pscustomobject]@{pid=$launcherProcess.Id;startTicks=$launcherProcess.StartTime.ToUniversalTime().Ticks;label='local-runtime-'+$generation.Substring(0,8);jobName=$jobName;jobAcknowledged=$false;generation=$generation;state='launching';serverPid=$null;serverStartTicks=$null}
            [DepositSuiteJobs]::Create($jobName,$launcherProcess.Id)
            Update-SuiteJournal $journalPath {param($j)if($j.invocationId -cne $journal.invocationId -or $j.state -cne 'running' -or $j.terminalCleanup){throw 'Run owner changed during local runtime registration'};$j.runtimeJobs=@($j.runtimeJobs)+@($runtimeEntry)}
            $ackDeadline=[DateTime]::UtcNow.AddSeconds(30)
            do{$registered=Read-SuiteJournal $journalPath;$ack=@($registered.runtimeJobs|Where-Object {$_.generation -ceq $generation -and $_.jobName -ceq $jobName});if($ack.Count -eq 1 -and $ack[0].jobAcknowledged){break};if($launcherProcess.HasExited -or [DateTime]::UtcNow -gt $ackDeadline){throw 'Watchdog did not acknowledge packaged local runtime containment before launch'};Start-Sleep -Milliseconds 100}while($true)
            [IO.File]::WriteAllText($gate,'go')
            if(!$launcherProcess.WaitForExit(150000)){throw 'Packaged local runtime start timed out'}
            $launcherProcess.Refresh();if($launcherProcess.ExitCode -ne 0){throw 'Packaged local runtime start failed; inspect release-start logs'}
            $identityPath=Join-Path $installation 'deposit-process.json';$deadline=[DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds);$identity=$null
            while([DateTime]::UtcNow -lt $deadline -and !$identity){if(Test-Path -LiteralPath $identityPath){$identity=Get-Content -LiteralPath $identityPath -Raw|ConvertFrom-Json}else{Start-Sleep -Milliseconds 250}}
            if(!$identity){throw 'Packaged launcher did not record process ownership'}
            $server=Get-CimInstance Win32_Process -Filter "ProcessId=$([long]$identity.pid)"
            if(!$server -or !$server.CommandLine -or !$server.CommandLine.Contains($installation) -or $server.Name -ine 'java.exe' -or $server.CommandLine -notmatch 'org\.neo4j\.server\.Neo4jCommunity|org\.neo4j\.server\.startup\.Neo4jCommand'){throw 'Packaged launcher process record does not identify this Neo4j installation'}
            $serverHandle=Get-Process -Id ([int]$server.ProcessId) -ErrorAction Stop
            try{if(![DepositSuiteJobs]::Contains($jobName,$serverHandle)){throw 'Packaged Neo4j server did not join its registered runtime containment job'};$serverStartTicks=$serverHandle.StartTime.ToUniversalTime().Ticks}finally{$serverHandle.Dispose()}
            $runtimeEntry.serverPid=[long]$server.ProcessId;$runtimeEntry.serverStartTicks=[long]$serverStartTicks;$runtimeEntry.state='running'
            Update-SuiteJournal $journalPath {param($j)if($j.invocationId -cne $journal.invocationId -or $j.terminalCleanup){throw 'Run owner changed before packaged runtime publication'};foreach($entry in @($j.runtimeJobs)){if($entry.generation -ceq $generation){$entry.serverPid=[long]$server.ProcessId;$entry.serverStartTicks=[long]$serverStartTicks;$entry.state='running'}}}
            Write-Json @{pid=$identity.pid;created=$identity.created;launcherPid=$runtimeEntry.pid;launcherStartTicks=$runtimeEntry.startTicks;runtimeJobName=$jobName;runtimeGeneration=$generation;releaseMode=$true} "$run\process.json"
            Invoke-Readiness ([DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds))
            return
        } catch {
            if($runtimeEntry){try{Stop-SuiteChildTree $runtimeEntry}catch{};try{Update-SuiteJournal $journalPath {param($j)foreach($entry in @($j.runtimeJobs)){if($entry.generation -ceq $generation){$entry.state='retired'}}}}catch{}}
            elseif($launcherProcess -and !$launcherProcess.HasExited){try{$launcherProcess.Kill();$launcherProcess.WaitForExit(5000)}catch{}}
            throw
        } finally {if(Test-Path -LiteralPath $gate){Remove-Item -LiteralPath $gate -Force -ErrorAction SilentlyContinue};if($launcherProcess){$launcherProcess.Dispose()}}
    }
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$Port)
    try {$listener.Start()} finally {$listener.Stop()}
    $env:NEO4J_HOME=$installation; $env:NEO4J_CONF="$installation\conf"
    $logFile="$installation\logs\neo4j.log"
    $oldLength=0
    if (Test-Path $logFile) {$oldLength=(Get-Item $logFile).Length}
    # Contain the whole local console launch separately from its suite/scenario
    # owner. The gate ensures the watchdog retains the job before Neo4j starts.
    $journalPath=[Environment]::GetEnvironmentVariable('DEPOSIT_SUITE_JOURNAL','Process')
    if([string]::IsNullOrWhiteSpace($journalPath)){throw 'Local runtime start requires an active run journal'}
    $journal=Read-SuiteJournal $journalPath
    if($journal.state -cne 'running' -or $journal.terminalCleanup -or $journal.ownerSid -cne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value){throw 'Local runtime start is not admitted by the active run owner'}
    foreach($previous in @($journal.runtimeJobs)){
        if($previous.state -cne 'retired'){
            $members=@([DepositSuiteJobs]::Members([string]$previous.jobName))
            try{if($members.Count){throw 'A previous local runtime containment job is still active'} }finally{foreach($member in $members){$member.Dispose()}}
            throw 'A previous local runtime generation was not retired'
        }
    }
    $generation=[Guid]::NewGuid().ToString('N')
    $jobName='Local\DepositRuntime-'+$journal.invocationId+'-'+$generation
    $gate=Join-Path $run ('tmp\runtime-'+$generation+'.gate')
    $command='while(!(Test-Path -LiteralPath '''+$gate.Replace("'","''")+''')){Start-Sleep -Milliseconds 50}; & '''+(Join-Path $installation 'bin\neo4j.bat').Replace("'","''")+''' console'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $launcher=Start-Process powershell.exe -ArgumentList @('-NoProfile','-NonInteractive','-EncodedCommand',$encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput "$evidence\console.out.log" -RedirectStandardError "$evidence\console.err.log"
    $runtimeEntry=$null
    try {
        $null=$launcher.Handle
        $runtimeEntry=[pscustomobject]@{pid=$launcher.Id;startTicks=$launcher.StartTime.ToUniversalTime().Ticks;label='local-runtime-'+$generation.Substring(0,8);jobName=$jobName;jobAcknowledged=$false;generation=$generation;state='launching';serverPid=$null;serverStartTicks=$null}
        [DepositSuiteJobs]::Create($jobName,$launcher.Id)
        Update-SuiteJournal $journalPath {param($j) if($j.invocationId -cne $journal.invocationId -or $j.state -cne 'running' -or $j.terminalCleanup){throw 'Run owner changed during local runtime registration'};$j.runtimeJobs=@($j.runtimeJobs)+@($runtimeEntry)}
        $ackDeadline=[DateTime]::UtcNow.AddSeconds(30)
        do{
            $registered=Read-SuiteJournal $journalPath
            $ack=@($registered.runtimeJobs|Where-Object {$_.generation -ceq $generation -and $_.jobName -ceq $jobName})
            if($ack.Count -eq 1 -and $ack[0].jobAcknowledged){break}
            if($launcher.HasExited -or [DateTime]::UtcNow -gt $ackDeadline){throw 'Watchdog did not acknowledge local runtime containment before launch'}
            Start-Sleep -Milliseconds 100
        }while($true)
        [IO.File]::WriteAllText($gate,'go')
        $deadline=[DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
        $server=$null
        while ([DateTime]::UtcNow -lt $deadline) {
            $server = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($installation) -and $_.CommandLine.Contains('org.neo4j.server.Neo4jCommunity') } | Select-Object -First 1
            if ($server -and (Test-LauncherAncestry $server $launcher)) {break}
            $server=$null
            if ($launcher.HasExited) {throw 'Neo4j launcher exited before starting server'}
            Start-Sleep -Milliseconds 500
        }
        if (!$server) {throw 'Neo4j JVM startup timeout'}
        $serverHandle=Get-Process -Id ([int]$server.ProcessId) -ErrorAction Stop
        try {
            if(![DepositSuiteJobs]::Contains($jobName,$serverHandle)){throw 'Neo4j server did not join its registered runtime containment job'}
            $serverStartTicks=$serverHandle.StartTime.ToUniversalTime().Ticks
        } finally {$serverHandle.Dispose()}
        $runtimeEntry.serverPid=[long]$server.ProcessId;$runtimeEntry.serverStartTicks=[long]$serverStartTicks;$runtimeEntry.state='running'
        Update-SuiteJournal $journalPath {param($j) if($j.invocationId -cne $journal.invocationId -or $j.terminalCleanup){throw 'Run owner changed before local runtime publication'};foreach($entry in @($j.runtimeJobs)){if($entry.generation -ceq $generation){$entry.serverPid=[long]$server.ProcessId;$entry.serverStartTicks=[long]$serverStartTicks;$entry.state='running'}}}
        Write-Json @{pid=$server.ProcessId;created=$server.CreationDate.ToUniversalTime().ToString('o');launcherPid=$launcher.Id;launcherStartTicks=$runtimeEntry.startTicks;runtimeJobName=$jobName;runtimeGeneration=$generation} "$run\process.json"
    } catch {
        if($runtimeEntry){
            try{Stop-SuiteChildTree $runtimeEntry}catch{}
            try{Update-SuiteJournal $journalPath {param($j) foreach($entry in @($j.runtimeJobs)){if($entry.generation -ceq $generation){$entry.state='retired'}}}}catch{}
        } elseif($launcher -and !$launcher.HasExited){try{$launcher.Kill();$launcher.WaitForExit(5000)}catch{}}
        throw
    } finally {
        if(Test-Path -LiteralPath $gate){Remove-Item -LiteralPath $gate -Force -ErrorAction SilentlyContinue}
        if($launcher){$launcher.Dispose()}
    }
    while ([DateTime]::UtcNow -lt $deadline) {
        if (!(Get-OwnedProcess)) {throw 'Neo4j exited during startup; inspect startup logs'}
        if (Test-Path $logFile) {
            $stream=[IO.File]::Open($logFile,'Open','Read','ReadWrite')
            try {
                [void]$stream.Seek($oldLength,'Begin')
                $reader=New-Object IO.StreamReader($stream)
                $newLog=$reader.ReadToEnd()
            } finally {$stream.Dispose()}
            if ($newLog -match 'Started\.') {
                Invoke-Readiness $deadline
                return
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'Neo4j readiness timeout'
}

function Invoke-Readiness([DateTime]$Deadline) {
    $previousExpectedVersion=[Environment]::GetEnvironmentVariable('DEPOSIT_EXPECTED_VERSION','Process')
    try {
        $env:DEPOSIT_TEST_PASSWORD=[DepositHarnessSecretStore]::Read($credentialTarget)
    $expectedPlugin=@(Get-ChildItem -LiteralPath (Join-Path $installation 'plugins') -Filter 'deposit-plugin*.jar' -File)
        if($expectedPlugin.Count -ne 1){throw 'Installed plugins must contain exactly one versioned Deposit plugin JAR'}
        $env:DEPOSIT_EXPECTED_VERSION=Get-DepositJarVersion $expectedPlugin[0].FullName
        $remaining=[int]($Deadline-[DateTime]::UtcNow).TotalMilliseconds
        if($remaining -le 0) {throw 'Readiness deadline exhausted'}
        $readinessUri=if($BoltUri){$BoltUri}else{"bolt://127.0.0.1:$Port"}
        $arguments="-Djava.io.tmpdir=$env:TEMP -cp `"$Root\build\deposit-integration-tests\target\classes;$Root\build\deposit-integration-tests\target\dependency\*`" org.deposit.it.ReadinessClient `"$readinessUri`""
        $client=Start-Process "$env:JAVA_HOME\bin\java.exe" -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput "$evidence\readiness.out.log" -RedirectStandardError "$evidence\readiness.err.log"
        [void]$client.Handle
        try {
            if(!$client.WaitForExit($remaining)) {$client.Kill(); throw 'Authenticated readiness timed out'}
            if ($client.ExitCode -ne 0) {throw 'Deposit startup readiness rejected; see readiness.err.log. Server retained for inspection.'}
    } finally {$client.Dispose()}
    } finally {$ErrorActionPreference='Stop';[Environment]::SetEnvironmentVariable('DEPOSIT_EXPECTED_VERSION',$previousExpectedVersion,'Process'); Remove-Item Env:\DEPOSIT_TEST_PASSWORD -ErrorAction SilentlyContinue}
}

function Invoke-Stop($Context,[string]$ScriptsDir,[bool]$Force) {$Root=$Context.Root; $repo=$Context.Repo; $run=$Context.Run; $evidence=$Context.Evidence; $manifest=$Context.Manifest; $spatialLock=$Context.SpatialLock; $ocflLock=$Context.OcflLock; $formatLock=$Context.FormatLock; $RunId=$Context.RunId; $Port=$Context.Port; $ReleasePackage=$Context.ReleasePackage; $BoltUri=$Context.BoltUri; $DiagnosticBuild=$Context.DiagnosticBuild; $WithoutProbe=$Context.WithoutProbe; $StartupTimeoutSeconds=$Context.StartupTimeoutSeconds; $installation=$Context.Installation; $credentialTarget=$Context.CredentialTarget; $utf8=$Context.Utf8;
    $recordPath=Join-Path $run 'process.json'
    if(!(Test-Path -LiteralPath $recordPath)){return}
    $record=Get-Content -LiteralPath $recordPath -Raw|ConvertFrom-Json
    $owned=Get-OwnedProcess
    if($record.PSObject.Properties['mode'] -and $record.mode -eq 'service') {
        if(!$owned){return}
        $processHandle=Get-Process -Id $owned.ProcessId
        [void]$processHandle.Handle
        try {
            if($Force){Stop-Process -Id $owned.ProcessId -Force}
            else {
                $owner=Get-Content (Join-Path $run 'owner.json') -Raw|ConvertFrom-Json
                if($owner.releaseMode){$env:JAVA_HOME=Join-Path $Root $manifest.javaHome;$env:NEO4J_HOME=$installation;$env:NEO4J_CONF=Join-Path $installation 'conf';$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH;$ErrorActionPreference='Continue';& (Join-Path $run 'distribution\deposit.bat') stop *> "$evidence\release-stop.log";$stopCode=$LASTEXITCODE;$ErrorActionPreference='Stop';if($stopCode -ne 0){throw 'Packaged deposit.bat stop failed'} }
                else {. (Join-Path $ScriptsDir 'Request-DepositStop.ps1');$null=Request-DepositStop -Neo4jHome $installation -TargetProcessId $owned.ProcessId}
            }
            if(!$processHandle.WaitForExit(60000)){throw 'Owned service process did not stop within 60 seconds after Deposit cleanup'}
        } finally {$processHandle.Dispose()}
        return
    }
    if(!$record.runtimeJobName -or !$record.runtimeGeneration -or !$record.launcherPid -or !$record.launcherStartTicks){throw 'Local runtime record lacks its containment generation identity'}
    $journalPath=[Environment]::GetEnvironmentVariable('DEPOSIT_SUITE_JOURNAL','Process')
    if([string]::IsNullOrWhiteSpace($journalPath)){throw 'Local runtime stop requires its active run journal'}
    $journal=Read-SuiteJournal $journalPath
    if($journal.state -cne 'running' -or $journal.terminalCleanup -or $journal.ownerSid -cne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value){throw 'Local runtime stop is not admitted by the active run owner'}
    $runtime=@($journal.runtimeJobs|Where-Object {$_.generation -ceq [string]$record.runtimeGeneration -and $_.jobName -ceq [string]$record.runtimeJobName})
    if($runtime.Count -ne 1 -or !$runtime[0].jobAcknowledged -or $runtime[0].pid -ne [long]$record.launcherPid -or $runtime[0].startTicks -ne [long]$record.launcherStartTicks -or $runtime[0].state -cnotin @('launching','running')){throw 'Local runtime containment identity differs from the protected run journal'}
    $runtimeEntry=$runtime[0]
    $members=@([DepositSuiteJobs]::Members([string]$runtimeEntry.jobName))
    try {
        if($owned){
            $serverHandle=Get-Process -Id ([int]$owned.ProcessId) -ErrorAction Stop
            try {
                if(![DepositSuiteJobs]::Contains([string]$runtimeEntry.jobName,$serverHandle) -or [long]$runtimeEntry.serverPid -ne [long]$serverHandle.Id -or [long]$runtimeEntry.serverStartTicks -ne $serverHandle.StartTime.ToUniversalTime().Ticks){throw 'Recorded Neo4j server is outside its runtime containment generation'}
            } finally {$serverHandle.Dispose()}
        }
        if(!$members.Count -and $owned){throw 'Owned server is missing from its runtime containment job'}
    } finally {foreach($member in $members){$member.Dispose()}}
    if($owned -and !$Force){
        $processHandle=Get-Process -Id ([int]$owned.ProcessId)
        [void]$processHandle.Handle
        try {
            $owner=Get-Content (Join-Path $run 'owner.json') -Raw|ConvertFrom-Json
            if($owner.releaseMode){$env:JAVA_HOME=Join-Path $Root $manifest.javaHome;$env:NEO4J_HOME=$installation;$env:NEO4J_CONF=Join-Path $installation 'conf';$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH;$ErrorActionPreference='Continue';& (Join-Path $run 'distribution\deposit.bat') stop *> "$evidence\release-stop.log";$stopCode=$LASTEXITCODE;$ErrorActionPreference='Stop';if($stopCode -ne 0){throw 'Packaged deposit.bat stop failed'}}
            else {. (Join-Path $ScriptsDir 'Request-DepositStop.ps1');$drain=Request-DepositStop -Neo4jHome $installation -TargetProcessId $owned.ProcessId;$identityBase64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($drain|ConvertTo-Json -Compress)));$stopArgs="-NoProfile -File `"$ScriptsDir\Stop-Console.ps1`" -IdentityBase64 $identityBase64";$helper=Start-Process powershell.exe -ArgumentList $stopArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput "$evidence\stop.log" -RedirectStandardError "$evidence\stop.err.log";[void]$helper.Handle;try{if(!$helper.WaitForExit(10000)){$helper.Kill();throw 'Graceful console signal timed out'};if($helper.ExitCode -ne 0){throw 'Graceful console signal failed'}}finally{$helper.Dispose()}}
            if(!$processHandle.WaitForExit(60000)){throw 'Owned server did not stop within 60 seconds after Deposit cleanup'}
        } finally {$processHandle.Dispose()}
    } elseif($owned -and $Force) {
        Stop-Process -Id ([int]$owned.ProcessId) -Force
        $killed=Get-Process -Id ([int]$owned.ProcessId) -ErrorAction SilentlyContinue
        if($killed){try{if(!$killed.WaitForExit(10000)){throw 'Owned Neo4j JVM did not exit after the crash signal'}}finally{$killed.Dispose()}}
    }
    if(!$Force -and $owned){
        $deadline=[DateTime]::UtcNow.AddSeconds(15)
        do{$remaining=@([DepositSuiteJobs]::Members([string]$runtimeEntry.jobName));try{if(!$remaining.Count){break}}finally{foreach($member in $remaining){$member.Dispose()}};Start-Sleep -Milliseconds 100}while([DateTime]::UtcNow -lt $deadline)
        if($remaining.Count){throw 'Graceful stop left processes in the local runtime containment job'}
    } else {
        # The server crash should let Neo4j's console JVM exit naturally. Retire
        # any remaining members only from this exact acknowledged runtime job.
        $graceDeadline=[DateTime]::UtcNow.AddSeconds(3)
        do{$remaining=@([DepositSuiteJobs]::Members([string]$runtimeEntry.jobName));try{if(!$remaining.Count){break}}finally{foreach($member in $remaining){$member.Dispose()}};Start-Sleep -Milliseconds 100}while([DateTime]::UtcNow -lt $graceDeadline)
        if($remaining.Count){Stop-SuiteChildTree $runtimeEntry -Deadline ([DateTime]::UtcNow.AddSeconds(20))}
        $remaining=@([DepositSuiteJobs]::Members([string]$runtimeEntry.jobName))
        try{if($remaining.Count){throw 'Local runtime containment job remains active after bounded retirement'}}finally{foreach($member in $remaining){$member.Dispose()}}
    }
    Update-SuiteJournal $journalPath {param($j) if($j.invocationId -cne $journal.invocationId){throw 'Run owner changed during local runtime retirement'};foreach($entry in @($j.runtimeJobs)){if($entry.generation -ceq [string]$record.runtimeGeneration){$entry.state='retired';$entry|Add-Member -NotePropertyName retiredUtc -NotePropertyValue ([DateTime]::UtcNow.ToString('o')) -Force}}}
}

function Invoke-Reset($Context,[string]$ScriptsDir) {$Root=$Context.Root; $repo=$Context.Repo; $run=$Context.Run; $evidence=$Context.Evidence; $manifest=$Context.Manifest; $spatialLock=$Context.SpatialLock; $ocflLock=$Context.OcflLock; $formatLock=$Context.FormatLock; $RunId=$Context.RunId; $Port=$Context.Port; $ReleasePackage=$Context.ReleasePackage; $BoltUri=$Context.BoltUri; $DiagnosticBuild=$Context.DiagnosticBuild; $WithoutProbe=$Context.WithoutProbe; $StartupTimeoutSeconds=$Context.StartupTimeoutSeconds; $installation=$Context.Installation; $credentialTarget=$Context.CredentialTarget; $utf8=$Context.Utf8;
    Assert-Run
    if(Get-OwnedProcess) {throw 'Stop the owned Neo4j process before resetting its installation'}
    if(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains($Root) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'}) {throw 'A test harness Neo4j process is still running'}
    $resolved=[IO.Path]::GetFullPath($run).TrimEnd('\')
    if($resolved -ne (Join-Path $Root 'runs\test-host')) {throw 'Reset target is not the authorized installation'}
    if(@(Get-Item -LiteralPath $Root,(Join-Path $Root 'runs'),$run | Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count -or
       @(Get-ChildItem -LiteralPath $run -Recurse -Force | Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count) {throw 'Reset refuses reparse points'}
    if(@(Get-ChildItem "$run\secrets" -File -Recurse).Count) {throw 'Reset refuses temporary secret files'}
    foreach($item in (Get-ChildItem -LiteralPath $run -Force | Where-Object Name -ne 'phase.lock')) {
        $target=[IO.Path]::GetFullPath($item.FullName)
        if(!$target.StartsWith($resolved+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'Reset target escaped the owned run'}
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    New-Item -ItemType Directory -Force "$run\tmp" | Out-Null
}
