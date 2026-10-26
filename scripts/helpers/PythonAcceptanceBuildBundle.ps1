Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-PythonAcceptanceBundlePlainPath([string]$Path) {
    $full=[IO.Path]::GetFullPath($Path)
    $harness=[IO.Path]::GetFullPath((Get-HarnessRoot)).TrimEnd('\')
    if($full -ine $harness -and !$full.StartsWith($harness+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Python acceptance build bundle path escaped the harness root.'}
    $current=$full
    while($current){if((Test-Path -LiteralPath $current) -and ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Python acceptance build bundle path contains a reparse point.'};$current=Split-Path $current -Parent}
}

function Get-PythonAcceptanceTargetVersion([string]$PythonRoot) {
    $manifestPath=Join-Path ([IO.Path]::GetFullPath($PythonRoot)) 'src\deposit\_procedure_manifest.json'
    if(!(Test-Path -LiteralPath $manifestPath -PathType Leaf)){throw 'Python procedure manifest is missing.'}
    try{$manifest=Get-Content -LiteralPath $manifestPath -Raw|ConvertFrom-Json -ErrorAction Stop}catch{throw 'Python procedure manifest is invalid.'}
    $version=[string]$manifest.support.target_server_version
    if($version -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$'){throw 'Python manifest has no exact supported Deposit version.'}
    return $version
}

function Get-PythonAcceptanceBundleTree([string]$BundleRoot) {
    $rootPath=[IO.Path]::GetFullPath($BundleRoot).TrimEnd('\')
    if(!(Test-Path -LiteralPath $rootPath -PathType Container)){throw 'Python acceptance build bundle is missing.'}
    Assert-PythonAcceptanceBundlePlainPath $rootPath
    $pending=New-Object 'System.Collections.Generic.Stack[string]';$pending.Push($rootPath)
    $files=New-Object 'System.Collections.Generic.List[string]'
    while($pending.Count){
        $directory=$pending.Pop()
        foreach($item in Get-ChildItem -LiteralPath $directory -Force){
            Assert-PythonAcceptanceBundlePlainPath $item.FullName
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Build bundle cannot contain reparse points.'}
            if($item.PSIsContainer){$pending.Push($item.FullName)}else{$files.Add($item.FullName)}
        }
    }
    return $files.ToArray()
}

function Get-PythonAcceptanceBundleRelativePath([string]$BundleRoot,[string]$Path) {
    $prefix=[IO.Path]::GetFullPath($BundleRoot).TrimEnd('\')+'\'
    $full=[IO.Path]::GetFullPath($Path)
    if(!$full.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Build bundle path escaped its root.'}
    return $full.Substring($prefix.Length).Replace('\','/')
}

function Test-PythonAcceptanceBuildBundle {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$BundleRoot,
        [Parameter(Mandatory=$true)][string]$ExpectedVersion,
        [Parameter(Mandatory=$true)][string]$RepositoryRoot
    )
    $bundle=[IO.Path]::GetFullPath($BundleRoot).TrimEnd('\')
    $harness=[IO.Path]::GetFullPath((Get-HarnessRoot)).TrimEnd('\')
    $allowedPrefix=$harness+'\cache\python-acceptance-build-bundles\'
    if(!$bundle.StartsWith($allowedPrefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Build bundle must be inside the harness-owned cache.'}
    $files=@(Get-PythonAcceptanceBundleTree $bundle)
    $manifestPath=Join-Path $bundle 'bundle.json'
    if($manifestPath -notin $files){throw 'Build bundle manifest is missing.'}
    try{$manifest=Get-Content -LiteralPath $manifestPath -Raw|ConvertFrom-Json -ErrorAction Stop}catch{throw 'Build bundle manifest is invalid.'}
    if($manifest.schemaVersion -ne 1 -or [string]$manifest.version -cne $ExpectedVersion){throw 'Build bundle version does not match the selected Python support version.'}
    $source=$manifest.source
    if(!$source -or [string]$source.commit -notmatch '^[0-9a-f]{40}$' -or [string]$source.tag -cne $ExpectedVersion -or [string]$source.status -cne 'clean' -or @($source.statusPorcelain).Count -ne 0){throw 'Build bundle source provenance is not a clean exact-version tag.'}
    $repo=[IO.Path]::GetFullPath($RepositoryRoot)
    $head=(& git -C $repo rev-parse --verify HEAD 2>$null).Trim()
    if($LASTEXITCODE -ne 0 -or $head -cne [string]$source.commit){throw 'Build bundle source commit does not match this server checkout.'}
    $tags=@(& git -C $repo tag --points-at $head 2>$null|Where-Object {$_ -ceq $ExpectedVersion})
    if($LASTEXITCODE -ne 0 -or $tags.Count -ne 1){throw 'Build bundle source commit is not the selected exact-version tag.'}
    foreach($tool in @('java','maven','neo4j','powershell')){if([string]::IsNullOrWhiteSpace([string]$manifest.toolVersions.$tool)){throw "Build bundle is missing the $tool tool version."}}
    $declared=@{}
    foreach($entry in @($manifest.files)){
        $relative=[string]$entry.path
        if([string]::IsNullOrWhiteSpace($relative) -or $relative.Contains('\') -or $relative.StartsWith('/') -or [IO.Path]::IsPathRooted($relative) -or $relative -match '^[A-Za-z]:' -or $relative.Split('/') -contains '..' -or $relative.Split('/') -contains '.' -or $relative -match ':'){throw 'Build bundle contains an unsafe manifest path.'}
        if($relative -ceq 'bundle.json' -or $declared.ContainsKey($relative.ToLowerInvariant())){throw 'Build bundle manifest has a duplicate or recursive entry.'}
        if([string]$entry.sha256 -notmatch '^[0-9a-f]{64}$' -or [long]$entry.size -lt 0){throw 'Build bundle manifest contains invalid file metadata.'}
        $declared[$relative.ToLowerInvariant()]=$entry
    }
    $actual=@($files|Where-Object {$_ -cne $manifestPath}|ForEach-Object {Get-PythonAcceptanceBundleRelativePath $bundle $_})
    if($actual.Count -ne $declared.Count -or @($actual|Where-Object {!$declared.ContainsKey($_.ToLowerInvariant())}).Count){throw 'Build bundle files differ from its SHA-256 manifest.'}
    foreach($relative in $actual){
        $path=Join-Path $bundle ($relative.Replace('/','\'))
        $entry=$declared[$relative.ToLowerInvariant()]
        $item=Get-Item -LiteralPath $path -Force
        if([long]$item.Length -ne [long]$entry.size -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne [string]$entry.sha256){throw "Build bundle file failed SHA-256 verification: $relative"}
    }
    $metadataRelative='build/deposit-build.properties'
    $pluginRelative="build/deposit-plugin/target/deposit-plugin-$ExpectedVersion.jar"
    $archiveRelative="release/deposit-server-$ExpectedVersion.zip"
    $classesRelative='build/deposit-integration-tests/target/classes/org/deposit/it/ReadinessClient.class'
    $needed=@($metadataRelative,$pluginRelative,$archiveRelative,$classesRelative)
    foreach($required in $needed){if(!$declared.ContainsKey($required.ToLowerInvariant())){throw "Build bundle is missing required output: $required"}}
    if(!@($actual|Where-Object {$_ -match '^build/deposit-integration-tests/target/dependency/[^/]+\.jar$'}).Count){throw 'Build bundle readiness dependencies are missing.'}
    # Use the same metadata contract as Package and Install, including probeFile.
    $metadata=Get-DepositBuildMetadata $repo $bundle
    if($metadata.Version -cne $ExpectedVersion){throw 'Generated build metadata differs from the selected exact version.'}
    $pluginPath=Join-Path $bundle ($pluginRelative.Replace('/','\'))
    if((Get-DepositJarVersion $pluginPath) -cne $ExpectedVersion){throw 'Build bundle plugin embedded version is incorrect.'}
    $pluginHash=(Get-FileHash -LiteralPath $pluginPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $archivePath=Join-Path $bundle ($archiveRelative.Replace('/','\'))
    $zip=[IO.Compression.ZipFile]::OpenRead($archivePath)
    try{
        foreach($entry in $zip.Entries){
            if($entry.FullName.StartsWith('/') -or $entry.FullName.Contains('\') -or $entry.FullName.Split('/') -contains '..' -or $entry.FullName.Split('/') -contains '.'){throw 'Release ZIP contains an unsafe path.'}
        }
        $pluginEntry=$zip.GetEntry("plugins/deposit-plugin-$ExpectedVersion.jar")
        if(!$pluginEntry){throw 'Release ZIP does not contain the expected plugin artifact.'}
        $stream=$pluginEntry.Open();$zipHasher=[Security.Cryptography.SHA256]::Create();try{$zipPluginHash=[BitConverter]::ToString($zipHasher.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}finally{$zipHasher.Dispose();$stream.Dispose()}
        if($zipPluginHash -cne $pluginHash){throw 'Release ZIP plugin differs from the separately bundled plugin artifact.'}
    }finally{$zip.Dispose()}
    return [pscustomobject]@{Root=$bundle;Version=$ExpectedVersion;ArchivePath=$archivePath;PluginPath=$pluginPath;MetadataPath=(Join-Path $bundle ($metadataRelative.Replace('/','\')));Files=$declared}
}

function Import-PythonAcceptanceBuildBundle {
    [CmdletBinding()]
    param([Parameter(Mandatory=$true)][string]$BundleRoot,[Parameter(Mandatory=$true)][string]$ExpectedVersion,[Parameter(Mandatory=$true)][string]$RepositoryRoot)
    $verified=Test-PythonAcceptanceBuildBundle -BundleRoot $BundleRoot -ExpectedVersion $ExpectedVersion -RepositoryRoot $RepositoryRoot
    $buildRoot=Join-Path $root 'build'
    Assert-PythonAcceptanceBundlePlainPath $buildRoot
    if((Test-Path -LiteralPath $buildRoot) -and !(Test-Path -LiteralPath $buildRoot -PathType Container)){throw 'Prebuilt outputs require a fresh, empty harness build directory.'}
    if(Test-Path -LiteralPath $buildRoot -PathType Container){
        $existingBuildItems=@(Get-ChildItem -LiteralPath $buildRoot -Force)
        if($existingBuildItems.Count){throw 'Prebuilt outputs require a fresh, empty harness build directory.'}
    }
    New-Item -ItemType Directory -Force $buildRoot | Out-Null
    # Bundle paths are relative to its root; build outputs must lose exactly the
    # leading build/ because the destination already names the harness build.
    # Enumerate the verified manifest so copy/reverification use the same files.
    $copyMap=@(foreach($entry in $verified.Files.Values | Sort-Object path){
        $relative=[string]$entry.path
        if($relative -ieq "release/deposit-server-$ExpectedVersion.zip"){
            [pscustomobject]@{Entry=$entry;Target="deposit-server-$ExpectedVersion.zip"}
        }elseif($relative -ieq 'build/deposit-build.properties' -or
                 $relative -ieq "build/deposit-plugin/target/deposit-plugin-$ExpectedVersion.jar" -or
                 $relative.StartsWith('build/deposit-integration-tests/target/classes/',[StringComparison]::OrdinalIgnoreCase) -or
                 $relative.StartsWith('build/deposit-integration-tests/target/dependency/',[StringComparison]::OrdinalIgnoreCase)){
            [pscustomobject]@{Entry=$entry;Target=$relative.Substring('build/'.Length)}
        }
    })
    foreach($item in $copyMap){
        $source=Join-Path $verified.Root ($item.Entry.path.Replace('/','\'))
        $target=Join-Path $buildRoot ($item.Target.Replace('/','\'))
        New-Item -ItemType Directory -Force (Split-Path $target -Parent)|Out-Null
        Copy-Item -LiteralPath $source -Destination $target
        $copied=Get-Item -LiteralPath $target
        if([long]$copied.Length -ne [long]$item.Entry.size -or
           (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -cne [string]$item.Entry.sha256){
            throw "Copied build output failed verification: $($item.Target)"
        }
    }
    return (Join-Path $buildRoot (Split-Path $verified.ArchivePath -Leaf))
}
