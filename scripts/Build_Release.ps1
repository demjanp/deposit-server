[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$WorkRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$runtime = Join-Path $repo 'runtime'
. (Join-Path $PSScriptRoot 'DepositBuildMetadata.ps1')
. (Join-Path $PSScriptRoot 'ServerBuildInputs.ps1')

function Read-ReleaseGit {
    $result = & git -C $repo @args
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read release Git metadata.' }
    return $result
}

if ((Read-ReleaseGit rev-parse --is-shallow-repository) -ne 'false' -or
    (Read-ReleaseGit status --porcelain --untracked-files=all)) {
    throw 'Build the release from a clean, full Git checkout.'
}
$tags = @(Read-ReleaseGit tag --points-at HEAD | Where-Object { $_ -match '^\d+\.\d+\.\d+$' })
if ($tags.Count -ne 1) { throw 'HEAD must have exactly one stable version tag (X.Y.Z).' }
$version = $tags[0]
$root = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($WorkRoot)
$archive = Join-Path $root "deposit-server-$version.zip"
if (Test-Path -LiteralPath $archive) { throw "Release ZIP already exists: $archive" }

$manifest = Get-Content -LiteralPath (Join-Path $runtime 'artifacts.json') -Raw | ConvertFrom-Json
$mavenArtifact = $manifest.artifacts | Where-Object id -eq 'maven'
$spatial = $manifest.artifacts | Where-Object id -eq 'spatial'
$cache = Join-Path $root 'cache'
Initialize-ServerTools $manifest $root
$dependencies = @(Get-ServerPluginDependencies $manifest $root $repo)
$sources = Get-ServerArtifact ($manifest.artifacts | Where-Object id -eq 'spatial-sources') $cache

$buildRoot = Join-Path $root ('work-' + [guid]::NewGuid().ToString('N'))
$maven = Join-Path $root ('tools/' + ($mavenArtifact.file -replace '-bin\.zip$','') + '/bin/mvn.cmd')
$previousJava = $env:JAVA_HOME
Push-Location $repo
try {
    $env:JAVA_HOME = Join-Path $root $manifest.javaHome
    & $maven --batch-mode --no-transfer-progress "-Dmaven.repo.local=$root\maven-repository" "-Ddeposit.test.root=$buildRoot" "-Dspatial.jar=$cache\$($spatial.file)" -pl plugin -am package -Dmaven.test.skip=true
    if ($LASTEXITCODE -ne 0) { throw "Maven packaging failed with exit code $LASTEXITCODE." }
} finally {
    $env:JAVA_HOME = $previousJava
    Pop-Location
}

$metadata = Get-DepositBuildMetadata $repo $buildRoot
$plugin = Join-Path $buildRoot "build\deposit-plugin\target\$($metadata.PluginFile)"
if ($metadata.Version -cne $version -or (Get-DepositJarVersion $plugin) -cne $version) {
    throw 'Git, Maven, and the packaged plugin must report the same release version.'
}

$package = Join-Path $buildRoot 'distribution'
$distribution = Join-Path $runtime 'distribution'
foreach ($directory in @('conf', 'helpers', 'plugins', 'sources')) {
    New-Item -ItemType Directory -Path (Join-Path $package $directory) -Force | Out-Null
}
Copy-Item -LiteralPath "$distribution\deposit.ps1","$distribution\deposit.bat","$runtime\artifacts.json","$repo\LICENSE","$repo\README.md","$repo\RELEASE_NOTES.md" -Destination $package
Copy-Item -LiteralPath "$distribution\neo4j.conf","$distribution\deposit.properties" -Destination "$package\conf"
Copy-Item -Path "$distribution\helpers\*.ps1" -Destination "$package\helpers"
foreach ($helper in @('Credentials.ps1','Configure-DepositDownloads.ps1','Stop-Console.ps1','Request-DepositStop.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $helper) -Destination "$package\helpers"
}
Copy-Item -LiteralPath (@($plugin) + $dependencies) -Destination "$package\plugins"
Copy-Item -LiteralPath $sources -Destination "$package\sources"
Copy-Item -LiteralPath "$repo\doc","$runtime\notices" -Destination $package -Recurse

$noticeLock = Get-Content -LiteralPath "$runtime\release-notices-lock.json" -Raw | ConvertFrom-Json
foreach ($component in $noticeLock.components) {
    foreach ($notice in $component.supplemental) {
        $path = $notice.path -replace '^runtime/notices/', 'notices/'
        if ((Get-FileHash -LiteralPath (Join-Path $package $path) -Algorithm SHA256).Hash -ine $notice.sha256) {
            throw "Packaged license notice differs from its pin: $path"
        }
    }
}

$partial = Join-Path $buildRoot $metadata.ArchiveFile
[IO.Compression.ZipFile]::CreateFromDirectory($package, $partial)
[IO.File]::Move($partial, $archive)
Write-Host "Built $archive"
