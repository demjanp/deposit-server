[CmdletBinding()]
param(
    [ValidateSet('Provision','Build','Package','Install','Start','Stop','Kill','Reset')]
    [string]$Phase = 'Build',
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$')]
    [string]$RunId = 'test-host',
    [ValidateRange(1024,65535)][int]$Port = 17770,
    [string]$Root = (Join-Path $PSScriptRoot '..\..\test_harness'),
    [string]$ReleasePackage,
    [string]$BoltUri,
    [switch]$DiagnosticBuild,
    [switch]$WithoutProbe,
    [ValidateRange(5,300)][int]$StartupTimeoutSeconds = 90
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\Credentials.ps1"
. "$PSScriptRoot\IntegrationHarnessSecrets.ps1"
. "$PSScriptRoot\ArtifactIntegrity.ps1"
. "$PSScriptRoot\HarnessPaths.ps1"
. "$PSScriptRoot\helpers\PackageArchive.ps1"
. "$PSScriptRoot\helpers\HarnessProvisioning.ps1"
. "$PSScriptRoot\helpers\HarnessBuildPackage.ps1"
. "$PSScriptRoot\helpers\HarnessRuntime.ps1"
$repo = Split-Path $PSScriptRoot -Parent
$Root = [IO.Path]::GetFullPath($Root).TrimEnd('\')
Assert-HarnessRoot $Root
. "$PSScriptRoot\IntegrationRunContext.ps1"
$activeRun=Get-ValidatedIntegrationRunContext $Root
if(!$activeRun){throw 'Harness phases must be dispatched by an active integration run owner. Use Invoke-IntegrationSuite.ps1 to start an independent run.'}
$manifest = Get-Content "$repo\runtime\artifacts.json" -Raw | ConvertFrom-Json
$spatialLock = Get-Content (Join-Path "$repo\runtime" $manifest.dependencyLock) -Raw | ConvertFrom-Json
$ocflLock = Get-Content (Join-Path "$repo\runtime" $manifest.ocflDependencyLock) -Raw | ConvertFrom-Json
$formatLock = Get-Content (Join-Path "$repo\runtime" $manifest.formatDependencyLock) -Raw | ConvertFrom-Json
$run = Join-Path $Root "runs\$RunId"
$evidence = Join-Path $run ('logs\'+$Phase+'-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))
if($Phase -in @('Install','Start','Stop','Kill','Reset') -and $RunId -ne 'test-host') {throw 'Only the single test-host Neo4j installation is authorized'}
$installation = Join-Path $run 'neo4j'
$savedEnvironment=@{}
foreach($key in @('JAVA_HOME','MAVEN_USER_HOME','MAVEN_OPTS','NEO4J_HOME','NEO4J_CONF','TEMP','TMP','DEPOSIT_TEST_ROOT')) {$savedEnvironment[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
$credentialTarget = "Deposit/IntegrationHarness/$RunId"
$utf8 = New-Object Text.UTF8Encoding($false)


















$harnessContext=[pscustomobject]@{Root=$Root;Repo=$repo;Run=$run;Evidence=$evidence;Manifest=$manifest;SpatialLock=$spatialLock;OcflLock=$ocflLock;FormatLock=$formatLock;RunId=$RunId;Port=$Port;ReleasePackage=$ReleasePackage;BoltUri=$BoltUri;DiagnosticBuild=[bool]$DiagnosticBuild;WithoutProbe=[bool]$WithoutProbe;StartupTimeoutSeconds=$StartupTimeoutSeconds;Installation=$installation;CredentialTarget=$credentialTarget;Utf8=$utf8}
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
    $env:DEPOSIT_TEST_ROOT = $Root
    $env:TEMP="$run\tmp"; $env:TMP=$env:TEMP
    $env:MAVEN_OPTS=($env:MAVEN_OPTS+' -Djava.io.tmpdir='+$env:TEMP).Trim()
    switch ($Phase) {
        Provision {Invoke-Provision $harnessContext $PSScriptRoot}
        Build {Invoke-Build $harnessContext $PSScriptRoot}
        Package {Invoke-Package $harnessContext $PSScriptRoot}
        Install {Invoke-Install $harnessContext $PSScriptRoot}
        Start {Invoke-Start $harnessContext $PSScriptRoot}
        Stop {Invoke-Stop $harnessContext $PSScriptRoot $false}
        Kill {Invoke-Stop $harnessContext $PSScriptRoot $true}
        Reset {Invoke-Reset $harnessContext $PSScriptRoot}
    }
} finally {
    try {$phaseLock.Dispose()}
    finally {foreach($key in $savedEnvironment.Keys) {[Environment]::SetEnvironmentVariable($key,$savedEnvironment[$key],'Process')}}
}
