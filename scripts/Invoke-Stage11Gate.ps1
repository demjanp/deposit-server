[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]{0,21}$')][string]$RunPrefix=('stage11-'+[DateTime]::UtcNow.ToString('yyMMddTHHmmss')),
    [ValidateRange(1024,65534)][int]$Port=17770
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
. "$PSScriptRoot\Inputs.ps1"
$repo=Split-Path $PSScriptRoot -Parent;$root='C:\data_processed\Deposit_v2\stage0'
$runtimeManifest=Get-Content (Join-Path $repo 'runtime\artifacts.json') -Raw|ConvertFrom-Json
$script:javaHome=Join-Path $root $runtimeManifest.javaHome
if(!(Test-Path -LiteralPath (Join-Path $script:javaHome 'bin\java.exe')) -or !(Test-Path -LiteralPath (Join-Path $script:javaHome 'bin\javac.exe'))){throw 'Pinned Stage 0 JDK is incomplete'}
& "$PSScriptRoot\Invoke-Stage10Gate.ps1" -RunPrefix ($RunPrefix+'-stage10') -Port $Port
$stage10=Get-Content "$root\stage10-checkpoint.json" -Raw|ConvertFrom-Json
if($stage10.status -ne 'accepted' -or $stage10.cumulativeIntegrationCases -ne 219){throw 'Accepted in-scope Stage 10 cumulative gate is required'}
$stage10ResultManifestPath=Join-Path $root ("evidence\$($stage10.gate)\result-manifest.json")
if(!(Test-Path -LiteralPath $stage10ResultManifestPath -PathType Leaf)){throw 'Stage 10 acceptance result manifest is missing'}
$stage10ResultManifest=Get-Content -LiteralPath $stage10ResultManifestPath -Raw|ConvertFrom-Json
if($stage10ResultManifest.resultId -ne $stage10.gate -or $stage10ResultManifest.lifecycle -ne 'finished' -or $stage10ResultManifest.outcome -ne 'accepted'){throw 'Stage 10 gate finalization is incomplete'}
$cleanupBarrier=Enter-Stage0CleanupBarrier $root;$gateLock=$null;$ownsResult=$false;$evidence=Join-Path $root "evidence\$RunPrefix";$packageFile=$null
$script:run=$null;$script:inputs=$null;$script:serviceLauncher=$null;$script:serviceName=$null;$script:serviceWasInstalled=$false;$script:oldJavaToolOptions=$null
$inputsFile=$null;$originalDepositConfig=$null;$originalNeo4jConfig=$null;$configPath=$null;$neo4jConfigPath=$null;$boltCertificateDirectory=$null
try {
    $gateLock=[IO.File]::Open((Join-Path $root 'gate.lock'),'OpenOrCreate','ReadWrite','None')
    if(Test-Path -LiteralPath $evidence){throw 'Use a fresh Stage 11 run prefix'}
    Start-Stage0Result $root $evidence $RunPrefix 'gate' $null|Out-Null;$ownsResult=$true
    $inputs=Get-Stage0Inputs $repo;$inputsFile=Join-Path $evidence 'source-inputs.json';$inputs|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $inputsFile -Encoding UTF8
    $snapshot=Save-Stage0SourceSnapshot $repo $root $inputs
    $checkpointPath=Join-Path $root 'stage11-checkpoint.json'
    $script:run=@{stage=11;gate=$RunPrefix;rerun='scripts/Invoke-Stage11Gate.ps1 -RunPrefix <fresh-id> -Port '+$Port;status='running';accepted=$false;sourceIdentity=$inputs.sha256;sourceSnapshot=$snapshot;stage10Checkpoint=(Get-FileHash (Join-Path $root 'stage10-checkpoint.json') -Algorithm SHA256).Hash;phases=@();releaseTests=@();excluded=@('A23 operational backup scenarios retired by approved scope amendment','A29 offline operational backup recovery');startedUtc=[DateTime]::UtcNow.ToString('o');acceptance='All in-scope A01-A30 scenarios on the exact release ZIP installed sequentially into the one owned Neo4j installation; no clean-machine or cross-host claim.'}
    $run=$script:run;$script:classes=@{}
    function Save-Checkpoint {
        $script:run.updated=[DateTime]::UtcNow.ToString('o');$temporary=$checkpointPath+'.partial'
        [IO.File]::WriteAllText($temporary,($script:run|ConvertTo-Json -Depth 14),(New-Object Text.UTF8Encoding($false)))
        if(Test-Path -LiteralPath $checkpointPath){[IO.File]::Replace($temporary,$checkpointPath,$checkpointPath+'.previous')}else{[IO.File]::Move($temporary,$checkpointPath)}
        Copy-Item -LiteralPath $checkpointPath -Destination (Join-Path $evidence 'checkpoint.json') -Force
    }
    function Get-ListenerDiagnostics([int[]]$Ports) {
        $items=@()
        foreach($portNumber in @($Ports | Where-Object {$_ -gt 0} | Select-Object -Unique)) {
            try {
                foreach($connection in @(Get-NetTCPConnection -State Listen -LocalPort $portNumber -ErrorAction Stop)) {
                    $owner=$null
                    try {$owner=Get-CimInstance Win32_Process -Filter "ProcessId=$($connection.OwningProcess)" -ErrorAction Stop} catch {}
                    $items+=@{address=$connection.LocalAddress;port=$connection.LocalPort;pid=$connection.OwningProcess;processName=$(if($owner){$owner.Name}else{$null});processCreatedUtc=$(if($owner -and $owner.CreationDate){$owner.CreationDate.ToUniversalTime().ToString('o')}else{$null})}
                }
            } catch {$items+=@{port=$portNumber;diagnosticUnavailable=$_.Exception.Message}}
        }
        return ,$items
    }
    function Phase([string]$Name,[hashtable]$Options=@{}) {
        Save-Checkpoint
        $invocationId=[Guid]::NewGuid().ToString();$invocationStarted=[DateTime]::UtcNow;$invocationError=$null
        try {& "$PSScriptRoot\Stage0.ps1" -Phase $Name -RunId stage0-host -Port $Port -EvidenceGroup $RunPrefix -InputsFile $inputsFile -PhaseInvocationId $invocationId @Options | Out-Null}
        catch {$invocationError=$_.Exception}
        finally {
            $phasePath=Join-Path $root 'runs\stage0-host\checkpoint.json'
            if(!(Test-Path -LiteralPath $phasePath -PathType Leaf)){throw "Stage 0 did not publish a checkpoint for Stage 11 phase $Name"}
            $phase=Get-Content -LiteralPath $phasePath -Raw|ConvertFrom-Json;$phaseUpdated=[DateTime]::MinValue
            if(!$phase.updated -or ![DateTime]::TryParse([string]$phase.updated,[ref]$phaseUpdated) -or $phaseUpdated.ToUniversalTime() -lt $invocationStarted.AddSeconds(-2)){throw "Stage 0 checkpoint is stale for Stage 11 phase $Name"}
            $expectedEvidenceRoot=[IO.Path]::GetFullPath((Join-Path $evidence 'phases')).TrimEnd('\')+'\';$actualEvidence=[IO.Path]::GetFullPath([string]$phase.evidence)
            if($phase.phase -ne $Name -or $phase.phaseInvocationId -ne $invocationId -or $phase.runId -ne 'stage0-host' -or [int]$phase.port -ne $Port -or !$actualEvidence.StartsWith($expectedEvidenceRoot,[StringComparison]::OrdinalIgnoreCase)){throw "Stage 0 checkpoint identity mismatch for Stage 11 phase $Name"}
            $phaseCopy=Join-Path $actualEvidence "checkpoint-$Name.json"
            if(!(Test-Path -LiteralPath $phaseCopy -PathType Leaf)){throw "Stage 0 phase evidence is missing its checkpoint for Stage 11 phase $Name"}
            $copied=Get-Content -LiteralPath $phaseCopy -Raw|ConvertFrom-Json
            if($copied.phaseInvocationId -ne $invocationId -or $copied.phase -ne $Name -or $copied.evidence -ne $phase.evidence){throw "Stage 0 phase evidence checkpoint identity mismatch for Stage 11 phase $Name"}
            if($Name -eq 'Start' -and $phase.status -eq 'failed') {
                $downloadConfigPath=Join-Path $root 'runs\stage0-host\neo4j\conf\deposit.properties';$diagnosticPorts=@($Port)
                if(Test-Path -LiteralPath $downloadConfigPath){$portMatch=[regex]::Match([IO.File]::ReadAllText($downloadConfigPath),'(?m)^download\.port\s*=\s*(\d+)\s*$');if($portMatch.Success){$diagnosticPorts+=@([int]$portMatch.Groups[1].Value)}}
                $phase.listenerDiagnostics=Get-ListenerDiagnostics $diagnosticPorts
            }
            $script:run.phases+=@($phase);Save-Checkpoint
        }
        if($invocationError){throw $invocationError}
        if($phase.phase -ne $Name -or $phase.status -ne 'passed'){throw "Stage 11 release phase failed: $Name"}
        return $phase
    }
    function Collect-TestCases($PhaseResult) {
        $reports=@(Get-ChildItem (Join-Path $PhaseResult.evidence 'junit') -Filter 'TEST-*.xml');if(!$reports.Count){throw "Missing JUnit output for $($PhaseResult.testSelection)"};$found=@()
        foreach($report in $reports){[xml]$xml=Get-Content -LiteralPath $report.FullName -Raw;if([int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped -or [int]$xml.testsuite.tests -ne @($xml.testsuite.testcase).Count){throw "Failed, skipped or incomplete report: $($report.FullName)"};foreach($test in $xml.testsuite.testcase){$found+=@{class=$test.classname;name=$test.name;report=$report.FullName}}}
        if(!$found.Count){throw 'JUnit reports contain no test cases'};return $found
    }
    function Run-Tests([string]$Selection,[hashtable]$Options=@{}) { $phase=Phase Test (@{TestSelection=$Selection}+$Options);$found=Collect-TestCases $phase;$script:run.releaseTests+=@($found);foreach($case in $found){$script:classes[$case.class]=1+([int]$script:classes[$case.class])};return ,$found }
    function Enable-Stage9TestProbe {
        $neo4j=Join-Path $root 'runs\stage0-host\neo4j';$probe=Join-Path $root 'build\deposit-runtime-probe\target\deposit-runtime-probe-0.0.0-stage65.jar';$config=Join-Path $neo4j 'conf\neo4j.conf';$backup=Join-Path $root 'runs\stage0-host\stage11-probe-config.original'
        if(!(Test-Path -LiteralPath $probe -PathType Leaf) -or !(Test-Path -LiteralPath $config -PathType Leaf)){throw 'Stage 9 test-only probe or installed config is missing'}
        if(Test-Path -LiteralPath (Join-Path $neo4j 'plugins\deposit-runtime-probe-0.0.0-stage65.jar')){throw 'Test probe already exists in the release installation'}
        $script:stage9OriginalConfigHash=(Get-FileHash -LiteralPath $config -Algorithm SHA256).Hash
        Copy-Item -LiteralPath $config -Destination $backup
        try {
            Copy-Item -LiteralPath $probe -Destination (Join-Path $neo4j 'plugins\deposit-runtime-probe-0.0.0-stage65.jar')
            $text=[IO.File]::ReadAllText($config);$allow='dbms.security.procedures.allowlist=deposit.*,stage0.geometry.*,stage0.security.*,stage0.storage.*,apoc.version';$unrestricted='dbms.security.procedures.unrestricted=stage0.geometry.*,stage0.security.*,stage0.storage.*'
            if([regex]::IsMatch($text,'(?m)^dbms\.security\.procedures\.allowlist=')){$text=[regex]::Replace($text,'(?m)^dbms\.security\.procedures\.allowlist=.*$',[System.Text.RegularExpressions.MatchEvaluator]{param($m)$allow})}else{$text=$text.TrimEnd()+"`r`n$allow"}
            if([regex]::IsMatch($text,'(?m)^dbms\.security\.procedures\.unrestricted=')){$text=[regex]::Replace($text,'(?m)^dbms\.security\.procedures\.unrestricted=.*$',[System.Text.RegularExpressions.MatchEvaluator]{param($m)$unrestricted})}else{$text=$text.TrimEnd()+"`r`n$unrestricted"}
            [IO.File]::WriteAllText($config,$text,(New-Object Text.UTF8Encoding($false)))
        } catch {Remove-Item -LiteralPath (Join-Path $neo4j 'plugins\deposit-runtime-probe-0.0.0-stage65.jar') -Force -ErrorAction SilentlyContinue;Copy-Item -LiteralPath $backup -Destination $config -Force;Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue;throw}
    }
    function Disable-Stage9TestProbe {
        $neo4j=Join-Path $root 'runs\stage0-host\neo4j';$config=Join-Path $neo4j 'conf\neo4j.conf';$backup=Join-Path $root 'runs\stage0-host\stage11-probe-config.original'
        $processFile=Join-Path $neo4j 'deposit-process.json';$probe=Join-Path $neo4j 'plugins\deposit-runtime-probe-0.0.0-stage65.jar'
        if(Test-Path -LiteralPath $processFile){throw 'Refusing probe cleanup while the launcher still records a running server'}
        $live=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'"|Where-Object {$_.CommandLine -and $_.CommandLine.Contains([IO.Path]::GetFullPath($neo4j)) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'})
        if($live.Count){throw 'Refusing probe cleanup while a Neo4j JVM still uses the probe installation'}
        if(!(Test-Path -LiteralPath $backup -PathType Leaf)){throw 'Probe cleanup lacks the saved original Neo4j configuration'}
        if(Test-Path -LiteralPath $probe){Remove-Item -LiteralPath $probe -Force}
        if(Test-Path -LiteralPath $probe){throw 'Test probe remains in the normal release installation'}
        Copy-Item -LiteralPath $backup -Destination $config -Force
        if((Get-FileHash -LiteralPath $config -Algorithm SHA256).Hash -ne $script:stage9OriginalConfigHash){throw 'Restored Neo4j configuration does not match its pre-probe hash'}
        Remove-Item -LiteralPath $backup -Force
    }
    function Invoke-ReleaseCommand([string]$Arguments,[string]$LogPath) {
        if(!$script:serviceLauncher){throw 'Release launcher path is unavailable'}
        $prior=$ErrorActionPreference
        try {$ErrorActionPreference='Continue';& cmd.exe /d /c "`"$script:serviceLauncher`" $Arguments" *> $LogPath;$exit=$LASTEXITCODE}
        finally {$ErrorActionPreference=$prior}
        return [int]$exit
    }
    function Wait-ReleaseService([string]$Name,[string]$Status,[int]$TimeoutSeconds=90) {
        $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while([DateTime]::UtcNow -lt $deadline){$service=Get-Service -Name $Name -ErrorAction SilentlyContinue;if($service -and $service.Status.ToString() -eq $Status){return $service};Start-Sleep -Milliseconds 500}
        $actual=Get-Service -Name $Name -ErrorAction SilentlyContinue
        throw "Windows service did not reach $Status (actual: $($actual.Status))"
    }
    function Wait-ReleaseServiceStopPending([string]$Name,[int]$TimeoutSeconds=90) {
        $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while([DateTime]::UtcNow -lt $deadline){$service=Get-Service -Name $Name -ErrorAction SilentlyContinue;if($service -and $service.Status -eq 'StopPending'){return $service};if($service -and $service.Status -eq 'Stopped'){throw 'Service stopped before the admitted transaction and download were released'};Start-Sleep -Milliseconds 100}
        $actual=Get-Service -Name $Name -ErrorAction SilentlyContinue
        throw "Windows service did not enter StopPending (actual: $($actual.Status))"
    }
    function Invoke-Stage11GracefulDrainTest([string]$BoltUri) {
        $secrets=Join-Path $root 'runs\stage0-host\secrets';$ready=Join-Path $secrets 'stage11-drain-ready.txt';$release=Join-Path $secrets 'stage11-drain-release.txt'
        Remove-Item -LiteralPath $ready,$release,(Join-Path $secrets 'stage11-drain-fixture.properties') -Force -ErrorAction SilentlyContinue
        $testLog=Join-Path $evidence 'service-drain-test.out.log';$testError=Join-Path $evidence 'service-drain-test.err.log';$stopLog=Join-Path $evidence 'service-drain-stop.out.log';$stopError=Join-Path $evidence 'service-drain-stop.err.log'
        $stage0Script=Join-Path $PSScriptRoot 'Stage0.ps1';$arguments="-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$stage0Script`" -Phase Test -RunId stage0-host -Port $Port -EvidenceGroup $RunPrefix -InputsFile `"$inputsFile`" -TestSelection Stage11ShutdownIT#holdTransactionAndDownloadUntilShutdownSignalsRelease -BoltUri `"$BoltUri`""
        $testProcess=Start-Process powershell.exe -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $testLog -RedirectStandardError $testError
        $stopProcess=$null;$released=$false
        try {
            $readyDeadline=[DateTime]::UtcNow.AddMinutes(4)
            while([DateTime]::UtcNow -lt $readyDeadline -and !(Test-Path -LiteralPath $ready)){$testProcess.Refresh();if($testProcess.HasExited){throw "Stage 11 held transaction test exited before ready (code $($testProcess.ExitCode))"};Start-Sleep -Milliseconds 250}
            if(!(Test-Path -LiteralPath $ready)){throw 'Stage 11 held transaction test did not reach its ready barrier'}
            $stopProcess=Start-Process cmd.exe -ArgumentList @('/d','/c',"`"$script:serviceLauncher`" stop") -WindowStyle Hidden -PassThru -RedirectStandardOutput $stopLog -RedirectStandardError $stopError
            $null=Wait-ReleaseServiceStopPending $script:serviceName
            [IO.File]::WriteAllText($release,"release`n",(New-Object Text.UTF8Encoding($false)));$released=$true
            if(!$testProcess.WaitForExit(180000)){throw 'Stage 11 held transaction test did not finish after the release signal'}
            if($testProcess.ExitCode -ne 0){throw "Stage 11 held transaction test failed with exit code $($testProcess.ExitCode)"}
            if(!$stopProcess.WaitForExit(180000)){throw 'Packaged service stop remained blocked after the admitted work completed'}
            if($stopProcess.ExitCode -ne 0){throw "Packaged service stop failed with exit code $($stopProcess.ExitCode)"}
            $null=Wait-ReleaseService $script:serviceName 'Stopped'
        } finally {
            if(!$released){[IO.File]::WriteAllText($release,"release`n",(New-Object Text.UTF8Encoding($false)))}
            if($testProcess){$testProcess.Dispose()};if($stopProcess){$stopProcess.Dispose()}
        }
        $heldPhase=Get-Content (Join-Path $root 'runs\stage0-host\checkpoint.json') -Raw|ConvertFrom-Json
        if($heldPhase.status -ne 'passed' -or $heldPhase.testSelection -ne 'Stage11ShutdownIT#holdTransactionAndDownloadUntilShutdownSignalsRelease'){throw 'Stage 11 held transaction test phase was not recorded as passed'}
        return $heldPhase
    }
    function Set-ConfigValue([string]$Path,[string]$Name,[string]$Value) {
        $lines=if(Test-Path -LiteralPath $Path){[IO.File]::ReadAllLines($Path)}else{@()};$pattern='^\s*'+[regex]::Escape($Name)+'\s*='
        $indices=@(for($i=0;$i -lt $lines.Length;$i++){if($lines[$i] -match $pattern){$i}})
        if($indices.Count -gt 1){throw "Duplicate setting in isolated test configuration: $Name"}
        if($indices.Count){$lines[$indices[0]]="$Name=$Value"}else{$lines+= "$Name=$Value"}
        [IO.File]::WriteAllLines($Path,$lines,(New-Object Text.UTF8Encoding($false)))
    }
    function Assert-ServiceAccess([string]$Path,[string]$Account,[System.Security.AccessControl.FileSystemRights]$Required) {
        $acl=Get-Acl -LiteralPath $Path;$rules=$acl.GetAccessRules($true,$true,[System.Security.Principal.NTAccount])
        $rights=[System.Security.AccessControl.FileSystemRights]0
        foreach($rule in $rules){if($rule.IdentityReference.Value -ieq $Account -and $rule.AccessControlType -eq [System.Security.AccessControl.AccessControlType]::Allow){$rights=$rights -bor $rule.FileSystemRights}}
        if(($rights -band $Required) -ne $Required){throw "Service identity lacks $Required on $Path"}
    }
    function Wait-AuthenticatedDepositReadiness([string]$Uri,[string]$EvidenceName,[int]$TimeoutSeconds=120) {
        . "$PSScriptRoot\Credentials.ps1"
        $priorPassword=[Environment]::GetEnvironmentVariable('STAGE0_PASSWORD','Process');$env:STAGE0_PASSWORD=[DepositCredentialStore]::Read('Deposit/Stage0/stage0-host')
        $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds);$attempt=0;$ready=$false
        try {
            while([DateTime]::UtcNow -lt $deadline -and !$ready){$attempt++;$out=Join-Path $evidence "$EvidenceName-$attempt.out.log";$err=Join-Path $evidence "$EvidenceName-$attempt.err.log"
                $arguments="-Djava.io.tmpdir=$env:TEMP -cp `"$root\build\deposit-integration-tests\target\classes;$root\build\deposit-integration-tests\target\dependency\*`" org.deposit.it.ReadinessClient `"$Uri`""
                $client=Start-Process (Join-Path $script:javaHome 'bin\java.exe') -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err
                try {if(!$client.WaitForExit([Math]::Max(1000,[int]($deadline-[DateTime]::UtcNow).TotalMilliseconds))){$client.Kill();throw 'Authenticated Deposit readiness probe timed out'};if($client.ExitCode -eq 0){$ready=$true}else{Start-Sleep -Seconds 1}}finally{$client.Dispose()}
            }
        } finally {if($priorPassword){$env:STAGE0_PASSWORD=$priorPassword}else{Remove-Item Env:\STAGE0_PASSWORD -ErrorAction SilentlyContinue}}
        if(!$ready){throw "Authenticated Deposit readiness did not succeed; inspect $EvidenceName-*.err.log"}
    }
    function Remove-ReleaseService {
        if(!$script:serviceName -or !$script:serviceLauncher){return}
        $service=Get-Service -Name $script:serviceName -ErrorAction SilentlyContinue
        if(!$service){$script:serviceWasInstalled=$false;return}
        if($service.Status -ne 'Stopped'){
            $stopExit=Invoke-ReleaseCommand 'stop' (Join-Path $evidence 'service-cleanup-stop.log')
            if($stopExit -ne 0){throw 'Packaged service stop failed during cleanup'}
            $null=Wait-ReleaseService $script:serviceName 'Stopped'
        }
        $removeExit=Invoke-ReleaseCommand 'remove-service' (Join-Path $evidence 'service-cleanup-remove.log')
        if($removeExit -ne 0){throw 'Packaged service removal failed during cleanup'}
        if(Get-Service -Name $script:serviceName -ErrorAction SilentlyContinue){throw 'Windows service remains registered after removal'}
        $script:serviceWasInstalled=$false
    }

    Save-Checkpoint
    if((Get-Stage0Inputs $repo).sha256 -ne $inputs.sha256){throw 'Source identity changed before Stage 11 packaging'}
    $packagePhase=Phase Package;$packageRecord=Get-Content (Join-Path $packagePhase.evidence 'distribution.json') -Raw|ConvertFrom-Json;$packageFile=[string]$packageRecord.path
    if(!(Test-Path -LiteralPath $packageFile -PathType Leaf) -or (Get-FileHash $packageFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne [string]$packageRecord.sha256){throw 'Release ZIP does not match the recorded package hash'}
    $runCopy=Join-Path $evidence 'launcher smoke Δ path';New-Item -ItemType Directory -Path $runCopy -Force|Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory($packageFile,$runCopy)
    $releaseManifest=Get-Content (Join-Path $runCopy 'release-manifest.json') -Raw|ConvertFrom-Json
    foreach($item in $releaseManifest.files){$path=Join-Path $runCopy ([string]$item.path.Replace('/','\'));if(!(Test-Path -LiteralPath $path -PathType Leaf) -or (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne [string]$item.sha256 -or (Get-Item $path).Length -ne [long]$item.length){throw "Release hash/size mismatch: $($item.path)"}}
    if(!(Test-Path (Join-Path $runCopy 'deposit.bat')) -or !(Test-Path (Join-Path $runCopy 'helpers\deposit-worker.ps1')) -or !$releaseManifest.files.Count){throw 'Launcher, helper, or release inventory is missing'}
    if(Get-ChildItem (Join-Path $runCopy 'plugins') -Filter '*runtime-probe*.jar' -ErrorAction SilentlyContinue){throw 'Release package includes a runtime test probe'}
    $expectedPluginCount=@(Get-ChildItem (Join-Path $runCopy 'plugins') -Filter '*.jar').Count
    if($expectedPluginCount -ne (Get-Content (Join-Path $runCopy 'distribution-manifest.json') -Raw|ConvertFrom-Json).plugins.Count){throw 'Distribution manifest plugin set does not match package'}
    $prior=Get-Location;$priorPreference=$ErrorActionPreference
    try {$ErrorActionPreference='Continue';Set-Location $evidence;$launcher=Join-Path $runCopy 'deposit.bat';& cmd.exe /d /c "`"$launcher`" help" *> (Join-Path $evidence 'launcher-help.log');$helpExit=$LASTEXITCODE;& cmd.exe /d /c "`"$launcher`" unknown-command" *> (Join-Path $evidence 'launcher-unknown.log');$unknownExit=$LASTEXITCODE}finally{Set-Location $prior;$ErrorActionPreference=$priorPreference}
    if($helpExit -ne 0 -or $unknownExit -ne 2){throw "Packaged launcher exit-code contract failed: help=$helpExit unknown=$unknownExit"}
    $priorPreference=$ErrorActionPreference
    try {$ErrorActionPreference='Continue';$diagnosticOut=& $launcher diagnose 2>&1;$diagnosticExit=$LASTEXITCODE}finally{$ErrorActionPreference=$priorPreference}
    if($diagnosticExit -ne 1 -or ($diagnosticOut -join "`n") -match '(?i)password|token'){throw 'Launcher missing-setup diagnostics or secret-redaction contract failed'}
    $script:run.package=@{path=$packageFile;sha256=$packageRecord.sha256;releaseManifest=(Get-FileHash (Join-Path $runCopy 'release-manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant();fileCount=$releaseManifest.fileCount;pluginCount=$expectedPluginCount;probeFree=$true;launcherHelpExit=$helpExit;unknownCommandExit=$unknownExit;missingSetupExit=$diagnosticExit}

    Phase Stop
    Phase Reset
    $install=Phase Install @{ReleasePackage=$packageFile}
    $installed=Get-Content (Join-Path $root 'runs\stage0-host\installed-artifacts.json') -Raw|ConvertFrom-Json
    if($installed.diagnostic -or !$installed.releasePackage -or $installed.releasePackage -ne $packageRecord.sha256){throw 'Test installation did not originate from the exact normal release ZIP'}
    $script:run.installedPackage=$installed.releasePackage
    Phase Start
    $expected=@{'org.deposit.it.Stage1LifecycleIT'=5;'org.deposit.it.Stage1CoreIT'=9;'org.deposit.it.Stage2CoreIT'=15;'org.deposit.it.Stage3CoreIT'=16;'org.deposit.it.Stage4CoreIT'=10;'org.deposit.it.Stage5CoreIT'=24;'org.deposit.it.Stage55CoreIT'=5;'org.deposit.it.Stage6CoreIT'=18;'org.deposit.it.Stage65CoreIT'=26;'org.deposit.it.Stage65RecoveryIT'=3;'org.deposit.it.Stage6RecoveryIT'=3;'org.deposit.it.Stage55RecoveryIT'=3;'org.deposit.it.Stage1RecoveryIT'=2;'org.deposit.it.Stage2RecoveryIT'=2;'org.deposit.it.Stage3RecoveryIT'=2;'org.deposit.it.Stage4RecoveryIT'=2;'org.deposit.it.Stage5RecoveryIT'=2;'org.deposit.it.Stage7CoreIT'=16;'org.deposit.it.Stage7RecoveryIT'=3;'org.deposit.it.Stage8CoreIT'=7;'org.deposit.it.Stage8RecoveryIT'=2;'org.deposit.it.Stage9CoreIT'=26;'org.deposit.it.Stage9FormatIT'=5;'org.deposit.it.Stage9RecoveryIT'=3}
    foreach($suite in @('Stage1LifecycleIT','Stage1CoreIT','Stage2CoreIT','Stage3CoreIT','Stage4CoreIT','Stage5CoreIT','Stage55CoreIT','Stage6CoreIT','Stage65CoreIT')){Run-Tests $suite|Out-Null}
    Run-Tests 'Stage7CoreIT'|Out-Null;Run-Tests 'Stage8CoreIT'|Out-Null
    Phase Stop;Enable-Stage9TestProbe;$probeMayBeRunning=$true
    try {Phase Start @{AllowTestProbe=$true};Run-Tests 'Stage9CoreIT'|Out-Null;Phase Stop;$probeMayBeRunning=$false}
    finally {if($probeMayBeRunning){Phase Stop};Disable-Stage9TestProbe}
    Phase Start;Run-Tests 'Stage9FormatIT'|Out-Null
    foreach($suite in @('Stage55RecoveryIT#seed','Stage6RecoveryIT#seed','Stage65RecoveryIT#seed','Stage7RecoveryIT#seed','Stage8RecoveryIT#seed','Stage9RecoveryIT#seed')){Run-Tests $suite|Out-Null}
    $recovery='Stage1RecoveryIT,Stage2RecoveryIT,Stage3RecoveryIT,Stage4RecoveryIT,Stage5RecoveryIT,Stage55RecoveryIT#verify,Stage6RecoveryIT#verify,Stage65RecoveryIT#verify,Stage7RecoveryIT#verify,Stage8RecoveryIT#verify,Stage9RecoveryIT#verify'
    $forcedRecovery='Stage1RecoveryIT,Stage2RecoveryIT,Stage3RecoveryIT,Stage4RecoveryIT,Stage5RecoveryIT,Stage55RecoveryIT#verify,Stage6RecoveryIT#verify,Stage65RecoveryIT#verify,Stage7RecoveryIT#verify,Stage9RecoveryIT#verify'
    Phase Stop;Phase Start;Run-Tests $recovery|Out-Null;Phase Kill;Phase Start;Run-Tests $forcedRecovery|Out-Null;Phase Stop
    Phase Start;$stage10Core=Run-Tests 'Stage10CoreIT';$stage10Expected=@('browserLoginReusesCookieStreamsExactBytesAndSupportsRangesAndCsrfLogout','browserAndBoltTokensAreTransportBoundAndAccountDisableRevokesBoth','browserAdministrativeModeIsExplicitSessionLocalAndClearedOnDemotion','creatorBoundExportGrantDoesNotBecomeBrowserAccessForAnotherNamespaceMember','namespaceRevocationStopsAnAlreadyStreamingDownloadBetweenChunks','browserHistoryAccessHonorsClosedEpochRetentionAndTransientExpiry','csvXlsxAndGeoPackageRenderLinksWithoutGrantingTheReferencedResource')
    if($stage10Core.Count -ne $stage10Expected.Count -or @($stage10Core.name|Where-Object {$_ -notin $stage10Expected}).Count -or @($stage10Expected|Where-Object {$_ -notin $stage10Core.name}).Count){throw 'Stage 10 release browser cases are incomplete'}
    $sessionFile=Join-Path $root 'runs\stage0-host\secrets\stage10-browser-session.properties';Remove-Item -LiteralPath $sessionFile -Force -ErrorAction SilentlyContinue
    $seed=Run-Tests 'Stage10RestartIT#seed';$seedCases=@($seed);if($seedCases.Count -ne 1 -or [string]$seedCases[0].name -ne 'seed'){throw 'Stage 10 restart fixture was not seeded'}
    Phase Stop;Phase Start;$verify=Run-Tests 'Stage10RestartIT#verify';$verifyCases=@($verify);if($verifyCases.Count -ne 1 -or [string]$verifyCases[0].name -ne 'verify'){throw 'Stage 10 graceful restart checks failed'}
    Phase Stop
    $installation=Join-Path $root 'runs\stage0-host\neo4j';$configPath=Join-Path $installation 'conf\deposit.properties';$neo4jConfigPath=Join-Path $installation 'conf\neo4j.conf'
    $originalDepositConfig=Join-Path $evidence 'deposit.properties.original';$originalNeo4jConfig=Join-Path $evidence 'neo4j.conf.original'
    Copy-Item -LiteralPath $configPath -Destination $originalDepositConfig;Copy-Item -LiteralPath $neo4jConfigPath -Destination $originalNeo4jConfig
    $downloadPortMatch=[regex]::Match([IO.File]::ReadAllText($configPath),'(?m)^[ \t]*download\.port[ \t]*=[ \t]*(\d+)[ \t]*\r?$')
    if(!$downloadPortMatch.Success){throw 'Installed download listener port is missing'}
    $downloadPort=[int]$downloadPortMatch.Groups[1].Value
    $bindAddress=Get-NetIPAddress -AddressFamily IPv4 -AddressState Preferred|Where-Object {$_.IPAddress -notmatch '^(127\.|169\.254\.)'}|Select-Object -First 1 -ExpandProperty IPAddress
    if(!$bindAddress){throw 'No non-loopback IPv4 address is available for isolated TLS acceptance'}
    $keyTool=$null;foreach($directory in Get-ChildItem (Join-Path $root 'tools') -Directory){$candidate=Join-Path $directory.FullName 'bin\keytool.exe';if(Test-Path -LiteralPath $candidate){$keyTool=$candidate;break}};if(!$keyTool){throw 'Pinned keytool is unavailable'}
    $secretDirectory=Join-Path $root 'runs\stage0-host\secrets';$keyStore=Join-Path $secretDirectory 'stage11-download-test.p12';$passwordFile=Join-Path $secretDirectory 'stage11-download-test.password';$trustStore=Join-Path $secretDirectory 'stage11-client-trust.p12';$keyPassword='Stage11-Test-Key-Store-Only-2026'
    [IO.File]::WriteAllText($passwordFile,$keyPassword,(New-Object Text.UTF8Encoding($false)));& $keyTool -genkeypair -alias stage11-download -keyalg RSA -keysize 2048 -validity 3 -dname "CN=$bindAddress" -ext "SAN=ip:127.0.0.1,ip:$bindAddress" -storetype PKCS12 -keystore $keyStore -storepass $keyPassword -keypass $keyPassword -noprompt|Out-Null;if($LASTEXITCODE -ne 0){throw 'Could not create the isolated TLS certificate'}
    $boltCertificateDirectory=Join-Path $installation 'certificates\bolt';New-Item -ItemType Directory -Path $boltCertificateDirectory -Force|Out-Null
    $tlsExporterSource=Join-Path $evidence 'ExportStage11TlsMaterial.java';$tlsExporterDirectory=Join-Path $evidence 'tls-exporter';New-Item -ItemType Directory -Path $tlsExporterDirectory -Force|Out-Null
    $tlsExporterSourceText=@'
import java.io.*;import java.nio.file.*;import java.security.*;import java.security.cert.Certificate;import java.util.*;
public final class ExportStage11TlsMaterial {
  private static String pem(String label,byte[] bytes){return "-----BEGIN "+label+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)+"\n-----END "+label+"-----\n";}
  public static void main(String[] a)throws Exception{char[] p=Files.readString(Path.of(a[1])).trim().toCharArray();KeyStore k=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(Path.of(a[0]))){k.load(in,p);}String alias=k.aliases().nextElement();PrivateKey key=(PrivateKey)k.getKey(alias,p);Certificate cert=k.getCertificate(alias);Files.writeString(Path.of(a[2]),pem("PRIVATE KEY",key.getEncoded()));Files.writeString(Path.of(a[3]),pem("CERTIFICATE",cert.getEncoded()));}
}
'@
    [IO.File]::WriteAllText($tlsExporterSource,$tlsExporterSourceText,(New-Object Text.UTF8Encoding($false)))
    & (Join-Path $script:javaHome 'bin\javac.exe') '-d' $tlsExporterDirectory $tlsExporterSource;if($LASTEXITCODE -ne 0){throw 'Could not compile the isolated TLS certificate exporter'}
    & (Join-Path $script:javaHome 'bin\java.exe') '-cp' $tlsExporterDirectory 'ExportStage11TlsMaterial' $keyStore $passwordFile (Join-Path $boltCertificateDirectory 'private.key') (Join-Path $boltCertificateDirectory 'public.crt');if($LASTEXITCODE -ne 0){throw 'Could not export the isolated Bolt TLS key and certificate'}
    $publicCertificate=Join-Path $boltCertificateDirectory 'public.crt'
    & $keyTool -importcert -alias stage11-download -file $publicCertificate -storetype PKCS12 -keystore $trustStore -storepass $keyPassword -noprompt|Out-Null;if($LASTEXITCODE -ne 0){throw 'Could not create the isolated test trust store'}
    $boltCertificateBase=$boltCertificateDirectory.Replace('\','/')
    Set-ConfigValue $neo4jConfigPath 'server.bolt.tls_level' 'REQUIRED'
    Set-ConfigValue $neo4jConfigPath 'dbms.ssl.policy.bolt.enabled' 'true'
    Set-ConfigValue $neo4jConfigPath 'dbms.ssl.policy.bolt.base_directory' $boltCertificateBase
    Set-ConfigValue $neo4jConfigPath 'dbms.ssl.policy.bolt.private_key' 'private.key'
    Set-ConfigValue $neo4jConfigPath 'dbms.ssl.policy.bolt.public_certificate' 'public.crt'
    Set-ConfigValue $neo4jConfigPath 'dbms.ssl.policy.bolt.client_auth' 'NONE'
    $configText=[IO.File]::ReadAllText($configPath);$settings=@{'download.listenAddress'=$bindAddress;'download.publicBaseUrl'="https://${bindAddress}:$downloadPort/stage11-proxy";'download.tlsKeyStore'=$keyStore.Replace('\','/');'download.tlsKeyStorePasswordFile'=$passwordFile.Replace('\','/')}
    foreach($key in $settings.Keys){$pattern='(?m)^'+[regex]::Escape($key)+'=.*$';$replacement=$key+'='+$settings[$key];if([regex]::IsMatch($configText,$pattern)){$configText=[regex]::Replace($configText,$pattern,$replacement)}else{$configText=$configText.TrimEnd()+"`r`n"+$replacement+"`r`n"}};[IO.File]::WriteAllText($configPath,$configText,(New-Object Text.UTF8Encoding($false)))
    $script:oldJavaToolOptions=[Environment]::GetEnvironmentVariable('JAVA_TOOL_OPTIONS','Process');$env:JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$trustStore -Djavax.net.ssl.trustStorePassword=$keyPassword"
    $tlsBoltUri="neo4j+s://127.0.0.1:$Port"
    Phase Start @{BoltUri=$tlsBoltUri};$tls=Run-Tests 'Stage10TlsIT' @{BoltUri=$tlsBoltUri};$tlsCases=@($tls);if($tlsCases.Count -ne 1 -or [string]$tlsCases[0].name -ne 'directHttpsSetsSecureScopedCookieAndReusesTheSession'){throw 'Direct HTTPS browser acceptance failed'};Phase Stop

    $releaseInstall=Get-Content (Join-Path $root 'runs\stage0-host\release-install.json') -Raw|ConvertFrom-Json
    $script:serviceLauncher=Join-Path $releaseInstall.distribution 'deposit.bat';$packageStatePath=Join-Path $releaseInstall.distribution 'deposit.install.json'
    $packageState=Get-Content -LiteralPath $packageStatePath -Raw|ConvertFrom-Json;$script:serviceName=[string]$packageState.serviceName
    if(!$script:serviceName){throw 'Release installation has no Windows service identity'}
    if(Test-Path -LiteralPath (Join-Path $installation 'deposit-process.json')){throw 'Local launcher still owns the Neo4j process before service installation'}
    $installServiceExit=Invoke-ReleaseCommand 'install-service' (Join-Path $evidence 'service-install.log')
    if($installServiceExit -ne 0){throw 'Dedicated-account service installation failed; see service-install.log'}
    $script:serviceWasInstalled=$true
    $serviceState=Get-Content -LiteralPath $packageStatePath -Raw|ConvertFrom-Json
    $serviceAccount=[string]$serviceState.serviceAccount
    if($serviceState.mode -ne 'service' -or !$serviceAccount -or $serviceAccount -ine "NT SERVICE\$script:serviceName"){throw 'Release service identity is not the dedicated virtual account'}
    $readRights=[System.Security.AccessControl.FileSystemRights]::ReadAndExecute;$modifyRights=[System.Security.AccessControl.FileSystemRights]::Modify
    foreach($path in @($serviceState.neo4jHome,$serviceState.javaHome)){Assert-ServiceAccess $path $serviceAccount $readRights}
    foreach($path in @((Join-Path $serviceState.neo4jHome 'data'),(Join-Path $serviceState.neo4jHome 'logs'),$serviceState.dataRoot,$serviceState.exportRoot)){Assert-ServiceAccess $path $serviceAccount $modifyRights}
    foreach($path in @($keyStore,$passwordFile)){& icacls.exe $path '/grant' "$serviceAccount`:R" '/Q'|Out-Null;if($LASTEXITCODE -ne 0){throw "Could not grant test TLS material access to $serviceAccount"}}
    $startServiceExit=Invoke-ReleaseCommand 'start' (Join-Path $evidence 'service-start.log');if($startServiceExit -ne 0){throw 'Packaged service start command failed'}
    $null=Wait-ReleaseService $script:serviceName 'Running'
    $serviceCim=Get-CimInstance Win32_Service -Filter "Name='$script:serviceName'"
    if($serviceCim.StartName -ine $serviceAccount -or !$serviceCim.ProcessId){throw 'Windows service is not running under its dedicated identity'}
    Wait-AuthenticatedDepositReadiness $tlsBoltUri 'service-readiness'
    Assert-ServiceAccess (Join-Path $boltCertificateDirectory 'private.key') $serviceAccount $readRights
    Assert-ServiceAccess $publicCertificate $serviceAccount $readRights
    Assert-ServiceAccess $keyStore $serviceAccount $readRights
    Assert-ServiceAccess $passwordFile $serviceAccount $readRights
    if(!$serviceState.secretRoot){throw 'Release service installation has no configured secret directory'};$serviceSecretRoot=[IO.Path]::GetFullPath([string]$serviceState.secretRoot);$boltSecret=Join-Path $serviceSecretRoot 'deposit-bootstrap.secret';$cursorSecrets=@(Get-ChildItem -LiteralPath $serviceSecretRoot -Filter 'deposit-*-cursor.key' -File)
    if($cursorSecrets.Count -ne 1){throw "Expected one service-owned cursor secret, found $($cursorSecrets.Count)"};$cursorSecret=$cursorSecrets[0].FullName
    if(Test-Path -LiteralPath (Join-Path $serviceState.neo4jHome 'conf\deposit-bootstrap.secret')){throw 'Bootstrap secret remains in Neo4j conf after secret adoption'}
    $run.serviceMode=@{serviceName=$script:serviceName;serviceAccount=$serviceAccount;startName=$serviceCim.StartName;config='TLS required for Bolt; HTTPS enabled for browser downloads';directoryPermissions='verified read/execute runtime, modify data/log/resource/export'}
    $serviceRuntimeSeed=Phase Test @{TestSelection='RuntimeIT';TestMode='seed';BoltUri=$tlsBoltUri};$serviceTests=@(Collect-TestCases $serviceRuntimeSeed)
    $serviceBrowserCore=Phase Test @{TestSelection='Stage10CoreIT';BoltUri=$tlsBoltUri};$serviceBrowserCases=@(Collect-TestCases $serviceBrowserCore);$serviceBrowserExpected=@('browserLoginReusesCookieStreamsExactBytesAndSupportsRangesAndCsrfLogout','browserAndBoltTokensAreTransportBoundAndAccountDisableRevokesBoth','browserAdministrativeModeIsExplicitSessionLocalAndClearedOnDemotion','creatorBoundExportGrantDoesNotBecomeBrowserAccessForAnotherNamespaceMember','namespaceRevocationStopsAnAlreadyStreamingDownloadBetweenChunks','browserHistoryAccessHonorsClosedEpochRetentionAndTransientExpiry','csvXlsxAndGeoPackageRenderLinksWithoutGrantingTheReferencedResource')
    if($serviceBrowserCases.Count -ne $serviceBrowserExpected.Count -or @($serviceBrowserCases.name|Where-Object {$_ -notin $serviceBrowserExpected}).Count -or @($serviceBrowserExpected|Where-Object {$_ -notin $serviceBrowserCases.name}).Count){throw 'Service-mode browser integration cases are incomplete'};$serviceTests+=@($serviceBrowserCases)
    $preCrashRecovery=Phase Test @{TestSelection='Stage1RecoveryIT';BoltUri=$tlsBoltUri};$serviceTests+=@(Collect-TestCases $preCrashRecovery)
    $serviceBrowserSeed=Phase Test @{TestSelection='Stage10RestartIT#seed';TestMode='seed';BoltUri=$tlsBoltUri};$serviceTests+=@(Collect-TestCases $serviceBrowserSeed)
    $operatorAccount=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $serviceSecretFiles=@($cursorSecret);if(Test-Path -LiteralPath $boltSecret -PathType Leaf){$serviceSecretFiles+=@($boltSecret)}
    foreach($path in $serviceSecretFiles){if(!(Test-Path -LiteralPath $path -PathType Leaf)){throw "Service secret is missing: $path"};$secretAcl=Get-Acl -LiteralPath $path;if($secretAcl.Owner -ine $operatorAccount){throw "Secret is not owned by the installation operator: $path"};$rules=$secretAcl.GetAccessRules($true,$true,[System.Security.Principal.NTAccount]);$principals=@($rules|Where-Object {$_.AccessControlType -eq [System.Security.AccessControl.AccessControlType]::Allow}|ForEach-Object {$_.IdentityReference.Value}|Sort-Object -Unique);if(@($principals|Where-Object {$_ -ine $serviceAccount -and $_ -ine $operatorAccount}).Count -or @(@($serviceAccount,$operatorAccount)|Where-Object {$_ -notin $principals}).Count){throw "Secret ACL is not limited to the operator and service identity: $path"};Assert-ServiceAccess $path $serviceAccount ([System.Security.AccessControl.FileSystemRights]::Read)
    }
    $neo4jJvm=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'"|Where-Object {$_.CommandLine -and $_.CommandLine.Contains([IO.Path]::GetFullPath($installation)) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'})
    if($neo4jJvm.Count -ne 1){throw "Expected one service-owned Neo4j JVM before crash test, found $($neo4jJvm.Count)"}
    $drainPhase=Invoke-Stage11GracefulDrainTest $tlsBoltUri
    $startServiceExit=Invoke-ReleaseCommand 'start' (Join-Path $evidence 'service-start-after-drain.log');if($startServiceExit -ne 0){throw 'Packaged service did not restart after graceful shutdown acceptance'}
    $null=Wait-ReleaseService $script:serviceName 'Running';Wait-AuthenticatedDepositReadiness $tlsBoltUri 'service-post-drain-readiness'
    $drainHoldCases=@(Collect-TestCases $drainPhase);if($drainHoldCases.Count -ne 1 -or $drainHoldCases[0].name -ne 'holdTransactionAndDownloadUntilShutdownSignalsRelease'){throw 'Stage 11 held transaction and download acceptance failed'};$serviceTests+=@($drainHoldCases)
    $drainVerify=Run-Tests 'Stage11ShutdownIT#verifyCommittedTransactionSurvivedServiceShutdown' @{BoltUri=$tlsBoltUri};$drainVerifyCases=@($drainVerify);if($drainVerifyCases.Count -ne 1 -or [string]$drainVerifyCases[0].name -ne 'verifyCommittedTransactionSurvivedServiceShutdown'){throw 'Stage 11 graceful drain verification did not pass'};$serviceTests+=@($drainVerifyCases)
    $crashedPid=[int]$neo4jJvm[0].ProcessId;$crashedCreated=$neo4jJvm[0].CreationDate.ToUniversalTime().ToString('o');Stop-Process -Id $crashedPid -Force
    $crashDeadline=[DateTime]::UtcNow.AddSeconds(90);$crashServiceStatus=$null;$restartedJvm=$null
    while([DateTime]::UtcNow -lt $crashDeadline){$survivor=Get-CimInstance Win32_Process -Filter "ProcessId=$crashedPid";$currentService=Get-Service -Name $script:serviceName -ErrorAction SilentlyContinue;$restartedJvm=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'"|Where-Object {$_.CommandLine -and $_.CommandLine.Contains([IO.Path]::GetFullPath($installation)) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'}|Where-Object {$_.ProcessId -ne $crashedPid});if(!$survivor -and $currentService -and ($currentService.Status -eq 'Stopped' -or ($currentService.Status -eq 'Running' -and $restartedJvm.Count -eq 1))){$crashServiceStatus=$currentService.Status.ToString();break};Start-Sleep -Milliseconds 250}
    if(Get-CimInstance Win32_Process -Filter "ProcessId=$crashedPid"){throw 'Forced service JVM termination did not complete'}
    if(!$crashServiceStatus){throw 'Windows service did not stop or restart after the forced Neo4j process termination'}
    if($crashServiceStatus -eq 'Stopped'){$restartServiceExit=Invoke-ReleaseCommand 'start' (Join-Path $evidence 'service-restart.log');if($restartServiceExit -ne 0){throw 'Packaged service restart after process termination failed'}}
    $null=Wait-ReleaseService $script:serviceName 'Running'
    $restartedJvm=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'"|Where-Object {$_.CommandLine -and $_.CommandLine.Contains([IO.Path]::GetFullPath($installation)) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'})
    if($restartedJvm.Count -ne 1 -or $restartedJvm[0].ProcessId -eq $crashedPid){throw 'Service restart did not create a new Neo4j JVM'}
    Wait-AuthenticatedDepositReadiness $tlsBoltUri 'service-restart-readiness'
    $postCrashRuntime=Phase Test @{TestSelection='RuntimeIT';TestMode='verify';BoltUri=$tlsBoltUri};$serviceTests+=@(Collect-TestCases $postCrashRuntime)
    $postCrashRecovery=Phase Test @{TestSelection='Stage1RecoveryIT';BoltUri=$tlsBoltUri};$serviceTests+=@(Collect-TestCases $postCrashRecovery)
    $postCrashBrowser=Phase Test @{TestSelection='Stage10RestartIT#verify';TestMode='verify';BoltUri=$tlsBoltUri};$serviceTests+=@(Collect-TestCases $postCrashBrowser)
    $serviceTls=Phase Test @{TestSelection='Stage10TlsIT';BoltUri=$tlsBoltUri};$serviceTlsCases=@(Collect-TestCases $serviceTls);if($serviceTlsCases.Count -ne 1 -or $serviceTlsCases[0].name -ne 'directHttpsSetsSecureScopedCookieAndReusesTheSession'){throw 'Service-mode HTTPS browser acceptance failed'};$serviceTests+=@($serviceTlsCases)
    $stopServiceExit=Invoke-ReleaseCommand 'stop' (Join-Path $evidence 'service-stop.log');if($stopServiceExit -ne 0){throw 'Packaged service stop command failed'}
    $null=Wait-ReleaseService $script:serviceName 'Stopped'
    Remove-ReleaseService
    $run.serviceMode.integrationExecutions=$serviceTests.Count;$run.serviceMode.authenticatedReadiness='passed';$run.serviceMode.forcedRestart=@{passed=$true;serviceObserved=$crashServiceStatus};$run.serviceMode.gracefulStop='passed';$run.serviceMode.exactPackage='probe-free';$run.serviceTests=$serviceTests
    Copy-Item -LiteralPath $originalDepositConfig -Destination $configPath -Force;Copy-Item -LiteralPath $originalNeo4jConfig -Destination $neo4jConfigPath -Force
    if((Get-FileHash $configPath -Algorithm SHA256).Hash -ne (Get-FileHash $originalDepositConfig -Algorithm SHA256).Hash -or (Get-FileHash $neo4jConfigPath -Algorithm SHA256).Hash -ne (Get-FileHash $originalNeo4jConfig -Algorithm SHA256).Hash){throw 'Service-mode configuration cleanup failed hash verification'}
    Remove-Item -LiteralPath (Join-Path $boltCertificateDirectory 'private.key'),$publicCertificate -Force
    if($oldJavaToolOptions){$env:JAVA_TOOL_OPTIONS=$oldJavaToolOptions}else{Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue}
    Remove-Item -LiteralPath $keyStore,$passwordFile,$trustStore -Force
    if((Get-Stage0Inputs $repo).sha256 -ne $inputs.sha256){throw 'Source identity changed during release acceptance'}
    $all=@($script:run.releaseTests);if($all.Count -ne 219){throw "Release acceptance expected 219 in-scope integration executions, got $($all.Count)"}
    foreach($class in $expected.Keys){if([int]$script:classes[$class] -ne $expected[$class]){throw "Incomplete release recovery/core coverage for $class"}}
    $run.unitTests=[int]$stage10.unitTests;$run.releaseIntegrationExecutions=$all.Count;$run.localLauncherLifecycle=@{mode='local-console';start='passed';stop='passed';normalRuntime='probe-free'};$run.status='verified'
} catch { $run.status='incomplete';$run.cause=$_.Exception.Message }
finally {
    try {
        if($script:serviceLauncher -and $script:serviceName){Remove-ReleaseService}
        $checkpoint=Get-Content (Join-Path $root 'runs\stage0-host\checkpoint.json') -Raw|ConvertFrom-Json -ErrorAction SilentlyContinue
        if($checkpoint -and $checkpoint.status -eq 'passed'){Phase Stop}elseif(Test-Path -LiteralPath (Join-Path $root 'runs\stage0-host\owner.json')){& "$PSScriptRoot\Stage0.ps1" -Phase Stop -RunId stage0-host -Port $Port -InputsFile $inputsFile}
        if($originalDepositConfig -and (Test-Path -LiteralPath $originalDepositConfig)){Copy-Item -LiteralPath $originalDepositConfig -Destination $configPath -Force;Copy-Item -LiteralPath $originalNeo4jConfig -Destination $neo4jConfigPath -Force}
        if($boltCertificateDirectory){Remove-Item -LiteralPath (Join-Path $boltCertificateDirectory 'private.key'),(Join-Path $boltCertificateDirectory 'public.crt') -Force -ErrorAction SilentlyContinue}
        if($script:oldJavaToolOptions){$env:JAVA_TOOL_OPTIONS=$script:oldJavaToolOptions}else{Remove-Item Env:\JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue}
        foreach($name in @('stage1-session.txt','stage55-session.txt','stage10-browser-session.properties','stage10-download-test.p12','stage10-download-test.password','stage11-download-test.p12','stage11-download-test.password','stage11-client-trust.p12')){$path=Join-Path $root ('runs\stage0-host\secrets\'+$name);Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue}
    } catch {$run.status='incomplete';$run.accepted=$false;$run.cause='Final local stop/cleanup failed: '+$_.Exception.Message}
    if($run.status -eq 'verified'){$run.status='accepted';$run.accepted=$true}
    Save-Checkpoint
    try {if($ownsResult){Complete-Stage0GateResult $root $evidence $RunPrefix}}finally{if($gateLock){$gateLock.Dispose()};$cleanupBarrier.Dispose()}
}
if($run.status -ne 'accepted'){Write-Error "Stage 11 acceptance is incomplete; evidence: $evidence";exit 1}
