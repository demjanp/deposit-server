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
#>
[CmdletBinding()]
param(
    [ValidateSet('Run','Stop','Status')][string]$Action = 'Run',
    [ValidateRange(1024,65535)][int]$Port = 7687,
    [switch]$SetupOnly
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

function Get-ServerArtifact($Artifact, [string]$Directory) {
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    $file = Join-Path $Directory $Artifact.file
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) {
        $partial = $file + '.' + [guid]::NewGuid().ToString('N') + '.partial'
        try {
            Write-Host "Downloading $($Artifact.file)"
            Invoke-WebRequest -UseBasicParsing -Uri $Artifact.url -OutFile $partial
            if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ine $Artifact.sha256) {
                throw "Download checksum mismatch: $($Artifact.file)"
            }
            Move-Item -LiteralPath $partial -Destination $file
        } finally { Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue }
    }
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ine $Artifact.sha256) {
        throw "Cached artifact checksum mismatch: $file. Remove this file and retry."
    }
    return $file
}

function Initialize-ServerTools {
    $tools = Join-Path $root 'tools'
    New-Item -ItemType Directory -Path $tools -Force | Out-Null
    foreach ($id in @('temurin','neo4j','maven')) {
        $artifact = $manifest.artifacts | Where-Object id -eq $id
        $relative = switch ($id) {
            temurin { $manifest.javaHome }
            neo4j { $manifest.neo4jHome }
            maven { 'tools/' + ($artifact.file -replace '-bin\.zip$','') }
        }
        $toolHome = Join-Path $root $relative
        $required = switch ($id) {
            temurin { @('bin\java.exe','bin\javac.exe') }
            neo4j { @('bin\neo4j.bat','bin\neo4j-admin.bat') }
            maven { @('bin\mvn.cmd') }
        }
        if (@($required | Where-Object { !(Test-Path -LiteralPath (Join-Path $toolHome $_) -PathType Leaf) }).Count) {
            $archive = Get-ServerArtifact $artifact (Join-Path $root 'cache')
            Expand-Archive -LiteralPath $archive -DestinationPath $tools -Force
        }
        foreach ($name in $required) {
            if (!(Test-Path -LiteralPath (Join-Path $toolHome $name) -PathType Leaf)) {
                throw "Runtime extraction did not yield $id file $name"
            }
        }
    }
}

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

$saved = @{}
foreach ($key in @('JAVA_HOME','NEO4J_HOME','NEO4J_CONF','PATH','MAVEN_USER_HOME','MAVEN_OPTS','TEMP','TMP')) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key,'Process')
}
$lock = $null
try {
    New-Item -ItemType Directory -Path $localRoot -Force | Out-Null
    try { $lock = [IO.File]::Open((Join-Path $localRoot 'launcher.lock'),'OpenOrCreate','ReadWrite','None') }
    catch { throw 'Another Run_Server.ps1 invocation is using this installation.' }

    if (Test-Path -LiteralPath $statePath -PathType Leaf) {
        # Preserve the installed version across checkout updates, including the
        # ability to stop an older server after repository pins have changed.
        $manifest = Get-Content -LiteralPath (Join-Path $package 'artifacts.json') -Raw | ConvertFrom-Json
    }
    if ($Action -ne 'Run') {
        if (!(Test-Path -LiteralPath $statePath -PathType Leaf)) { throw 'No local server is configured. Run Run_Server.ps1 first.' }
    } else {
        Initialize-ServerTools
    }

    if (!(Test-Path -LiteralPath $statePath -PathType Leaf)) {
        # No server has ever been started until setup has completed. Preserve any
        # interrupted setup for inspection rather than deleting an unknown tree.
        if (Test-Path -LiteralPath $installation) {
            throw "Incomplete setup at $installation. Preserve or move it aside before retrying; no existing files were removed."
        }
        $env:JAVA_HOME = Join-Path $root $manifest.javaHome
        $env:MAVEN_USER_HOME = Join-Path $root 'maven-user'
        $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
        $env:TEMP = Join-Path $localRoot 'tmp'
        $env:TMP = $env:TEMP
        New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null

        $jars = @()
        foreach ($id in @('apoc','spatial','bcrypt-provider')) {
            $jars += Get-ServerArtifact ($manifest.artifacts | Where-Object id -eq $id) (Join-Path $root 'cache')
        }
        foreach ($dependency in @(
            @{ File=$manifest.dependencyLock; Pom='spatial-dependencies.pom.xml'; Cache='spatial-dependencies' },
            @{ File=$manifest.ocflDependencyLock; Pom='ocfl-dependencies.pom.xml'; Cache='ocfl-dependencies' },
            @{ File=$manifest.formatDependencyLock; Pom='format-dependencies.pom.xml'; Cache='format-dependencies' }
        )) {
            $dependencyLock = Get-Content -LiteralPath (Join-Path "$repo\runtime" $dependency.File) -Raw | ConvertFrom-Json
            if ((Get-FileHash -LiteralPath (Join-Path "$repo\runtime" $dependency.Pom) -Algorithm SHA256).Hash -ine $dependencyLock.sourcePomSha256) {
                throw "Dependency lock does not match $($dependency.Pom)"
            }
            foreach ($artifact in $dependencyLock.dependencies) {
                if ($artifact.ship) {
                    $jars += Get-ServerArtifact $artifact (Join-Path "$root\cache" $dependency.Cache)
                } elseif ($dependency.Cache -ne 'spatial-dependencies') {
                    # Spatial's approved lock deliberately uses newer Neo4j
                    # replacements; only the other locks require identical JARs.
                    $provided = Join-Path (Join-Path $root $manifest.neo4jHome) "lib\$($artifact.file)"
                    if ((Get-FileHash -LiteralPath $provided -Algorithm SHA256).Hash -ine $artifact.sha256) {
                        throw "Pinned Neo4j dependency differs: $($artifact.file)"
                    }
                }
            }
        }
        $mavenArtifact = $manifest.artifacts | Where-Object id -eq 'maven'
        $maven = Join-Path $root ('tools/' + ($mavenArtifact.file -replace '-bin\.zip$','') + '/bin/mvn.cmd')
        $spatial = $manifest.artifacts | Where-Object id -eq 'spatial'
        Write-Host 'Building Deposit server (first setup only)...'
        Push-Location $repo
        try {
            & $maven --batch-mode --no-transfer-progress "-Dmaven.repo.local=$root\maven-repository" "-Ddeposit.test.root=$localRoot" "-Dspatial.jar=$root\cache\$($spatial.file)" -pl plugin -am package -DskipTests
            if ($LASTEXITCODE -ne 0) { throw "Deposit build failed with code $LASTEXITCODE" }
        } finally { Pop-Location }
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

        $password = Read-Host 'Choose the Neo4j administrator password (at least 8 characters)' -AsSecureString
        $passwordPointer = [IntPtr]::Zero
        $argumentFile = Join-Path $localRoot 'private\initial-password.args'
        try {
            $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
            $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
            # Picocli @files split on whitespace and interpret quotes/comments.
            if ($plainPassword.Length -lt 8 -or $plainPassword -match '[\s"''#\\]') {
                throw 'Use at least 8 characters without whitespace, quotes, # or backslash.'
            }
            . (Join-Path $PSScriptRoot 'Credentials.ps1')
            New-Item -ItemType Directory -Path (Split-Path $argumentFile -Parent) -Force | Out-Null
            Set-OwnerOnlyDirectory (Split-Path $argumentFile -Parent)
            [IO.File]::WriteAllText($argumentFile,"dbms`nset-initial-password`n$plainPassword`n",(New-Object Text.UTF8Encoding($false)))
            Copy-Item -LiteralPath (Join-Path $root $manifest.neo4jHome) -Destination $installation -Recurse
            $env:NEO4J_HOME = $installation
            $env:NEO4J_CONF = Join-Path $installation 'conf'
            & (Join-Path $installation 'bin\neo4j-admin.bat') "@$argumentFile"
            if ($LASTEXITCODE -ne 0) { throw 'Setting the initial Neo4j administrator password failed' }
        } finally {
            Remove-Item -LiteralPath $argumentFile -Force -ErrorAction SilentlyContinue
            $plainPassword = $null
            if ($passwordPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer) }
            if ($password) { $password.Dispose() }
        }
        Invoke-ServerLauncher 'setup' @($installation,$env:JAVA_HOME,(Join-Path $localRoot 'resources'),(Join-Path $localRoot 'exports'),[string]$Port)
    }

    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($state.mode -cne 'local' -or $state.neo4jHome -ine $installation -or $state.distributionHome -ine $package -or $state.javaHome -ine (Join-Path $root $manifest.javaHome) -or $state.neo4jVersion -cne $manifest.neo4jVersion -or $state.dataRoot -ine (Join-Path $localRoot 'resources') -or $state.exportRoot -ine (Join-Path $localRoot 'exports')) {
        throw 'Local installation identity or pinned runtime changed; refusing to use it.'
    }
    if ($Action -eq 'Run') {
        if (!$SetupOnly) { Invoke-ServerLauncher 'start' }
        Write-Host "Installation: $installation"
        Write-Host "Logs: $installation\logs"
        Write-Host "Stop with: & '$PSCommandPath' -Action Stop"
    } else {
        & powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File (Join-Path $package 'deposit.ps1') ($Action.ToLowerInvariant())
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
} finally {
    if ($lock) { $lock.Dispose() }
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
}
