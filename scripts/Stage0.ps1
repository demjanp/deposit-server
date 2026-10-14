[CmdletBinding()]
param(
    [ValidateSet('Provision','Build','Package','Install','Start','Stop','Kill','Reset')]
    [string]$Phase = 'Build',
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$')]
    [string]$RunId = 'stage0-host',
    [ValidateRange(1024,65535)][int]$Port = 17770,
    [string]$Root = 'C:\data_processed\Deposit_v2\stage0',
    [string]$ReleasePackage,
    [string]$BoltUri,
    [switch]$DiagnosticBuild,
    [switch]$WithoutProbe,
    [ValidateRange(5,300)][int]$StartupTimeoutSeconds = 90
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\Credentials.ps1"
. "$PSScriptRoot\ArtifactIntegrity.ps1"
$repo = Split-Path $PSScriptRoot -Parent
$Root = [IO.Path]::GetFullPath($Root).TrimEnd('\')
if ($Root -ne 'C:\data_processed\Deposit_v2\stage0') {throw 'Stage 0 must use the user-approved root C:\data_processed\Deposit_v2\stage0'}
$manifest = Get-Content "$repo\runtime\artifacts.json" -Raw | ConvertFrom-Json
$spatialLock = Get-Content (Join-Path "$repo\runtime" $manifest.dependencyLock) -Raw | ConvertFrom-Json
$ocflLock = Get-Content (Join-Path "$repo\runtime" $manifest.ocflDependencyLock) -Raw | ConvertFrom-Json
$formatLock = Get-Content (Join-Path "$repo\runtime" $manifest.formatDependencyLock) -Raw | ConvertFrom-Json
$run = Join-Path $Root "runs\$RunId"
$evidence = Join-Path $run ('logs\'+$Phase+'-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))
if($Phase -in @('Install','Start','Stop','Kill','Reset') -and $RunId -ne 'stage0-host') {throw 'Only the single stage0-host Neo4j installation is authorized'}
$installation = Join-Path $run 'neo4j'
$savedEnvironment=@{}
foreach($key in @('JAVA_HOME','MAVEN_USER_HOME','MAVEN_OPTS','NEO4J_HOME','NEO4J_CONF','TEMP','TMP')) {$savedEnvironment[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
$credentialTarget = "Deposit/Stage0/$RunId"
$utf8 = New-Object Text.UTF8Encoding($false)

function Write-Json($Value, [string]$Path) {
    $temporary=$Path+'.'+[Guid]::NewGuid().ToString('N')+'.partial'
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 15), $utf8)
    if(Test-Path -LiteralPath $Path) {[IO.File]::Replace($temporary,$Path,$Path+'.previous')} else {[IO.File]::Move($temporary,$Path)}
}
function Assert-Run {
    $marker = Join-Path $run 'owner.json'
    if (!(Test-Path $marker)) { throw 'Installation has no Stage 0 ownership marker' }
    $owner = Get-Content $marker -Raw | ConvertFrom-Json
    if ($owner.runId -ne $RunId -or $owner.root -ne $Root -or $owner.port -ne $Port) { throw 'Installation identity mismatch' }
    $resolved = [IO.Path]::GetFullPath($installation)
    if (!$resolved.StartsWith($Root+'\runs\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Installation escaped Stage 0 root' }
}
function Test-Artifacts([switch]$VerifyChecksums,[switch]$SkipProvided) {
    foreach ($artifact in $manifest.artifacts) {
        $file = Join-Path $Root "cache\$($artifact.file)"
        if (!(Test-Path $file) -or ($VerifyChecksums -and (Get-FileHash $file -Algorithm SHA256).Hash -ne $artifact.sha256)) {
            throw "Artifact missing or checksum mismatch: $($artifact.id)"
        }
    }
    if($VerifyChecksums -and (Get-FileHash "$repo\runtime\spatial-dependencies.pom.xml" -Algorithm SHA256).Hash -ne $spatialLock.sourcePomSha256) {throw 'Spatial dependency lock does not match its source POM'}
    foreach($artifact in @($spatialLock.dependencies | Where-Object ship)) {
        $file=Join-Path $Root "cache\spatial-dependencies\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file) -or ($VerifyChecksums -and (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $artifact.sha256)) {throw "Spatial dependency missing or checksum mismatch: $($artifact.file)"}
    }
    if($VerifyChecksums -and (Get-FileHash "$repo\runtime\ocfl-dependencies.pom.xml" -Algorithm SHA256).Hash -ne $ocflLock.sourcePomSha256) {throw 'OCFL dependency lock does not match its source POM'}
    foreach($artifact in @($ocflLock.dependencies | Where-Object ship)) {
        $file=Join-Path $Root "cache\ocfl-dependencies\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file) -or ($VerifyChecksums -and (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $artifact.sha256)) {throw "OCFL dependency missing or checksum mismatch: $($artifact.file)"}
    }
    foreach($artifact in @($ocflLock.dependencies | Where-Object {-not $_.ship -and -not $SkipProvided})) {
        $file=Join-Path $Root "$($manifest.neo4jHome)\lib\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $artifact.sha256){throw "Provided OCFL dependency differs from the pinned runtime: $($artifact.file)"}
    }
    if($VerifyChecksums -and (Get-FileHash "$repo\runtime\format-dependencies.pom.xml" -Algorithm SHA256).Hash -ne $formatLock.sourcePomSha256) {throw 'Format dependency lock does not match its source POM'}
    foreach($artifact in @($formatLock.dependencies | Where-Object ship)) {
        $file=Join-Path $Root "cache\format-dependencies\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file) -or ($VerifyChecksums -and (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $artifact.sha256)) {throw "Format dependency missing or checksum mismatch: $($artifact.file)"}
    }
    foreach($artifact in @($formatLock.dependencies | Where-Object {-not $_.ship -and -not $SkipProvided})) {
        $file=Join-Path $Root "$($manifest.neo4jHome)\lib\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $artifact.sha256){throw "Provided format dependency differs from the pinned runtime: $($artifact.file)"}
    }
}
function Invoke-Provision {
    New-Item -ItemType Directory -Force "$Root\cache","$Root\tools" | Out-Null
    foreach ($artifact in $manifest.artifacts) {
        $file = Join-Path $Root "cache\$($artifact.file)"
        if (!(Test-Path $file)) {
            Invoke-WebRequest -UseBasicParsing -Uri $artifact.url -OutFile "$file.partial"
            if ((Get-FileHash "$file.partial" -Algorithm SHA256).Hash -ne $artifact.sha256) { throw "Download hash mismatch: $($artifact.id)" }
            Move-Item -LiteralPath "$file.partial" -Destination $file
        }
    }
    New-Item -ItemType Directory -Force "$Root\cache\spatial-dependencies" | Out-Null
    foreach($artifact in @($spatialLock.dependencies | Where-Object ship)) {
        $file=Join-Path $Root "cache\spatial-dependencies\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file)) {
            Invoke-WebRequest -UseBasicParsing -Uri $artifact.url -OutFile "$file.partial"
            if((Get-FileHash -LiteralPath "$file.partial" -Algorithm SHA256).Hash -ne $artifact.sha256) {throw "Spatial dependency download hash mismatch: $($artifact.file)"}
            Move-Item -LiteralPath "$file.partial" -Destination $file
        }
    }

    New-Item -ItemType Directory -Force "$Root\cache\ocfl-dependencies" | Out-Null
    foreach($artifact in @($ocflLock.dependencies | Where-Object ship)) {
        $file=Join-Path $Root "cache\ocfl-dependencies\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file)) {
            Invoke-WebRequest -UseBasicParsing -Uri $artifact.url -OutFile "$file.partial"
            if((Get-FileHash -LiteralPath "$file.partial" -Algorithm SHA256).Hash -ne $artifact.sha256) {throw "OCFL dependency download hash mismatch: $($artifact.file)"}
            Move-Item -LiteralPath "$file.partial" -Destination $file
        }
    }
    New-Item -ItemType Directory -Force "$Root\cache\format-dependencies" | Out-Null
    foreach($artifact in @($formatLock.dependencies | Where-Object ship)) {
        $file=Join-Path $Root "cache\format-dependencies\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file)) {
            Invoke-WebRequest -UseBasicParsing -Uri $artifact.url -OutFile "$file.partial"
            if((Get-FileHash -LiteralPath "$file.partial" -Algorithm SHA256).Hash -ne $artifact.sha256) {throw "Format dependency download hash mismatch: $($artifact.file)"}
            Move-Item -LiteralPath "$file.partial" -Destination $file
        }
    }
    Test-Artifacts -VerifyChecksums -SkipProvided
    Invoke-ProvisionLicenses
    foreach ($id in @('temurin','neo4j','maven')) {
        $artifact = $manifest.artifacts | Where-Object id -eq $id
        $relative=switch($id) {
            'temurin' {$manifest.javaHome}
            'neo4j' {$manifest.neo4jHome}
            'maven' {'tools/'+(($artifact.file -replace '-bin\.zip$',''))}
        }
        if (!(Test-Path -LiteralPath (Join-Path $Root $relative) -PathType Container)) {
            Expand-Archive -LiteralPath (Join-Path $Root "cache\$($artifact.file)") -DestinationPath "$Root\tools" -Force
        }
        $null=Get-ToolDirectory $manifest $id $Root
    }
    Test-Artifacts -VerifyChecksums
}
function Invoke-ProvisionLicenses {
    $licenses=Get-Content "$repo\runtime\license-overrides.json" -Raw | ConvertFrom-Json
    foreach($property in $licenses.PSObject.Properties) {
        $license=$property.Value
        $file=Join-Path "$Root\cache" $license.file
        if(!(Test-Path -LiteralPath $file)) {
            New-Item -ItemType Directory -Force (Split-Path $file -Parent) | Out-Null
            Invoke-WebRequest -UseBasicParsing -Uri $license.url -OutFile $file
            if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $license.sha256) {throw 'License download checksum mismatch'}
        }
    }
}
function Invoke-Build {
    Test-Artifacts
    $null=Get-ToolDirectory $manifest 'temurin' $Root
    Push-Location $repo
    try {
        $ErrorActionPreference='Continue'
        & .\mvnw.cmd --batch-mode --no-transfer-progress "-Dmaven.repo.local=$Root\maven-repository" clean package -DskipTests *> "$evidence\build.log"
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed; see $evidence\build.log" }
    } finally { $ErrorActionPreference='Stop'; Pop-Location }
}
function Invoke-Install {
    Test-Artifacts
    $runtimeDirectory=Get-ToolDirectory $manifest 'neo4j' $Root
    $null=Get-ToolDirectory $manifest 'temurin' $Root
    if (!$DiagnosticBuild -and !(Test-Path -LiteralPath "$Root\build\deposit-plugin\target\deposit-plugin-0.0.0-stage65.jar" -PathType Leaf)) {throw 'Build the plugin before installation'}
    if (Test-Path "$run\owner.json") { Assert-Run; throw 'Stop and reset stage0-host before reinstalling' }
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
    } else {
    $plugin = "$Root\build\deposit-plugin\target\deposit-plugin-0.0.0-stage65.jar"
    $probe = "$Root\build\deposit-runtime-probe\target\deposit-runtime-probe-0.0.0-stage65.jar"
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
    if(!$WithoutProbe) {$permissions="dbms.security.procedures.allowlist=deposit.*,stage0.geometry.*,stage0.security.*,stage0.storage.*,apoc.version`ndbms.security.procedures.unrestricted=stage0.geometry.*,stage0.security.*,stage0.storage.*"}
    & "$PSScriptRoot\Configure-DepositDownloads.ps1" -ConfigPath $configFile -ResourceRoot $resources -ExportRoot (Join-Path $run 'exports') -SecretRoot $secretRoot | Out-Null
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
    [DepositCredentialStore]::Save($credentialTarget, $password)
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
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($identity.pid)"
    if (!$process -or !$process.CreationDate -or !$process.CommandLine) { return $null }
    if ($process.CreationDate.ToUniversalTime().ToString('o') -ne $identity.created -or !$process.CommandLine.Contains($installation)) {
        throw 'PID no longer identifies the owned Neo4j process'
    }
    return $process
}
function Test-LauncherAncestry($Process, $Launcher) {
    $current=$Process
    for($depth=0;$depth -lt 12;$depth++) {
        if (!$current -or !$current.CreationDate) {return $false}
        if ($current.ProcessId -eq $Launcher.Id) {return [Math]::Abs(($current.CreationDate-$Launcher.StartTime).TotalSeconds) -lt 1}
        if ($current.CreationDate -lt $Launcher.StartTime.AddSeconds(-1)) {return $false}
        $current=Get-CimInstance Win32_Process -Filter "ProcessId=$($current.ParentProcessId)"
    }
    return $false
}
function Invoke-Start {
    Assert-Run
    if(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains($Root) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'}) {throw 'A Stage 0 Neo4j process is already running; only one instance is authorized'}
    if(!(Test-Path -LiteralPath "$installation\bin\neo4j.bat" -PathType Leaf)) {throw 'Installed Neo4j launcher is missing'}
    $null=Get-ToolDirectory $manifest 'temurin' $Root
    $actualNames=@(Get-ChildItem "$installation\plugins" -Filter *.jar | ForEach-Object Name | Sort-Object)
    if(!$actualNames.Count) {throw 'The installed server has no plugin JARs'}
    if (Get-OwnedProcess) { throw 'Owned server is already running' }
    $owner=Get-Content (Join-Path $run 'owner.json') -Raw | ConvertFrom-Json
    if($owner.releaseMode){
        $archive=Join-Path $run 'distribution\deposit.bat'
        $env:JAVA_HOME=Join-Path $Root $manifest.javaHome;$env:NEO4J_HOME=$installation;$env:NEO4J_CONF=Join-Path $installation 'conf';$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
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
    $env:STAGE0_PASSWORD=[DepositCredentialStore]::Read($credentialTarget)
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
    } finally {$ErrorActionPreference='Stop'; Remove-Item Env:\STAGE0_PASSWORD -ErrorAction SilentlyContinue}
}
function Invoke-Stop([bool]$Force) {
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
        . (Join-Path $PSScriptRoot 'Request-DepositStop.ps1')
        $drain=Request-DepositStop -Neo4jHome $installation -TargetProcessId $owned.ProcessId
        # Windows `neo4j stop` only controls a service, not `neo4j console`.
        $identityBase64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($drain|ConvertTo-Json -Compress)))
        $stopArgs="-NoProfile -File `"$PSScriptRoot\Stop-Console.ps1`" -IdentityBase64 $identityBase64"
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
function Invoke-Reset {
    Assert-Run
    if(Get-OwnedProcess) {throw 'Stop the owned Neo4j process before resetting its installation'}
    if(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains($Root) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'}) {throw 'A Stage 0 Neo4j process is still running'}
    $resolved=[IO.Path]::GetFullPath($run).TrimEnd('\')
    if($resolved -ne (Join-Path $Root 'runs\stage0-host')) {throw 'Reset target is not the authorized installation'}
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
function New-DeterministicZip([string]$SourceDirectory,[string]$Destination) {
    Add-Type -AssemblyName System.IO.Compression
    $archive=[IO.Compression.ZipFile]::Open($Destination,[IO.Compression.ZipArchiveMode]::Create)
    try {
        $base=[IO.Path]::GetFullPath($SourceDirectory).TrimEnd('\')+'\'
        foreach($file in (Get-ChildItem -LiteralPath $SourceDirectory -File -Recurse | Sort-Object { $_.FullName.Substring($base.Length).Replace('\','/') })) {
            $relative=$file.FullName.Substring($base.Length).Replace('\','/')
            $entry=$archive.CreateEntry($relative,[IO.Compression.CompressionLevel]::Optimal)
            $entry.LastWriteTime=[DateTimeOffset]'2026-10-08T00:00:00Z'
            $input=[IO.File]::OpenRead($file.FullName);$output=$entry.Open()
            try {$input.CopyTo($output)} finally {$output.Dispose();$input.Dispose()}
        }
    } finally {$archive.Dispose()}
}
function Invoke-Package {
    Test-Artifacts
    Invoke-ProvisionLicenses
    $plugin=Join-Path $Root 'build\deposit-plugin\target\deposit-plugin-0.0.0-stage65.jar'
    if(!(Test-Path -LiteralPath $plugin -PathType Leaf)) {throw 'Build the plugin before packaging'}
    $jar=[IO.Compression.ZipFile]::OpenRead($plugin)
    try {if(@($jar.Entries | Where-Object {$_.FullName.EndsWith('.class') -and !$_.FullName.StartsWith('org/deposit/runtime/') -and !$_.FullName.StartsWith('deposit/')}).Count) {throw 'Normal plugin contains foreign/test classes'}} finally {$jar.Dispose()}
    $destination=Join-Path $Root 'build\distribution'
    if(Test-Path -LiteralPath $destination) {throw 'Distribution already exists; choose a fresh RunId'}
    New-Item -ItemType Directory -Force "$destination\plugins","$destination\conf","$destination\notices" | Out-Null
    Copy-Item -LiteralPath $plugin -Destination "$destination\plugins"
    foreach($id in @('apoc','spatial','bcrypt-provider')) {$artifact=$manifest.artifacts | Where-Object id -eq $id; Copy-Item -LiteralPath (Join-Path $Root "cache\$($artifact.file)") -Destination "$destination\plugins"}
    foreach($artifact in @($spatialLock.dependencies | Where-Object ship)) {Copy-Item -LiteralPath (Join-Path $Root "cache\spatial-dependencies\$($artifact.file)") -Destination "$destination\plugins"}
    foreach($artifact in @($ocflLock.dependencies | Where-Object ship)) {Copy-Item -LiteralPath (Join-Path $Root "cache\ocfl-dependencies\$($artifact.file)") -Destination "$destination\plugins"}
    foreach($artifact in @($formatLock.dependencies | Where-Object ship)) {Copy-Item -LiteralPath (Join-Path $Root "cache\format-dependencies\$($artifact.file)") -Destination "$destination\plugins"}
    Copy-Item -LiteralPath (Join-Path $Root "$($manifest.neo4jHome)\ThirdPartyLicenses.txt") -Destination "$destination\notices\Neo4j-ThirdPartyLicenses.txt"
    Copy-Item -Path "$Root\cache\licenses\*" -Destination "$destination\notices"
    Copy-Item -LiteralPath "$repo\LICENSE","$repo\runtime\artifacts.json","$repo\runtime\spatial-runtime-lock.json","$repo\runtime\ocfl-runtime-lock.json","$repo\runtime\format-runtime-lock.json" -Destination $destination
    Copy-Item -Path "$repo\runtime\distribution\neo4j.conf","$repo\runtime\distribution\deposit.properties" -Destination "$destination\conf"
    Copy-Item -LiteralPath "$repo\runtime\distribution\deposit.bat","$repo\runtime\distribution\deposit.ps1" -Destination $destination
    New-Item -ItemType Directory -Force "$destination\helpers" | Out-Null
    Copy-Item -LiteralPath "$PSScriptRoot\Credentials.ps1","$PSScriptRoot\Configure-DepositDownloads.ps1","$PSScriptRoot\Stop-Console.ps1","$PSScriptRoot\Request-DepositStop.ps1","$repo\runtime\distribution\helpers\deposit-worker.ps1" -Destination "$destination\helpers"
    # Keep the hub as the sole source; extract its operations section into the release package.
    $hubServerDoc=Join-Path (Split-Path $repo -Parent) 'deposit-hub\deposit-server.md'
    if(!(Test-Path -LiteralPath $hubServerDoc -PathType Leaf)) {throw 'Deposit Hub server documentation is required to package OPERATIONS.md'}
    $hubServerText=[IO.File]::ReadAllText($hubServerDoc)
    $operationsMatch=[regex]::Match($hubServerText,'(?ms)^## Operations[ \t]*\r?\n(.*?)(?=^## Runtime[ \t]*\r?$)')
    if(!$operationsMatch.Success) {throw 'Windows operations section is missing from Deposit Hub server documentation'}
    $operationsText="# Deposit Server on Windows`r`n`r`n"+$operationsMatch.Groups[1].Value.Trim()+"`r`n"
    [IO.File]::WriteAllText((Join-Path $destination 'OPERATIONS.md'),$operationsText,(New-Object Text.UTF8Encoding($false)))
    $archivePath=Join-Path $Root 'build\deposit-server-0.0.0-stage65.zip'
    New-DeterministicZip $destination $archivePath
    $installedDistribution=Join-Path $run 'distribution'
    if(Test-Path -LiteralPath $installedDistribution) {throw 'Installed distribution already exists; reset before packaging'}
    Copy-Item -LiteralPath $destination -Destination $installedDistribution -Recurse
}

$phaseLock=$null
try {
    New-Item -ItemType Directory -Path $run -Force | Out-Null
    $phaseLock=[IO.File]::Open((Join-Path $run 'phase.lock'),'OpenOrCreate','ReadWrite','None')
    New-Item -ItemType Directory -Force $evidence | Out-Null
    New-Item -ItemType Directory -Force "$run\tmp" | Out-Null
} catch {if($phaseLock){$phaseLock.Dispose()};throw}
try {
    $env:JAVA_HOME = Join-Path $Root $manifest.javaHome
    $env:MAVEN_USER_HOME = Join-Path $Root 'maven-user'
    $env:TEMP="$run\tmp"; $env:TMP=$env:TEMP
    $env:MAVEN_OPTS=($env:MAVEN_OPTS+' -Djava.io.tmpdir='+$env:TEMP).Trim()
    switch ($Phase) {
        Provision {Invoke-Provision}
        Build {Invoke-Build}
        Package {Invoke-Package}
        Install {Invoke-Install}
        Start {Invoke-Start}
        Stop {Invoke-Stop $false}
        Kill {Invoke-Stop $true}
        Reset {Invoke-Reset}
    }
} finally {
    try {$phaseLock.Dispose()}
    finally {foreach($key in $savedEnvironment.Keys) {[Environment]::SetEnvironmentVariable($key,$savedEnvironment[$key],'Process')}}
}
