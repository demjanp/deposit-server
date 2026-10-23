[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Unit','Integration')][string]$Kind,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_#+,*-]+$')][string]$Test,
    [string]$LogPath,
    [ValidateRange(1024,65535)][int]$Port = 17770,
    [ValidateSet('seed','verify')][string]$TestMode = 'seed',
    [ValidateSet('incompatible-spatial','missing-spatial','invalid-deposit-config')][string]$FailureMode,
    [string]$CrashPoint,
    [string]$Root = (Join-Path $PSScriptRoot '..\..\test_harness')
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot\HarnessPaths.ps1"
. "$PSScriptRoot\DepositBuildMetadata.ps1"
$Root = [IO.Path]::GetFullPath($Root).TrimEnd('\')
Assert-HarnessRoot $Root
. "$PSScriptRoot\IntegrationRunContext.ps1"
$activeRun=Get-ValidatedIntegrationRunContext $Root
if(!$activeRun) {
    $suiteParameters=@{SelectedKind=$Kind;SelectedTest=$Test;Root=$Root;Port=$Port}
    if($LogPath){$suiteParameters.SelectedLogPath=[IO.Path]::GetFullPath($LogPath)}
    & (Join-Path $PSScriptRoot 'Invoke-IntegrationSuite.ps1') @suiteParameters
    return
}
if (!$LogPath) {
    $logDirectory = Join-Path $Root 'logs'
    New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
    $LogPath = Join-Path $logDirectory ((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ') + '-' + $Kind.ToLowerInvariant() + '.log')
}
$LogPath = [IO.Path]::GetFullPath($LogPath)
Assert-HarnessPlainPath $LogPath
New-Item -ItemType Directory -Force -Path (Split-Path $LogPath -Parent) | Out-Null

$spatialJar = Join-Path $Root 'cache\neo4j-spatial-server-plugin-2025.10.0.jar'
$arguments = @('--batch-mode','--no-transfer-progress',"-Dmaven.repo.local=$Root\maven-repository","-Ddeposit.test.root=$Root","-Ddeposit.server.root=$repo","-Dspatial.jar=$spatialJar")
if ($Kind -eq 'Unit') {
    $arguments += @('-pl','plugin',"-Dtest=$Test",'test')
} else {
    $arguments += @('-pl','integration-tests','-Pintegration',"-Dtest=$Test",'test')
}

$savedEnvironment = @{}
try {
if ($Kind -eq 'Unit') {
    $manifest = Get-Content (Join-Path $repo 'runtime\artifacts.json') -Raw | ConvertFrom-Json
    foreach ($name in @('JAVA_HOME','MAVEN_USER_HOME','TEMP','TMP','DEPOSIT_TEST_ROOT')) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
    $env:JAVA_HOME = Join-Path $Root $manifest.javaHome
    $env:MAVEN_USER_HOME = Join-Path $Root 'maven-user'
    $env:TEMP = Join-Path $Root 'tmp'; $env:TMP=$env:TEMP; $env:DEPOSIT_TEST_ROOT=$Root
}
if ($Kind -eq 'Integration') {
    . "$PSScriptRoot\IntegrationHarnessSecrets.ps1"
    $manifest = Get-Content (Join-Path $repo 'runtime\artifacts.json') -Raw | ConvertFrom-Json
    $savedEnvironment = @{}
    foreach ($name in @('JAVA_HOME','MAVEN_USER_HOME','TEMP','TMP','DEPOSIT_TEST_ROOT','DEPOSIT_TEST_URI','DEPOSIT_TEST_PASSWORD','DEPOSIT_TEST_HTTP_BASE','DEPOSIT_TEST_NEO4J_VERSION','DEPOSIT_TEST_BASELINE_MAP','DEPOSIT_TEST_MODE','DEPOSIT_TEST_EXPECTED_FAILURE','DEPOSIT_TEST_RESOURCE_CRASH_POINT','DEPOSIT_TEST_HISTORY_CRASH_POINT','DEPOSIT_EXPECTED_VERSION','SE_CACHE_PATH','SE_AVOID_STATS','SE_BROWSER_PATH','SE_GECKODRIVER')) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
    }
    $run = Join-Path $Root 'runs\test-host'
    $downloadConfig = Get-Content (Join-Path $run 'neo4j\conf\deposit.properties') -Raw
    $downloadBase = ([regex]::Match($downloadConfig,'(?m)^download\.publicBaseUrl=(.+)$')).Groups[1].Value.Trim()
    if (!$downloadBase) { throw 'Installed download base URL is missing; start the test server first.' }
    $env:JAVA_HOME = Join-Path $Root $manifest.javaHome
    $env:MAVEN_USER_HOME = Join-Path $Root 'maven-user'
    $env:TEMP = Join-Path $Root 'tmp'; $env:TMP=$env:TEMP; $env:DEPOSIT_TEST_ROOT=$Root
    $env:DEPOSIT_TEST_URI = "bolt://127.0.0.1:$Port"
    $env:DEPOSIT_EXPECTED_VERSION=(Get-DepositBuildMetadata $repo $Root).Version
    $env:DEPOSIT_TEST_PASSWORD = [DepositHarnessSecretStore]::Read('Deposit/IntegrationHarness/test-host')
    $env:DEPOSIT_TEST_HTTP_BASE = $downloadBase
    $env:DEPOSIT_TEST_NEO4J_VERSION = $manifest.neo4jVersion
    $env:DEPOSIT_TEST_BASELINE_MAP = Join-Path $run 'fixture-uuid-baseline.tsv'
    $env:DEPOSIT_TEST_MODE = $TestMode
    $toolManifest = Get-Content (Join-Path $repo 'runtime\integration-tools.json') -Raw | ConvertFrom-Json
    $env:SE_BROWSER_PATH = Join-Path $Root $toolManifest.firefoxExe
    $env:SE_GECKODRIVER = Join-Path $Root $toolManifest.geckodriverExe
    if (!(Test-Path -LiteralPath $env:SE_BROWSER_PATH -PathType Leaf) -or !(Test-Path -LiteralPath $env:SE_GECKODRIVER -PathType Leaf)) { throw 'Pinned Firefox/geckodriver tools are missing; run Invoke-IntegrationSuite.ps1 -SetupOnly first.' }
    if ($FailureMode) { $env:DEPOSIT_TEST_EXPECTED_FAILURE = $FailureMode } else { Remove-Item Env:\DEPOSIT_TEST_EXPECTED_FAILURE -ErrorAction SilentlyContinue }
    if ($CrashPoint) { $env:DEPOSIT_TEST_RESOURCE_CRASH_POINT = $CrashPoint; $env:DEPOSIT_TEST_HISTORY_CRASH_POINT = $CrashPoint }
    else { Remove-Item Env:\DEPOSIT_TEST_RESOURCE_CRASH_POINT,Env:\DEPOSIT_TEST_HISTORY_CRASH_POINT -ErrorAction SilentlyContinue }
    $env:SE_CACHE_PATH = Join-Path $Root 'cache\selenium'
    $env:SE_AVOID_STATS = 'true'
}
} catch {
    [IO.File]::WriteAllText($LogPath,$_.Exception.Message + [Environment]::NewLine,(New-Object Text.UTF8Encoding($false)))
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process') }
    Write-Output "FAIL: $Kind tests ($Test)"
    Write-Output "Log: $LogPath"
    exit 1
}

Push-Location $repo
$exitCode = 1
try {
    $ErrorActionPreference = 'Continue'
    & .\mvnw.cmd @arguments *> $LogPath
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = 'Stop'
    Pop-Location
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process')
    }
}

if ($exitCode -eq 0) {
    Write-Output "PASS: $Kind tests ($Test)"
    Write-Output "Log: $LogPath"
    exit 0
}

Write-Output "FAIL: $Kind tests ($Test)"
Write-Output "Log: $LogPath"
exit $exitCode
