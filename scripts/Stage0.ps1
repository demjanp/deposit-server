[CmdletBinding()]
param(
    [ValidateSet('Provision','Build','Package','Install','Start','Stop','Kill','Test','Evidence','Reset','All')]
    [string]$Phase = 'All',
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$')]
    [string]$RunId = 'stage0-host',
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$')][string]$EvidenceGroup,
    [ValidateRange(1024,65535)][int]$Port = 17770,
    [string]$Root = 'C:\data_processed\Deposit_v2\stage0',
    [string]$InputsFile,
    [switch]$DiagnosticBuild,
    [switch]$SkipReadiness,
    [switch]$WithoutProbe,
    [ValidatePattern('^[A-Za-z0-9_#+,*-]+$')][string]$TestSelection = 'RuntimeIT',
    [ValidateSet('seed','verify')][string]$TestMode = 'seed',
    [ValidateSet('incompatible-spatial','missing-spatial','invalid-deposit-config')][string]$ExpectedFailure = 'incompatible-spatial',
    [ValidateRange(5,300)][int]$StartupTimeoutSeconds = 90
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\Credentials.ps1"
. "$PSScriptRoot\Inputs.ps1"
. "$PSScriptRoot\ArtifactIntegrity.ps1"
$repo = Split-Path $PSScriptRoot -Parent
$Root = [IO.Path]::GetFullPath($Root).TrimEnd('\')
if ($Root -ne 'C:\data_processed\Deposit_v2\stage0') {throw 'Stage 0 must use the user-approved root C:\data_processed\Deposit_v2\stage0'}
$manifest = Get-Content "$repo\runtime\artifacts.json" -Raw | ConvertFrom-Json
$spatialLock = Get-Content (Join-Path "$repo\runtime" $manifest.dependencyLock) -Raw | ConvertFrom-Json
$run = Join-Path $Root "runs\$RunId"
$evidence = Join-Path $run ('evidence\'+$Phase+'-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))
if($EvidenceGroup) {$evidence=Join-Path $Root ("evidence\$EvidenceGroup\phases\"+$Phase+'-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))}
if($Phase -in @('Install','Start','Stop','Kill','Test','Reset','All') -and $RunId -ne 'stage0-host') {throw 'Only the single stage0-host Neo4j installation is authorized'}
$installation = Join-Path $run 'neo4j'
$savedEnvironment=@{}
foreach($key in @('JAVA_HOME','MAVEN_USER_HOME','MAVEN_OPTS','NEO4J_HOME','NEO4J_CONF','TEMP','TMP','STAGE0_URI','STAGE0_PASSWORD','STAGE0_NEO4J_VERSION','STAGE0_EXPECTED_FAILURE','STAGE0_EVIDENCE','STAGE0_TEST_MODE','STAGE0_BASELINE_MAP')) {$savedEnvironment[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
$credentialTarget = "Deposit/Stage0/$RunId"
$utf8 = New-Object Text.UTF8Encoding($false)
New-Item -ItemType Directory -Force $evidence | Out-Null
New-Item -ItemType Directory -Force "$run\tmp" | Out-Null
$initialInputs=if($InputsFile) {Get-Content -LiteralPath $InputsFile -Raw | ConvertFrom-Json} else {Get-Stage0Inputs $repo}

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
function Test-Artifacts([switch]$VerifyChecksums) {
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
    Test-Artifacts -VerifyChecksums
    Invoke-ProvisionLicenses
    foreach ($id in @('temurin','neo4j','maven')) {
        $artifact = $manifest.artifacts | Where-Object id -eq $id
        $marker=Join-Path $Root "tools\$id.sha256"
        if (!(Test-Path $marker) -or (Get-Content $marker -Raw).Trim() -ne $artifact.sha256) {
            Expand-Archive -LiteralPath (Join-Path $Root "cache\$($artifact.file)") -DestinationPath "$Root\tools" -Force
            [IO.File]::WriteAllText($marker,$artifact.sha256,$utf8)
        }
        $null=Get-ToolDirectory $manifest $id $Root
    }
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
        & .\mvnw.cmd --batch-mode --no-transfer-progress "-Dmaven.repo.local=$Root\maven-repository" clean verify dependency:tree *> "$evidence\build.log"
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed; see $evidence\build.log" }
        if(Test-Path "$Root\build\deposit-plugin\target\surefire-reports") {Copy-Item -LiteralPath "$Root\build\deposit-plugin\target\surefire-reports" -Destination "$evidence\unit-junit" -Recurse}
        $artifacts=@('build\deposit-plugin\target\deposit-plugin-0.0.0-stage4.jar','build\deposit-runtime-probe\target\deposit-runtime-probe-0.0.0-stage4.jar') | ForEach-Object {@{path=$_;sha256=(Get-FileHash (Join-Path $Root $_) -Algorithm SHA256).Hash.ToLowerInvariant()}}
        Write-Json @{version='0.0.0-stage4';inputs=$initialInputs.sha256;sourceIdentity=$initialInputs.sha256;javaHome=$manifest.javaHome;reproducibleTimestamp='2026-10-08T00:00:00Z';artifacts=@($artifacts)} "$Root\build\deposit-plugin\target\build-metadata.json"
        $snapshot=Save-Stage0SourceSnapshot $repo $Root $initialInputs
        Write-Json $snapshot "$evidence\source-snapshot.json"
        Write-Json @{inputs=$initialInputs.sha256;sourceSnapshot=$snapshot;artifacts=@($artifacts);evidence=$evidence} "$Root\build-receipt.json"
    } finally { $ErrorActionPreference='Stop'; Pop-Location }
}
function Invoke-Install {
    Test-Artifacts
    $runtimeDirectory=Get-ToolDirectory $manifest 'neo4j' $Root
    $null=Get-ToolDirectory $manifest 'temurin' $Root
    if (!$DiagnosticBuild) {
        $receipt=Get-Content "$Root\build-receipt.json" -Raw | ConvertFrom-Json
        if ($receipt.inputs -ne $initialInputs.sha256) {throw 'Build receipt is stale; rebuild current inputs before installation'}
    }
    if (Test-Path "$run\owner.json") { Assert-Run; throw 'Stop and reset stage0-host before reinstalling' }
    Write-Json @{runId=$RunId;root=$Root;port=$Port;diagnostic=[bool]$DiagnosticBuild} "$run\owner.json"
    Copy-Item -LiteralPath $runtimeDirectory -Destination $installation -Recurse
    New-Item -ItemType Directory -Force "$run\resources\staging","$run\resources\live","$run\resources\deleted","$run\exports","$run\secrets" | Out-Null
    Set-OwnerOnlyDirectory "$run\secrets"
    $plugin = "$Root\build\deposit-plugin\target\deposit-plugin-0.0.0-stage4.jar"
    $probe = "$Root\build\deposit-runtime-probe\target\deposit-runtime-probe-0.0.0-stage4.jar"
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
    Write-Json @{inputs=$(if($DiagnosticBuild){$null}else{$receipt.inputs});diagnostic=[bool]$DiagnosticBuild;runtime=@(@{path='bin\neo4j.bat';sha256=(Get-FileHash (Join-Path $runtimeDirectory 'bin\neo4j.bat') -Algorithm SHA256).Hash.ToLowerInvariant()});plugins=@(Get-ChildItem "$installation\plugins" -Filter *.jar | ForEach-Object {@{name=$_.Name;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})} "$run\installed-artifacts.json"
    $configFile = (Join-Path $installation 'conf\deposit.properties').Replace('\','/')
    $resources = (Join-Path $run 'resources').Replace('\','/')
    $permissions='dbms.security.procedures.allowlist=deposit.*,apoc.version'
    if(!$WithoutProbe) {$permissions="dbms.security.procedures.allowlist=deposit.*,stage0.geometry.*,stage0.security.*,stage0.storage.*,apoc.version`ndbms.security.procedures.unrestricted=stage0.geometry.*,stage0.security.*,stage0.storage.*"}
    [IO.File]::WriteAllText($configFile, "resourceRoot=$resources`nexportRoot=$($run.Replace('\','/'))/exports`n", $utf8)
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
    $installed=Get-Content "$run\installed-artifacts.json" -Raw | ConvertFrom-Json
    if(!$installed.PSObject.Properties['runtime']) {throw 'Installation lacks pinned runtime inventory; stop and archive/reset stage0-host'}
    $launcherArtifact=@($installed.runtime | Where-Object path -eq 'bin\neo4j.bat')
    if($launcherArtifact.Count -ne 1 -or (Get-FileHash "$installation\bin\neo4j.bat" -Algorithm SHA256).Hash -ne $launcherArtifact[0].sha256) {throw 'Runtime file differs from pinned archive: Neo4j launcher'}
    $null=Get-ToolDirectory $manifest 'temurin' $Root
    $actualNames=@(Get-ChildItem "$installation\plugins" -Filter *.jar | ForEach-Object Name | Sort-Object)
    $expectedNames=@($installed.plugins | ForEach-Object name | Sort-Object)
    if (Compare-Object $actualNames $expectedNames) {throw 'Installed plugin set differs from the recorded manifest'}
    foreach ($artifact in $installed.plugins) {
        if ((Get-FileHash (Join-Path "$installation\plugins" $artifact.name) -Algorithm SHA256).Hash -ne $artifact.sha256) {throw 'Installed plugin changed; stop and archive/reset stage0-host'}
    }
    if (Get-OwnedProcess) { throw 'Owned server is already running' }
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
                if (!$SkipReadiness) {Invoke-Readiness $deadline}
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
        $arguments="-Djava.io.tmpdir=$env:TEMP -cp `"$Root\build\deposit-integration-tests\target\classes;$Root\build\deposit-integration-tests\target\dependency\*`" org.deposit.it.ReadinessClient bolt://127.0.0.1:$Port"
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
        # Windows `neo4j stop` only controls a service, not `neo4j console`.
        $args="-NoProfile -File `"$PSScriptRoot\Stop-Console.ps1`" -TargetProcessId $($owned.ProcessId)"
        $helper=Start-Process powershell.exe -ArgumentList $args -WindowStyle Hidden -PassThru -RedirectStandardOutput "$evidence\stop.log" -RedirectStandardError "$evidence\stop.err.log"
        [void]$helper.Handle
        try {
            if(!$helper.WaitForExit(10000)) {$helper.Kill(); throw 'Graceful console signal timed out'}
            if ($helper.ExitCode -ne 0) {throw 'Graceful console signal failed'}
        } finally {$helper.Dispose()}
    }
        if (!$processHandle.WaitForExit(30000)) {throw 'Owned server did not stop within 30 seconds'}
    } finally {$processHandle.Dispose()}
}
function Invoke-Test {
    Assert-Run
    $env:STAGE0_URI="bolt://127.0.0.1:$Port"
    $env:STAGE0_PASSWORD=[DepositCredentialStore]::Read($credentialTarget)
    $env:STAGE0_EVIDENCE=$evidence
    $env:STAGE0_TEST_MODE=$TestMode
    $env:STAGE0_BASELINE_MAP=Join-Path $run 'fixture-uuid-baseline.tsv'
    $env:STAGE0_EXPECTED_FAILURE=$ExpectedFailure
    $env:STAGE0_NEO4J_VERSION=$manifest.neo4jVersion
    try {
        Push-Location $repo
        $ErrorActionPreference='Continue'
        & .\mvnw.cmd --batch-mode --no-transfer-progress "-Dmaven.repo.local=$Root\maven-repository" -pl integration-tests -Pintegration "-Dtest=$TestSelection" "-Dstage0.reports=$evidence\junit" test *> "$evidence\tests.log"
        if ($LASTEXITCODE -ne 0) {throw 'Integration tests failed'}
        $reports=@(Get-ChildItem "$evidence\junit" -Filter 'TEST-*.xml')
        if(!$reports.Count) {throw 'Integration test reports are missing'}
        foreach($report in $reports) {
            [xml]$xml=Get-Content -LiteralPath $report.FullName -Raw
            if([int]$xml.testsuite.tests -eq 0 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0) {throw 'Integration reports include missing, skipped or failing scenarios'}
        }
    } finally {
        $ErrorActionPreference='Stop'; Pop-Location
        Remove-Item Env:\STAGE0_PASSWORD -ErrorAction SilentlyContinue
    }
}
function Invoke-Reset {
    Assert-Run
    if(!$EvidenceGroup) {throw 'Reset requires an external EvidenceGroup to preserve evidence'}
    if(Get-OwnedProcess) {throw 'Stop the owned Neo4j process before resetting its installation'}
    if(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains($Root) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'}) {throw 'A Stage 0 Neo4j process is still running'}
    $resolved=[IO.Path]::GetFullPath($run).TrimEnd('\')
    if($resolved -ne (Join-Path $Root 'runs\stage0-host')) {throw 'Reset target is not the authorized installation'}
    if(@(Get-Item -LiteralPath $Root,(Join-Path $Root 'runs'),$run | Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count -or
       @(Get-ChildItem -LiteralPath $run -Recurse -Force | Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count) {throw 'Reset refuses reparse points'}
    if(@(Get-ChildItem "$run\secrets" -File -Recurse).Count) {throw 'Reset refuses temporary secret files'}
    # Archive stopped state before removing any file. Archives are evidence, not another executable installation.
    $archive=Join-Path $evidence 'previous-installation.zip'
    $zip=[IO.Compression.ZipFile]::Open($archive,'Create')
    try {
        foreach($file in (Get-ChildItem -LiteralPath $run -File -Recurse -Force | Where-Object Name -ne 'phase.lock')) {
            $relative=$file.FullName.Substring($run.Length+1).Replace('\','/')
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$relative,'Fastest') | Out-Null
        }
    } finally {$zip.Dispose()}
    Write-Json @{path=$archive;runId=$RunId;inputs=$initialInputs.sha256} "$evidence\reset-archive.json"
    foreach($item in (Get-ChildItem -LiteralPath $run -Force | Where-Object Name -ne 'phase.lock')) {
        $target=[IO.Path]::GetFullPath($item.FullName)
        if(!$target.StartsWith($resolved+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'Reset target escaped the owned run'}
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    New-Item -ItemType Directory -Force "$run\tmp" | Out-Null
}
function Invoke-Evidence {
    if (Test-Path "$installation\logs") {Copy-Item "$installation\logs" "$evidence\server-logs" -Recurse -Force}
    Write-Json $manifest "$evidence\artifact-manifest.json"
    Write-Json $spatialLock "$evidence\spatial-dependencies.json"
    $os=Get-CimInstance Win32_OperatingSystem
    Write-Json @{os=$os.Caption;version=$os.Version;javaHome=$env:JAVA_HOME;runId=$RunId;diagnostic=[bool]$DiagnosticBuild} "$evidence\environment.json"
    $initialInputs.serverBaseline | Set-Content "$evidence\server-commit.txt"
    $initialInputs.hubBaseline | Set-Content "$evidence\hub-commit.txt"
    Write-Json @{sha256=$initialInputs.sha256;sourceInputs=$InputsFile;serverBaseline=$initialInputs.serverBaseline;hubBaseline=$initialInputs.hubBaseline} "$evidence\inputs.json"
    if (Test-Path "$run\installed-artifacts.json") {Copy-Item "$run\installed-artifacts.json" $evidence}
    foreach($name in @('owner.json','process.json','fixture-uuid-baseline.tsv','fixture-uuid-baseline.tsv.geometry')) {
        if(Test-Path -LiteralPath (Join-Path $run $name)) {Copy-Item -LiteralPath (Join-Path $run $name) -Destination $evidence}
    }
}
function Invoke-Package {
    Test-Artifacts
    Invoke-ProvisionLicenses
    $receipt=Get-Content "$Root\build-receipt.json" -Raw | ConvertFrom-Json
    if($receipt.inputs -ne $initialInputs.sha256) {throw 'Build receipt is stale; rebuild before packaging'}
    $plugin=Join-Path $Root 'build\deposit-plugin\target\deposit-plugin-0.0.0-stage4.jar'
    $expected=@($receipt.artifacts | Where-Object path -like 'build\deposit-plugin\*')
    if($expected.Count -ne 1 -or (Get-FileHash $plugin -Algorithm SHA256).Hash -ne $expected[0].sha256) {throw 'Plugin changed since build'}
    $jar=[IO.Compression.ZipFile]::OpenRead($plugin)
    try {if(@($jar.Entries | Where-Object {$_.FullName.EndsWith('.class') -and !$_.FullName.StartsWith('org/deposit/runtime/') -and !$_.FullName.StartsWith('deposit/')}).Count) {throw 'Normal plugin contains foreign/test classes'}} finally {$jar.Dispose()}
    $destination=Join-Path $run 'distribution'
    if(Test-Path -LiteralPath $destination) {throw 'Distribution already exists; choose a fresh RunId'}
    New-Item -ItemType Directory -Force "$destination\plugins","$destination\conf","$destination\notices" | Out-Null
    Copy-Item -LiteralPath $plugin -Destination "$destination\plugins"
    foreach($id in @('apoc','spatial','bcrypt-provider')) {$artifact=$manifest.artifacts | Where-Object id -eq $id; Copy-Item -LiteralPath (Join-Path $Root "cache\$($artifact.file)") -Destination "$destination\plugins"}
    foreach($artifact in @($spatialLock.dependencies | Where-Object ship)) {Copy-Item -LiteralPath (Join-Path $Root "cache\spatial-dependencies\$($artifact.file)") -Destination "$destination\plugins"}
    & "$PSScriptRoot\Audit-Artifacts.ps1" -Root $Root -Inputs $initialInputs.sha256
    Copy-Item -LiteralPath "$Root\evidence\artifact-audit\inventory.json" -Destination "$destination\runtime-inventory.json"
    Copy-Item -LiteralPath (Join-Path $Root "$($manifest.neo4jHome)\ThirdPartyLicenses.txt") -Destination "$destination\notices\Neo4j-ThirdPartyLicenses.txt"
    Copy-Item -Path "$Root\evidence\artifact-audit\notices\*" -Destination "$destination\notices"
    Copy-Item -Path "$Root\cache\licenses\*" -Destination "$destination\notices"
    Copy-Item -LiteralPath "$repo\LICENSE","$repo\runtime\artifacts.json","$repo\runtime\spatial-runtime-lock.json","$Root\build\deposit-plugin\target\build-metadata.json" -Destination $destination
    Copy-Item -Path "$repo\runtime\distribution\*" -Destination "$destination\conf"
    Write-Json @{inputs=$initialInputs.sha256;plugins=@(Get-ChildItem "$destination\plugins" -Filter *.jar | ForEach-Object {@{name=$_.Name;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})} "$destination\distribution-manifest.json"
    [IO.Compression.ZipFile]::CreateFromDirectory($destination,"$run\deposit-server-0.0.0-stage4.zip")
    Write-Json @{path="$run\deposit-server-0.0.0-stage4.zip";sha256=(Get-FileHash "$run\deposit-server-0.0.0-stage4.zip" -Algorithm SHA256).Hash.ToLowerInvariant();inputs=$initialInputs.sha256} "$evidence\distribution.json"
}

$status='running'
$failure=$null
$evidenceFailure=$null
$phaseLock=[IO.File]::Open((Join-Path $run 'phase.lock'),'OpenOrCreate','ReadWrite','None')
try {
    $env:JAVA_HOME = Join-Path $Root $manifest.javaHome
    $env:MAVEN_USER_HOME = Join-Path $Root 'maven-user'
    $env:TEMP="$run\tmp"; $env:TMP=$env:TEMP
    $env:MAVEN_OPTS=($env:MAVEN_OPTS+' -Djava.io.tmpdir='+$env:TEMP).Trim()
    Write-Json @{runId=$RunId;phase=$Phase;status=$status;inputs=$initialInputs.sha256;evidence=$evidence;updated=[DateTime]::UtcNow.ToString('o');nextAction='Interrupted phase: inspect owned process and artifacts, then rerun this phase'} "$run\checkpoint.json"
    switch ($Phase) {
        Provision {Invoke-Provision}
        Build {Invoke-Build}
        Package {Invoke-Package}
        Install {Invoke-Install}
        Start {Invoke-Start}
        Stop {Invoke-Stop $false}
        Kill {Invoke-Stop $true}
        Test {Invoke-Test}
        Evidence {Invoke-Evidence}
        Reset {Invoke-Reset}
        All {Invoke-Provision; Invoke-Build; Invoke-Install; Invoke-Start; Invoke-Test; Invoke-Stop $false; $TestMode='verify'; Invoke-Start; Invoke-Test; Invoke-Stop $true; Invoke-Start; Invoke-Test; Invoke-Stop $false}
    }
    $status='passed'
} catch {
    $status='failed'; $failure=$_.Exception.Message
    throw
} finally {
    try {
        try {Invoke-Evidence} catch {$evidenceFailure=$_.Exception.Message; $status='failed'}
        $checkpoint=@{runId=$RunId;port=$Port;phase=$Phase;status=$status;failure=$failure;evidenceFailure=$evidenceFailure;diagnostic=[bool]$DiagnosticBuild;readinessBypassed=[bool]$SkipReadiness;inputs=$initialInputs.sha256;evidence=$evidence;testSelection=$TestSelection;testMode=$TestMode;expectedFailure=$ExpectedFailure;withoutProbe=[bool]$WithoutProbe;updated=[DateTime]::UtcNow.ToString('o');nextAction='Review scenario evidence; a phase result alone never establishes Stage 0 acceptance'}
        try {Write-Json $checkpoint "$evidence\checkpoint-$Phase.json"}
        finally {Write-Json $checkpoint "$run\checkpoint.json"}
    } finally {
        $phaseLock.Dispose()
        foreach($key in $savedEnvironment.Keys) {[Environment]::SetEnvironmentVariable($key,$savedEnvironment[$key],'Process')}
    }
    if($evidenceFailure) {throw "Evidence collection failed: $evidenceFailure"}
}
