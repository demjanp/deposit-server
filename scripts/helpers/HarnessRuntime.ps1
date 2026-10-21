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
    if (!$DiagnosticBuild -and !(Test-Path -LiteralPath "$Root\build\deposit-plugin\target\deposit-plugin-0.0.0-dev.jar" -PathType Leaf)) {throw 'Build the plugin before installation'}
    if (Test-Path "$run\owner.json") { Assert-Run; throw 'Stop and reset test-host before reinstalling' }
    $releaseMode=![string]::IsNullOrWhiteSpace($ReleasePackage)
    Write-Json @{runId=$RunId;root=$Root;port=$Port;diagnostic=[bool]$DiagnosticBuild;releaseMode=$releaseMode} "$run\owner.json"
    Copy-Item -LiteralPath $runtimeDirectory -Destination $installation -Recurse
    New-Item -ItemType Directory -Force "$run\resources\staging","$run\resources\ocfl","$run\resources\ocfl-work","$run\exports","$run\secrets" | Out-Null
    Set-OwnerOnlyDirectory "$run\secrets"
    if($releaseMode) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archivePath=[IO.Path]::GetFullPath($ReleasePackage)
        if(!(Test-Path -LiteralPath $archivePath -PathType Leaf)){throw 'Release package does not exist'}
        $distribution=Join-Path $run 'distribution';New-Item -ItemType Directory -Path $distribution -Force|Out-Null
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
        $env:JAVA_HOME=Join-Path $Root $manifest.javaHome;$env:NEO4J_HOME=$installation;$env:NEO4J_CONF=Join-Path $installation 'conf';$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
        $launcher=Join-Path $distribution 'deposit.bat';$arguments=@('setup',$installation,$env:JAVA_HOME,(Join-Path $run 'resources'),(Join-Path $run 'exports'),[string]$Port)
        $ErrorActionPreference='Continue';& $launcher @arguments *> "$evidence\release-setup.log";$ErrorActionPreference='Stop'
        if($LASTEXITCODE -ne 0){throw 'Packaged deposit.bat setup failed; see release-setup.log'}
        . "$ScriptsDir\ReleaseManifest.ps1"
        $expectedInstallation=@{neo4jHome=$installation;javaHome=$env:JAVA_HOME;dataRoot=(Join-Path $run 'resources');exportRoot=(Join-Path $run 'exports');mode='local'}
        if(!(Test-DepositReleasePackage -Root $distribution -ConfiguredInstallation -ExpectedInstallation $expectedInstallation)){throw 'Configured release installation failed manifest verification'}
    } else {
    $plugin = "$Root\build\deposit-plugin\target\deposit-plugin-0.0.0-dev.jar"
    $probe = "$Root\build\deposit-runtime-probe\target\deposit-runtime-probe-0.0.0-dev.jar"
    if ($DiagnosticBuild) { $plugin="$Root\diagnostic-build\deposit-plugin.jar"; $probe="$Root\diagnostic-build\deposit-runtime-probe.jar" }
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
            $readinessDeadline=[DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds);$ready=$false;$lastReadinessError=$null
            while([DateTime]::UtcNow -lt $readinessDeadline -and !$ready){try{Invoke-Readiness $readinessDeadline;$ready=$true}catch{$lastReadinessError=$_.Exception.Message;Start-Sleep -Milliseconds 500}}
            if(!$ready){throw "Deposit service startup readiness timed out: $lastReadinessError. Server retained for inspection."}
            return
        }
        $launcherArguments='/d /c ""'+$archive+'" start"'
        $launcherProcess=Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\cmd.exe') -ArgumentList $launcherArguments -PassThru -WindowStyle Hidden -RedirectStandardOutput "$evidence\release-start.out.log" -RedirectStandardError "$evidence\release-start.err.log"
        try {if(!$launcherProcess.WaitForExit(150000)){$launcherProcess.Kill();throw 'Packaged deposit.bat start timed out'}} finally {$launcherProcess.Dispose()}
        $identityPath=Join-Path $installation 'deposit-process.json';$deadline=[DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds);$identity=$null
        while([DateTime]::UtcNow -lt $deadline -and !$identity){if(Test-Path -LiteralPath $identityPath){$identity=Get-Content -LiteralPath $identityPath -Raw|ConvertFrom-Json}else{Start-Sleep -Milliseconds 250}}
        if(!$identity){throw 'Packaged launcher did not record process ownership'}
        Write-Json @{pid=$identity.pid;created=$identity.created;launcherPid=$identity.workerPid;releaseMode=$true} "$run\process.json"
        Invoke-Readiness ([DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds))
        return
    }
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$Port)
    try {$listener.Start()} finally {$listener.Stop()}
    $env:NEO4J_HOME=$installation; $env:NEO4J_CONF="$installation\conf"
    $logFile="$installation\logs\neo4j.log"
    $oldLength=0
    if (Test-Path $logFile) {$oldLength=(Get-Item $logFile).Length}
    # Neo4j's PowerShell launcher starts its JVM as a descendant of this hidden shell.
    $command = "& '$installation\bin\neo4j.bat' console"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $launcher = Start-Process powershell.exe -ArgumentList '-NoProfile','-EncodedCommand',$encoded -WindowStyle Hidden -PassThru -RedirectStandardOutput "$evidence\console.out.log" -RedirectStandardError "$evidence\console.err.log"
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
    Write-Json @{pid=$server.ProcessId;created=$server.CreationDate.ToUniversalTime().ToString('o');launcherPid=$launcher.Id} "$run\process.json"
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
    $env:DEPOSIT_TEST_PASSWORD=[DepositHarnessSecretStore]::Read($credentialTarget)
    try {
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
    } finally {$ErrorActionPreference='Stop'; Remove-Item Env:\DEPOSIT_TEST_PASSWORD -ErrorAction SilentlyContinue}
}

function Invoke-Stop($Context,[string]$ScriptsDir,[bool]$Force) {$Root=$Context.Root; $repo=$Context.Repo; $run=$Context.Run; $evidence=$Context.Evidence; $manifest=$Context.Manifest; $spatialLock=$Context.SpatialLock; $ocflLock=$Context.OcflLock; $formatLock=$Context.FormatLock; $RunId=$Context.RunId; $Port=$Context.Port; $ReleasePackage=$Context.ReleasePackage; $BoltUri=$Context.BoltUri; $DiagnosticBuild=$Context.DiagnosticBuild; $WithoutProbe=$Context.WithoutProbe; $StartupTimeoutSeconds=$Context.StartupTimeoutSeconds; $installation=$Context.Installation; $credentialTarget=$Context.CredentialTarget; $utf8=$Context.Utf8;
    $owned=Get-OwnedProcess
    if (!$owned) {return}
    $processHandle=Get-Process -Id $owned.ProcessId
    [void]$processHandle.Handle
    try {
    if ($Force) {Stop-Process -Id $owned.ProcessId -Force}
    else {
        $owner=Get-Content (Join-Path $run 'owner.json') -Raw | ConvertFrom-Json
        if($owner.releaseMode){$env:JAVA_HOME=Join-Path $Root $manifest.javaHome;$env:NEO4J_HOME=$installation;$env:NEO4J_CONF=Join-Path $installation 'conf';$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH;$ErrorActionPreference='Continue';& (Join-Path $run 'distribution\deposit.bat') stop *> "$evidence\release-stop.log";$stopCode=$LASTEXITCODE;$ErrorActionPreference='Stop';if($stopCode -ne 0){throw 'Packaged deposit.bat stop failed'} }
        else {
        . (Join-Path $ScriptsDir 'Request-DepositStop.ps1')
        $drain=Request-DepositStop -Neo4jHome $installation -TargetProcessId $owned.ProcessId
        # Windows `neo4j stop` only controls a service, not `neo4j console`.
        $identityBase64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($drain|ConvertTo-Json -Compress)))
        $stopArgs="-NoProfile -File `"$ScriptsDir\Stop-Console.ps1`" -IdentityBase64 $identityBase64"
        $helper=Start-Process powershell.exe -ArgumentList $stopArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput "$evidence\stop.log" -RedirectStandardError "$evidence\stop.err.log"
        [void]$helper.Handle
        try {
            if(!$helper.WaitForExit(10000)) {$helper.Kill(); throw 'Graceful console signal timed out'}
            if ($helper.ExitCode -ne 0) {throw 'Graceful console signal failed'}
        } finally {$helper.Dispose()}
        }
    }
        if (!$processHandle.WaitForExit(60000)) {throw 'Owned server did not stop within 60 seconds after Deposit cleanup'}
    } finally {$processHandle.Dispose()}
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
