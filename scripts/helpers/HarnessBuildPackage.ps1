function Invoke-Build($Context,[string]$ScriptsDir) {$Root=$Context.Root; $repo=$Context.Repo; $run=$Context.Run; $evidence=$Context.Evidence; $manifest=$Context.Manifest; $spatialLock=$Context.SpatialLock; $ocflLock=$Context.OcflLock; $formatLock=$Context.FormatLock; $RunId=$Context.RunId; $Port=$Context.Port; $ReleasePackage=$Context.ReleasePackage; $BoltUri=$Context.BoltUri; $DiagnosticBuild=$Context.DiagnosticBuild; $WithoutProbe=$Context.WithoutProbe; $StartupTimeoutSeconds=$Context.StartupTimeoutSeconds; $installation=$Context.Installation; $credentialTarget=$Context.CredentialTarget; $utf8=$Context.Utf8;
    Test-Artifacts
    $null=Get-ToolDirectory $manifest 'temurin' $Root
    Push-Location $repo
    try {
        $ErrorActionPreference='Continue'
        & .\mvnw.cmd --batch-mode --no-transfer-progress "-Dmaven.repo.local=$Root\maven-repository" clean package -DskipTests *> "$evidence\build.log"
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed; see $evidence\build.log" }
    } finally { $ErrorActionPreference='Stop'; Pop-Location }
}

function Invoke-Package($Context,[string]$ScriptsDir) {$Root=$Context.Root; $repo=$Context.Repo; $run=$Context.Run; $evidence=$Context.Evidence; $manifest=$Context.Manifest; $spatialLock=$Context.SpatialLock; $ocflLock=$Context.OcflLock; $formatLock=$Context.FormatLock; $RunId=$Context.RunId; $Port=$Context.Port; $ReleasePackage=$Context.ReleasePackage; $BoltUri=$Context.BoltUri; $DiagnosticBuild=$Context.DiagnosticBuild; $WithoutProbe=$Context.WithoutProbe; $StartupTimeoutSeconds=$Context.StartupTimeoutSeconds; $installation=$Context.Installation; $credentialTarget=$Context.CredentialTarget; $utf8=$Context.Utf8;
    Test-Artifacts
    Invoke-ProvisionLicenses
    $plugin=Join-Path $Root 'build\deposit-plugin\target\deposit-plugin-0.0.0-dev.jar'
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
    . (Join-Path $ScriptsDir 'ReleaseNotices.ps1')
    if(!(Write-DepositReleaseNotices $destination $repo)){throw 'Release component notice verification failed'}
    Copy-Item -Path "$repo\runtime\distribution\neo4j.conf","$repo\runtime\distribution\deposit.properties" -Destination "$destination\conf"
    Copy-Item -LiteralPath "$repo\runtime\distribution\deposit.bat","$repo\runtime\distribution\deposit.ps1" -Destination $destination
    New-Item -ItemType Directory -Force "$destination\helpers" | Out-Null
    Copy-Item -LiteralPath "$ScriptsDir\Credentials.ps1","$ScriptsDir\Configure-DepositDownloads.ps1","$ScriptsDir\Stop-Console.ps1","$ScriptsDir\Request-DepositStop.ps1","$repo\runtime\distribution\helpers\deposit-worker.ps1","$repo\runtime\distribution\helpers\LauncherPathHelpers.ps1","$repo\runtime\distribution\helpers\LauncherState.ps1","$repo\runtime\distribution\helpers\LauncherSetup.ps1","$repo\runtime\distribution\helpers\LauncherService.ps1","$repo\runtime\distribution\helpers\LauncherLocal.ps1" -Destination "$destination\helpers"
    # Keep the hub as the sole source; extract its operations section into the release package.
    $hubServerDoc=Join-Path (Split-Path $repo -Parent) 'deposit-hub\deposit-server.md'
    if(!(Test-Path -LiteralPath $hubServerDoc -PathType Leaf)) {throw 'Deposit Hub server documentation is required to package OPERATIONS.md'}
    $hubServerText=[IO.File]::ReadAllText($hubServerDoc)
    $operationsMatch=[regex]::Match($hubServerText,'(?ms)^## Operations[ \t]*\r?\n(.*?)(?=^## Runtime[ \t]*\r?$)')
    if(!$operationsMatch.Success) {throw 'Windows operations section is missing from Deposit Hub server documentation'}
    $operationsText="# Deposit Server on Windows`r`n`r`n"+$operationsMatch.Groups[1].Value.Trim()+"`r`n"
    [IO.File]::WriteAllText((Join-Path $destination 'OPERATIONS.md'),$operationsText,(New-Object Text.UTF8Encoding($false)))
    . (Join-Path $ScriptsDir 'ReleaseManifest.ps1')
    $expectedPluginPaths=New-Object 'System.Collections.Generic.List[string]'
    $expectedPluginPaths.Add('plugins/'+[IO.Path]::GetFileName($plugin))
    foreach($id in @('apoc','spatial','bcrypt-provider')){$artifact=$manifest.artifacts|Where-Object id -eq $id;$expectedPluginPaths.Add('plugins/'+$artifact.file)}
    foreach($lock in @($spatialLock,$ocflLock,$formatLock)){foreach($artifact in @($lock.dependencies|Where-Object ship)){$expectedPluginPaths.Add('plugins/'+$artifact.file)}}
    Get-ReleaseManifestData $destination $expectedPluginPaths.ToArray()
    if(!(Test-DepositReleasePackage $destination)){throw 'Generated release package failed manifest verification'}
    $archivePath=Join-Path $Root 'build\deposit-server-0.0.0-dev.zip'
    New-DeterministicZip $destination $archivePath
    $archiveCheck=Join-Path $run ('tmp\release-verify-'+[Guid]::NewGuid().ToString('N'))
    try {
        [IO.Compression.ZipFile]::ExtractToDirectory($archivePath,$archiveCheck)
        if(!(Test-DepositReleasePackage $archiveCheck)){throw 'Release archive failed manifest verification'}
    } finally {if(Test-Path -LiteralPath $archiveCheck){Remove-Item -LiteralPath $archiveCheck -Recurse -Force}}
}
