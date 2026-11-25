<#
.SYNOPSIS
Build and run a persistent local Deposit server without installing a service.
.DESCRIPTION
Uses pinned tools and caches in ..\..\test_harness relative to this script.
The independent installation and database live in test_harness\runs\local-server.
The first run prompts for a Neo4j administrator password. Subsequent runs reuse
the installation, configuration and data; they do not rebuild or upgrade it.
Use -Action Stop for Deposit's graceful shutdown, or -Action Status to inspect it.
.PARAMETER Port
Loopback Bolt port for the first setup (default 7687). Existing configuration wins.
.PARAMETER SetupOnly
Prepare the installation without starting it.
.PARAMETER DpApiCredentialFile
An owner-only DPAPI-encoded UTF-16LE password record used only for first setup.
It is decrypted through an in-process SecureString; interactive setup is the
default. The matching acceptance helper retains the record for test logins.
#>
[CmdletBinding()]
param(
    [ValidateSet('Run','Stop','Status')][string]$Action = 'Run',
    [ValidateRange(1024,65535)][int]$Port = 7687,
    [switch]$SetupOnly,
    [ValidateScript({[string]::IsNullOrWhiteSpace($_) -or (Test-Path -LiteralPath $_ -PathType Leaf)})][string]$DpApiCredentialFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\test_harness'))
$localRoot = Join-Path $root 'runs\local-server'
$package = Join-Path $localRoot 'distribution'
$installation = Join-Path $localRoot 'neo4j'
$statePath = Join-Path $package 'deposit.install.json'
$manifest = Get-Content -LiteralPath (Join-Path $repo 'runtime\artifacts.json') -Raw | ConvertFrom-Json
. (Join-Path $PSScriptRoot 'DepositBuildMetadata.ps1')

. (Join-Path $PSScriptRoot 'ServerBuildInputs.ps1')

function Invoke-ServerLauncher([string]$Command, [string[]]$Values = @()) {
    & powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File (Join-Path $package 'deposit.ps1') $Command @Values
    if ($LASTEXITCODE -ne 0) { throw "Deposit $Command exited with code $LASTEXITCODE" }
}

function Set-ServerPluginFiles([string]$Directory, [string[]]$Files) {
    # Called only before any installation exists. A failed password prompt may
    # have left an earlier staging set with different versioned filenames.
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    Get-ChildItem -LiteralPath $Directory -Filter '*.jar' -File | ForEach-Object {
        Remove-Item -LiteralPath $_.FullName -Force
    }
    foreach ($file in $Files) { Copy-Item -LiteralPath $file -Destination $Directory -Force }
}

$lock = $null
try {
    New-Item -ItemType Directory -Path $localRoot -Force | Out-Null
    try { $lock = [IO.File]::Open((Join-Path $localRoot 'launcher.lock'),'OpenOrCreate','ReadWrite','None') }
    catch { throw 'Another Run_Server.bat invocation is using this installation.' }

    if (Test-Path -LiteralPath $statePath -PathType Leaf) {
        # Preserve the installed version across checkout updates, including the
        # ability to stop an older server after repository pins have changed.
        $manifest = Get-Content -LiteralPath (Join-Path $package 'artifacts.json') -Raw | ConvertFrom-Json
    }
    if ($Action -ne 'Run') {
        if (!(Test-Path -LiteralPath $statePath -PathType Leaf)) { throw 'No local server is configured. Run Run_Server.bat first.' }
    } else {
        Initialize-ServerTools $manifest $root
    }

    if (!(Test-Path -LiteralPath $statePath -PathType Leaf)) {
        # No server has ever been started until setup has completed. Preserve any
        # interrupted setup for inspection rather than deleting an unknown tree.
        if (Test-Path -LiteralPath $installation) {
            throw "Incomplete setup at $installation. Preserve or move it aside before retrying; no existing files were removed."
        }
        $javaHome = Join-Path $root $manifest.javaHome

        $jars = Get-ServerPluginDependencies $manifest $root $repo
        $mavenArtifact = $manifest.artifacts | Where-Object id -eq 'maven'
        $maven = Join-Path $root ('tools/' + ($mavenArtifact.file -replace '-bin\.zip$','') + '/bin/mvn.cmd')
        $spatial = $manifest.artifacts | Where-Object id -eq 'spatial'
        Write-Host 'Building Deposit server (first setup only)...'
        $previousJava = $env:JAVA_HOME
        Push-Location $repo
        try {
            $env:JAVA_HOME = $javaHome
            & $maven --batch-mode --no-transfer-progress "-Dmaven.repo.local=$root\maven-repository" "-Ddeposit.test.root=$localRoot" "-Dspatial.jar=$root\cache\$($spatial.file)" -pl plugin -am package -DskipTests
            if ($LASTEXITCODE -ne 0) { throw "Deposit build failed with code $LASTEXITCODE" }
        } finally {
            $env:JAVA_HOME = $previousJava
            Pop-Location
        }
        $build = Get-DepositBuildMetadata $repo $localRoot
        $plugin = Join-Path $localRoot "build\deposit-plugin\target\$($build.PluginFile)"
        if ((Get-DepositJarVersion $plugin) -cne $build.Version) { throw 'Built plugin version differs from build metadata' }
        New-Item -ItemType Directory -Path "$package\plugins","$package\conf","$package\helpers" -Force | Out-Null
        Set-ServerPluginFiles "$package\plugins" (@($plugin) + $jars)
        Copy-Item -LiteralPath "$repo\runtime\artifacts.json","$repo\runtime\distribution\deposit.ps1","$repo\runtime\distribution\deposit.bat" -Destination $package -Force
        Copy-Item -LiteralPath "$repo\runtime\distribution\neo4j.conf","$repo\runtime\distribution\deposit.properties" -Destination "$package\conf" -Force
        Copy-Item -Path "$repo\runtime\distribution\helpers\*.ps1" -Destination "$package\helpers" -Force
        foreach ($name in @('Credentials.ps1','Configure-DepositDownloads.ps1','Stop-Console.ps1','Request-DepositStop.ps1')) {
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination "$package\helpers" -Force
        }

        $password = if ($PSBoundParameters.ContainsKey('DpApiCredentialFile')) {
            try {
                Get-Content -LiteralPath $DpApiCredentialFile -Raw | ConvertTo-SecureString -ErrorAction Stop
            } catch { throw 'Unable to decrypt the supplied test credential as the current Windows user' }
        } else { Read-Host 'Choose the Neo4j administrator password (at least 8 characters)' -AsSecureString }
        try {
            . (Join-Path $PSScriptRoot 'Credentials.ps1')
            Set-InitialNeo4jPassword $installation $javaHome $password (Join-Path $root $manifest.neo4jHome)
        } finally {
            $password.Dispose()
        }
        Invoke-ServerLauncher 'setup' @($installation,$javaHome,(Join-Path $localRoot 'resources'),(Join-Path $localRoot 'exports'),[string]$Port)
    }

    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($state.mode -cne 'local' -or $state.neo4jHome -ine $installation -or $state.distributionHome -ine $package -or $state.javaHome -ine (Join-Path $root $manifest.javaHome) -or $state.neo4jVersion -cne $manifest.neo4jVersion -or $state.dataRoot -ine (Join-Path $localRoot 'resources') -or $state.exportRoot -ine (Join-Path $localRoot 'exports')) {
        throw 'Local installation identity or pinned runtime changed; refusing to use it.'
    }
    if ($Action -eq 'Run') {
        if (!$SetupOnly) { Invoke-ServerLauncher 'start' }
        Write-Host "Installation: $installation"
        Write-Host "Logs: $installation\logs"
        Write-Host "Stop with: scripts\Run_Server.bat -Action Stop"
    } else {
        & powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File (Join-Path $package 'deposit.ps1') ($Action.ToLowerInvariant())
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
} finally {
    if ($lock) { $lock.Dispose() }
}
