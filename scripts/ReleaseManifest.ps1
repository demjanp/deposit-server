Set-StrictMode -Version Latest

function Get-ReleaseTreeFiles([string]$Root) {
    $rootPath=[IO.Path]::GetFullPath($Root).TrimEnd('\')
    if(!(Test-Path -LiteralPath $rootPath -PathType Container)){throw "Release directory is missing: $rootPath"}
    if((Get-Item -LiteralPath $rootPath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Release directory cannot be a reparse point'}
    $pending=New-Object 'System.Collections.Generic.Stack[string]';$pending.Push($rootPath)
    $files=New-Object 'System.Collections.Generic.List[string]'
    while($pending.Count){
        $directory=$pending.Pop()
        foreach($item in Get-ChildItem -LiteralPath $directory -Force){
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw "Release tree cannot contain reparse points: $($item.FullName)"}
            if($item.PSIsContainer){$pending.Push($item.FullName)}else{$files.Add($item.FullName)}
        }
    }
    return $files.ToArray()
}

function ConvertTo-ReleaseRelativePath([string]$Root,[string]$FullPath) {
    $rootPath=[IO.Path]::GetFullPath($Root).TrimEnd('\')+'\'
    $full=[IO.Path]::GetFullPath($FullPath)
    if(!$full.StartsWith($rootPath,[StringComparison]::OrdinalIgnoreCase)){throw 'Release path escaped its package root'}
    return $full.Substring($rootPath.Length).Replace('\','/')
}

function Assert-ReleaseManifestPath([string]$Path) {
    if([string]::IsNullOrWhiteSpace($Path) -or $Path.Contains('\') -or $Path.StartsWith('/') -or [IO.Path]::IsPathRooted($Path) -or $Path -match '^[A-Za-z]:') {throw "Invalid release manifest path: $Path"}
    foreach($part in $Path.Split('/')){if(!$part -or $part -in @('.','..') -or $part.Contains(':')){throw "Invalid release manifest path: $Path"}}
}

function Get-ReleasePathSet([string[]]$Paths,[string]$Label) {
    $seen=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach($path in $Paths){Assert-ReleaseManifestPath $path;if(!$seen.Add($path)){throw "Duplicate or case-colliding $Label path: $path"}}
    return ,$seen
}

function Get-OrdinalSorted([string[]]$Values) {
    $sorted=New-Object 'System.Collections.Generic.List[string]'
    foreach($value in $Values){$sorted.Add($value)}
    $sorted.Sort([StringComparer]::Ordinal)
    return $sorted.ToArray()
}

function Get-ReleaseManifestData([string]$Root,[string[]]$ExpectedPluginPaths) {
    $files=Get-ReleaseTreeFiles $Root
    $relative=@($files | ForEach-Object {ConvertTo-ReleaseRelativePath $Root $_})
    if(@($relative | Where-Object {$_ -ieq 'deposit.install.json'}).Count){throw 'Launcher installation state cannot be included in a release package'}
    $fileSet=Get-ReleasePathSet $relative 'release'
    $pluginPaths=@($relative | Where-Object {$_ -match '^(?i:plugins)/.+\.jar$'})
    $pluginSet=Get-ReleasePathSet $pluginPaths 'plugin'
    $expectedSet=Get-ReleasePathSet $ExpectedPluginPaths 'expected plugin'
    if($pluginSet.Count -ne $expectedSet.Count -or @($pluginSet | Where-Object {!$expectedSet.Contains($_)}).Count -or @($expectedSet | Where-Object {!$pluginSet.Contains($_)}).Count){throw 'Shipped plugin JAR set differs from the pinned release inputs'}
    $distribution=[ordered]@{schemaVersion=1;plugins=@((Get-OrdinalSorted $pluginPaths) | ForEach-Object {[ordered]@{path=$_}})}
    $distributionPath=Join-Path $Root 'distribution-manifest.json'
    [IO.File]::WriteAllText($distributionPath,(ConvertTo-Json -InputObject $distribution -Depth 5)+"`n",(New-Object Text.UTF8Encoding($false)))

    $files=Get-ReleaseTreeFiles $Root
    $relative=@($files | ForEach-Object {ConvertTo-ReleaseRelativePath $Root $_})
    $null=Get-ReleasePathSet $relative 'release'
    $manifestFiles=New-Object 'System.Collections.Generic.List[object]'
    foreach($path in Get-OrdinalSorted $relative){
        if($path -ceq 'release-manifest.json'){continue}
        $fullPath=Join-Path $Root ($path.Replace('/','\'))
        $item=Get-Item -LiteralPath $fullPath
        $hash=(Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $manifestFiles.Add([ordered]@{path=$path;size=[long]$item.Length;sha256=$hash})
    }
    $release=[ordered]@{schemaVersion=1;files=@($manifestFiles.ToArray())}
    [IO.File]::WriteAllText((Join-Path $Root 'release-manifest.json'),(ConvertTo-Json -InputObject $release -Depth 6)+"`n",(New-Object Text.UTF8Encoding($false)))
}

function Test-DepositInstallationState([string]$Root,$ExpectedInstallation) {
    $statePath=Join-Path $Root 'deposit.install.json'
    $item=Get-Item -LiteralPath $statePath -Force
    if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Installation state cannot be a reparse point'}
    try{$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -ErrorAction Stop}catch{throw 'Installation state JSON is malformed'}
    if($state.schemaVersion -ne 1){throw 'Unsupported installation state schema'}
    foreach($key in @('neo4jHome','javaHome','dataRoot','exportRoot','secretRoot','serviceName','distributionHome','neo4jVersion','mode')){
        if([string]::IsNullOrWhiteSpace([string]$state.$key)){throw "Installation state is missing $key"}
    }
    if($state.mode -notin @('local','service')){throw 'Installation state mode is invalid'}
    if([string]$state.distributionHome -ine [IO.Path]::GetFullPath($Root)){throw 'Installation state points at a different distribution'}
    $artifactPath=Join-Path $Root 'artifacts.json'
    $artifacts=Get-Content -LiteralPath $artifactPath -Raw | ConvertFrom-Json -ErrorAction Stop
    if([string]$state.neo4jVersion -cne [string]$artifacts.neo4jVersion){throw 'Installation state Neo4j version differs from the package runtime manifest'}
    if([string]$state.serviceName -notmatch '^DepositNeo4j-[0-9a-fA-F]{8}$'){throw 'Installation state service name is invalid'}
    foreach($key in @('neo4jHome','javaHome','dataRoot','exportRoot','secretRoot')){$state.$key=[IO.Path]::GetFullPath([string]$state.$key)}
    foreach($key in @('neo4jHome','javaHome','dataRoot','exportRoot','mode')){
        if($null -ne $ExpectedInstallation -and $null -ne $ExpectedInstallation.$key){
            $expectedValue=if($key -eq 'mode'){[string]$ExpectedInstallation.$key}else{[IO.Path]::GetFullPath([string]$ExpectedInstallation.$key)}
            if([string]$state.$key -ine $expectedValue){throw "Installation state $key differs from the exercised installation"}
        }
    }
    return $state
}

function Test-DepositReleasePackage([string]$Root,[switch]$ConfiguredInstallation,$ExpectedInstallation) {
    $files=Get-ReleaseTreeFiles $Root
    $relative=@($files | ForEach-Object {ConvertTo-ReleaseRelativePath $Root $_})
    $actualSet=Get-ReleasePathSet $relative 'release tree'
    $configuredState=@($relative | Where-Object {$_ -ieq 'deposit.install.json'})
    if($ConfiguredInstallation){
        if($configuredState.Count -ne 1 -or $configuredState[0] -cne 'deposit.install.json'){throw 'Configured installation must contain only the root launcher state file'}
        $relative=@($relative | Where-Object {$_ -cne 'deposit.install.json'})
        $actualSet=Get-ReleasePathSet $relative 'release tree'
        $null=Test-DepositInstallationState $Root $ExpectedInstallation
    }elseif($configuredState.Count){throw 'Launcher installation state is not valid in a release package'}
    $releasePath=Join-Path $Root 'release-manifest.json';$distributionPath=Join-Path $Root 'distribution-manifest.json'
    if(!$actualSet.Contains('release-manifest.json') -or !$actualSet.Contains('distribution-manifest.json')){throw 'Release manifests are missing'}
    try {$release=Get-Content -LiteralPath $releasePath -Raw | ConvertFrom-Json -ErrorAction Stop;$distribution=Get-Content -LiteralPath $distributionPath -Raw | ConvertFrom-Json -ErrorAction Stop} catch {throw 'Release manifest JSON is malformed'}
    if($release.schemaVersion -ne 1 -or $distribution.schemaVersion -ne 1){throw 'Unsupported release manifest schema'}
    if($null -eq $release.files -or $null -eq $distribution.plugins){throw 'Release manifest collections are missing'}
    $manifestPaths=@($release.files | ForEach-Object {[string]$_.path})
    $manifestSet=Get-ReleasePathSet $manifestPaths 'manifest'
    if($manifestSet.Contains('release-manifest.json')){throw 'Release manifest cannot checksum itself'}
    $expectedFileSet=Get-ReleasePathSet @($relative | Where-Object {$_ -cne 'release-manifest.json'}) 'release tree'
    if($manifestSet.Count -ne $expectedFileSet.Count -or @($manifestSet | Where-Object {!$expectedFileSet.Contains($_)}).Count -or @($expectedFileSet | Where-Object {!$manifestSet.Contains($_)}).Count){throw 'Release manifest file set differs from the package'}
    foreach($entry in $release.files){
        $path=[string]$entry.path
        if($entry.size -isnot [long] -and $entry.size -isnot [int] -and $entry.size -isnot [int64]){throw "Invalid release file size: $path"}
        if([long]$entry.size -lt 0 -or [string]$entry.sha256 -cnotmatch '^[0-9a-f]{64}$'){throw "Invalid release file metadata: $path"}
        $fullPath=Join-Path $Root ($path.Replace('/','\'))
        $item=Get-Item -LiteralPath $fullPath
        if([long]$item.Length -ne [long]$entry.size){throw "Release file length mismatch: $path"}
        if((Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne [string]$entry.sha256){throw "Release file checksum mismatch: $path"}
    }
    $pluginPaths=@($distribution.plugins | ForEach-Object {[string]$_.path})
    $pluginSet=Get-ReleasePathSet $pluginPaths 'distribution plugin'
    $actualPlugins=@($relative | Where-Object {$_ -match '^(?i:plugins)/.+\.jar$'})
    $actualPluginSet=Get-ReleasePathSet $actualPlugins 'package plugin'
    if($pluginSet.Count -ne $actualPluginSet.Count -or @($pluginSet | Where-Object {!$actualPluginSet.Contains($_)}).Count -or @($actualPluginSet | Where-Object {!$pluginSet.Contains($_)}).Count){throw 'Distribution manifest plugin set differs from the package'}
    foreach($path in $pluginPaths){if($path -notmatch '^(?i:plugins)/.+\.jar$'){throw "Invalid distribution plugin path: $path"}}
    return $true
}
