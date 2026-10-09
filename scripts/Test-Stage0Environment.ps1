[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]{0,45}$')][string]$RunPrefix=('environment-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')),
    [ValidateRange(1024,65534)][int]$Port=17770,
    [string]$InputsFile,
    [switch]$Build,
    [switch]$Stage2Checks,
    [switch]$Stage3Checks
)
# Final Windows environment suite; collection also requires the live runner fixture.
. "$PSScriptRoot\Inputs.ps1"
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root='C:\data_processed\Deposit_v2\stage0'
$primary='stage0-host'
$inputs=if($InputsFile) {Get-Content -LiteralPath $InputsFile -Raw | ConvertFrom-Json} else {Get-Stage0Inputs (Split-Path $PSScriptRoot -Parent)}
if(!$InputsFile) {
    $InputsFile=Join-Path $root ("evidence\$RunPrefix\source-inputs.json")
    New-Item -ItemType Directory -Force (Split-Path $InputsFile -Parent) | Out-Null
    $inputs | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $InputsFile -Encoding UTF8
}
$directory=Join-Path $root "evidence\$RunPrefix"
New-Item -ItemType Directory -Force $directory | Out-Null
$results=New-Object System.Collections.Generic.List[object]
$complete=$false
$script:scenarioPhases=New-Object System.Collections.Generic.List[object]
function Save-Results {
    @{schemaVersion=2;installation=$primary;port=$Port;inputs=$inputs.sha256;status=$(if(!$complete -or @($results | Where-Object status -eq 'failed').Count){'incomplete'}else{'environment-checks-passed'});acceptance=$false;results=$results.ToArray();rerun=".\scripts\Test-Stage0Environment.ps1 -Build -RunPrefix <fresh-id> -Port $Port"} |
        ConvertTo-Json -Depth 8 | Set-Content "$directory\results.json" -Encoding UTF8
}
function Scenario([string]$Name,[scriptblock]$Action) {
    $watch=[Diagnostics.Stopwatch]::StartNew(); $failure=$null
    $script:scenarioPhases=New-Object System.Collections.Generic.List[object]
    try {& $Action} catch {$failure=$_.Exception.Message; throw}
    finally {
        $results.Add(@{name=$Name;status=$(if($failure){'failed'}else{'passed'});failure=$failure;seconds=$watch.Elapsed.TotalSeconds;phases=$script:scenarioPhases.ToArray()})
        Save-Results
    }
}
function Phase([string]$Name,[string]$Run=$primary,[int]$BoltPort=$Port,[hashtable]$Options=@{}) {
    try {& "$PSScriptRoot\Stage0.ps1" -Phase $Name -RunId $Run -Port $BoltPort -EvidenceGroup $RunPrefix -InputsFile $InputsFile @Options}
    finally {
        $checkpoint=Get-Content "$root\runs\$Run\checkpoint.json" -Raw | ConvertFrom-Json
        if($checkpoint.phase -ne $Name -or $checkpoint.runId -ne $Run) {throw 'Phase did not save its checkpoint'}
        $path=Join-Path $checkpoint.evidence "checkpoint-$Name.json"
        $script:scenarioPhases.Add(@{runId=$Run;phase=$Name;status=$checkpoint.status;path=$path})
    }
}
function Reject([scriptblock]$Action,[string]$Message) {
    try {& $Action} catch {if($_.Exception.Message -like $Message) {return}; throw}
    throw "Expected rejection: $Message"
}
$installationDirectory=Join-Path $root "runs\$primary\neo4j"
try {
    if($Build) {Scenario 'clean-wrapper-build' {Phase Build}}
    if(Test-Path "$root\runs\$primary\owner.json") {Scenario 'archive-previous-installation' {Phase Reset}}
    Scenario 'install-single-instance' {Phase Install}
    Scenario 'authenticated-readiness' {Phase Start}
    Scenario 'native-runtime-authentication-and-fixtures' {Phase Test -Options @{TestSelection='EnvironmentIT#nativeRuntimeAndApoc,RuntimeIT'}}
    Scenario 'independent-client-sessions' {Phase Test -Options @{TestSelection='EnvironmentIT#independentClientSessions'}}
    if($Stage2Checks) {Scenario 'stage2-isolated-clock-and-commit-faults' {Phase Test -Options @{TestSelection='Stage2ProbeIT'}}}
    if($Stage3Checks) {Scenario 'stage3-isolated-batch-commit-faults' {Phase Test -Options @{TestSelection='Stage3ProbeIT'}}}
    Scenario 'graceful-restart-and-persistent-uuid-maps' {
        Phase Stop; Phase Start
        Phase Test -Options @{TestMode='verify';TestSelection='RuntimeIT,EnvironmentIT#independentClientSessions'}
    }
    Scenario 'actual-termination-and-recovery' {
        Phase Kill; Phase Start
        Phase Test -Options @{TestMode='verify';TestSelection='RuntimeIT,EnvironmentIT#independentClientSessions'}
        Phase Stop
    }
    Scenario 'reject-modified-installed-runtime' {
        $file=Join-Path $installationDirectory 'bin\neo4j.bat'; $bytes=[IO.File]::ReadAllBytes($file)
        try {[IO.File]::AppendAllText($file,"`r`nrem Stage0 corruption fixture`r`n"); Reject {Phase Start} 'Runtime file differs from pinned archive*'}
        finally {[IO.File]::WriteAllBytes($file,$bytes)}
    }
    Scenario 'reject-missing-spatial' {
        $manifest=Get-Content (Join-Path $PSScriptRoot '..\runtime\artifacts.json') -Raw | ConvertFrom-Json
        $name=($manifest.artifacts | Where-Object id -eq 'spatial').file
        $file=Join-Path $installationDirectory "plugins\$name"; $holding=Join-Path $root "runs\$primary\$name"
        try {Move-Item -LiteralPath $file -Destination $holding; Reject {Phase Start} 'Installed plugin set differs*'}
        finally {if(Test-Path -LiteralPath $holding){Move-Item -LiteralPath $holding -Destination $file}}
    }
    Scenario 'reject-modified-spatial-binary' {
        $manifest=Get-Content (Join-Path $PSScriptRoot '..\runtime\artifacts.json') -Raw | ConvertFrom-Json
        $file=Join-Path $installationDirectory ('plugins\'+($manifest.artifacts | Where-Object id -eq 'spatial').file)
        $bytes=[IO.File]::ReadAllBytes($file)
        try {[IO.File]::AppendAllText($file,'Stage0 invalid binary fixture'); Reject {Phase Start} 'Installed plugin changed*'}
        finally {[IO.File]::WriteAllBytes($file,$bytes)}
    }
    Scenario 'reject-occupied-port' {
        $listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$Port)
        try {$listener.Start(); Reject {Phase Start} '*Only one usage of each socket address*'} finally {$listener.Stop()}
    }
    Scenario 'reject-invalid-deposit-configuration' {
        $file=Join-Path $installationDirectory 'conf\deposit.properties'; $bytes=[IO.File]::ReadAllBytes($file)
        try {
            [IO.File]::AppendAllText($file,"`ninvalidStage0Key=true`n")
            Reject {Phase Start} 'Deposit startup readiness rejected*'
            Phase Test -Options @{TestSelection='EnvironmentIT#rejectsUnavailableDependenciesAndConfiguration';ExpectedFailure='invalid-deposit-config'}
        } finally {Phase Stop; [IO.File]::WriteAllBytes($file,$bytes)}
    }
    Scenario 'strict-neo4j-configuration-validation' {
        $file=Join-Path $installationDirectory 'conf\neo4j.conf'; $bytes=[IO.File]::ReadAllBytes($file)
        try {
            [IO.File]::AppendAllText($file,"`ninvalid.stage0.setting=true`n")
            Reject {Phase Start} 'Neo4j launcher exited before starting server'
            $checkpoint=Get-Content "$root\runs\$primary\checkpoint.json" -Raw | ConvertFrom-Json
            $logs=Get-Content "$($checkpoint.evidence)\console.*.log" -Raw
            if(($logs -join '') -notmatch 'invalid.stage0.setting') {throw 'Strict validation diagnostic is missing'}
        } finally {Phase Stop; [IO.File]::WriteAllBytes($file,$bytes)}
    }
    Scenario 'bounded-startup-timeout' {
        $file=Join-Path $installationDirectory 'conf\neo4j.conf'; $bytes=[IO.File]::ReadAllBytes($file)
        try {
            # Real JVM suspends before main; the launcher must time out and retain its owned PID.
            [IO.File]::AppendAllText($file,"`nserver.jvm.additional=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0`n")
            Reject {Phase Start -Options @{StartupTimeoutSeconds=5}} 'Neo4j *timeout'
        } finally {Phase Kill; [IO.File]::WriteAllBytes($file,$bytes)}
    }
    Scenario 'runner-checkpoint-and-resume-policy' {& "$PSScriptRoot\Test-Runner.ps1"}
    Scenario 'sequential-clean-reprovisioning' {
        $before=Get-Content "$root\runs\$primary\installed-artifacts.json" -Raw | ConvertFrom-Json
        Phase Reset; Phase Install; Phase Start
        Phase Test -Options @{TestSelection='RuntimeIT,EnvironmentIT#nativeRuntimeAndApoc+independentClientSessions'}
        Phase Stop
        $after=Get-Content "$root\runs\$primary\installed-artifacts.json" -Raw | ConvertFrom-Json
        if(Compare-Object @($before.plugins | ForEach-Object { $_.name+':'+$_.sha256 }) @($after.plugins | ForEach-Object { $_.name+':'+$_.sha256 })) {throw 'Clean provisioning changed artifact hashes'}
    }
    Scenario 'normal-distribution-excludes-probe-and-permissions' {
        Phase Reset; Phase Package; Phase Install -Options @{WithoutProbe=$true}; Phase Start
        try {
            Phase Test -Options @{TestSelection='DistributionIT'}
            if(Get-ChildItem "$root\runs\$primary\distribution\plugins" -Filter '*probe*') {throw 'Normal package contains the test probe'}
            $config=Get-Content "$root\runs\$primary\distribution\conf\neo4j.conf" -Raw
            if($config -match 'stage0\.geometry|procedures\.unrestricted') {throw 'Normal package grants test-only permissions'}
        } finally {Phase Stop}
    }
    Scenario 'evidence-failure-still-saves-checkpoint-and-restores-environment' {
        $id=$primary; $logDirectory=Join-Path $root "runs\$id\neo4j\logs"
        New-Item -ItemType Directory -Force $logDirectory | Out-Null
        $logFile=Join-Path $logDirectory 'locked.log'; [IO.File]::WriteAllText($logFile,'Evidence failure fixture')
        $handle=[IO.File]::Open($logFile,'Open','ReadWrite','None')
        $before=@{}
        foreach($key in @('JAVA_HOME','TEMP','TMP','MAVEN_OPTS')) {$before[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
        try {Reject {Phase Evidence $id $Port} '*'} finally {$handle.Dispose()}
        $checkpoint=Get-Content "$root\runs\$id\checkpoint.json" -Raw | ConvertFrom-Json
        if($checkpoint.status -ne 'failed' -or !$checkpoint.evidenceFailure) {throw 'Evidence failure did not produce a durable failed checkpoint'}
        foreach($key in $before.Keys) {if([Environment]::GetEnvironmentVariable($key,'Process') -ne $before[$key]) {throw "Environment was not restored: $key"}}
    }
    $complete=$true
    Write-Output "Environment checks passed. Evidence: $directory"
} finally {
    if(Test-Path "$root\runs\$primary\owner.json") {
        try {Phase Stop} catch {Write-Warning $_.Exception.Message; Phase Kill}
    }
    Save-Results
}
