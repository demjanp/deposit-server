[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Unit','Integration')][string]$Kind,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9_#+,*-]+$')][string]$Test,
    [string]$LogPath,
    [ValidateRange(1024,65535)][int]$Port = 17770,
    [ValidateSet('seed','verify')][string]$TestMode = 'seed',
    [ValidateSet('incompatible-spatial','missing-spatial','invalid-deposit-config')][string]$FailureMode,
    [string]$CrashPoint,
    [string]$Root = 'D:\data_processed\Deposit_v2\stage0'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$approvedRoot = [IO.Path]::GetFullPath('D:\data_processed\Deposit_v2\stage0').TrimEnd('\')
if ($Kind -eq 'Integration' -and ![string]::Equals([IO.Path]::GetFullPath($Root).TrimEnd('\'),$approvedRoot,[StringComparison]::OrdinalIgnoreCase)) {
    throw "Integration tests must use the installed Stage 0 inputs at $approvedRoot"
}
if (!$LogPath) {
    $logDirectory = Join-Path $env:TEMP 'DepositTests'
    New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
    $LogPath = Join-Path $logDirectory ((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ') + '-' + $Kind.ToLowerInvariant() + '.log')
}
$LogPath = [IO.Path]::GetFullPath($LogPath)
New-Item -ItemType Directory -Force -Path (Split-Path $LogPath -Parent) | Out-Null

$spatialJar = Join-Path $Root 'cache\neo4j-spatial-server-plugin-2025.10.0.jar'
$arguments = @('--batch-mode','--no-transfer-progress',"-Dmaven.repo.local=$Root\maven-repository","-Dstage0.root=$Root","-Dspatial.jar=$spatialJar")
if ($Kind -eq 'Unit') {
    $arguments += @('-pl','plugin',"-Dtest=$Test",'test')
} else {
    $arguments += @('-pl','integration-tests','-Pintegration',"-Dtest=$Test",'test')
}

$savedEnvironment = @{}
try {
if ($Kind -eq 'Unit') {
    $manifest = Get-Content (Join-Path $repo 'runtime\artifacts.json') -Raw | ConvertFrom-Json
    foreach ($name in @('JAVA_HOME','MAVEN_USER_HOME')) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
    $env:JAVA_HOME = Join-Path $Root $manifest.javaHome
    $env:MAVEN_USER_HOME = Join-Path $Root 'maven-user'
}
if ($Kind -eq 'Integration') {
    . "$PSScriptRoot\Credentials.ps1"
    $manifest = Get-Content (Join-Path $repo 'runtime\artifacts.json') -Raw | ConvertFrom-Json
    $savedEnvironment = @{}
    foreach ($name in @('JAVA_HOME','MAVEN_USER_HOME','STAGE0_URI','STAGE0_PASSWORD','STAGE0_HTTP_BASE','STAGE0_NEO4J_VERSION','STAGE0_BASELINE_MAP','STAGE0_TEST_MODE','STAGE0_EXPECTED_FAILURE','STAGE4_CRASH_POINT','STAGE5_CRASH_POINT','SE_CACHE_PATH','SE_AVOID_STATS')) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
    }
    $run = Join-Path $Root 'runs\stage0-host'
    $downloadConfig = Get-Content (Join-Path $run 'neo4j\conf\deposit.properties') -Raw
    $downloadBase = ([regex]::Match($downloadConfig,'(?m)^download\.publicBaseUrl=(.+)$')).Groups[1].Value.Trim()
    if (!$downloadBase) { throw 'Installed download base URL is missing; start the test server first.' }
    $env:JAVA_HOME = Join-Path $Root $manifest.javaHome
    $env:MAVEN_USER_HOME = Join-Path $Root 'maven-user'
    $env:STAGE0_URI = "bolt://127.0.0.1:$Port"
    $env:STAGE0_PASSWORD = [DepositCredentialStore]::Read('Deposit/Stage0/stage0-host')
    $env:STAGE0_HTTP_BASE = $downloadBase
    $env:STAGE0_NEO4J_VERSION = $manifest.neo4jVersion
    $env:STAGE0_BASELINE_MAP = Join-Path $run 'fixture-uuid-baseline.tsv'
    $env:STAGE0_TEST_MODE = $TestMode
    if ($FailureMode) { $env:STAGE0_EXPECTED_FAILURE = $FailureMode } else { Remove-Item Env:\STAGE0_EXPECTED_FAILURE -ErrorAction SilentlyContinue }
    if ($CrashPoint) { $env:STAGE4_CRASH_POINT = $CrashPoint; $env:STAGE5_CRASH_POINT = $CrashPoint }
    else { Remove-Item Env:\STAGE4_CRASH_POINT,Env:\STAGE5_CRASH_POINT -ErrorAction SilentlyContinue }
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
