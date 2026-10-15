# Deliberate lock regeneration only. Normal provisioning downloads the resulting exact URLs/hashes.
[CmdletBinding()]
param([string]$Root='D:\data_processed\Deposit_v2\stage0')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if([IO.Path]::GetFullPath($Root).TrimEnd('\') -ne 'D:\data_processed\Deposit_v2\stage0') {throw 'Use the approved Stage 0 root'}
$manifest=Get-Content "$repo\runtime\artifacts.json" -Raw | ConvertFrom-Json
$spatialLock=Get-Content (Join-Path "$repo\runtime" $manifest.dependencyLock) -Raw | ConvertFrom-Json
$ocflLock=Get-Content (Join-Path "$repo\runtime" $manifest.ocflDependencyLock) -Raw | ConvertFrom-Json
$work=Join-Path $Root 'format-dependency-resolution'
New-Item -ItemType Directory -Force $work,"$Root\cache\format-dependencies" | Out-Null
Copy-Item -LiteralPath "$repo\runtime\format-dependencies.pom.xml" -Destination "$work\pom.xml" -Force
$saved=@{}
$overrides=Get-Content "$repo\runtime\license-overrides.json" -Raw | ConvertFrom-Json
foreach($key in @('JAVA_HOME','MAVEN_USER_HOME','MAVEN_OPTS','TEMP','TMP')) {$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
function Get-Licenses([string]$Pom,[int]$Depth=0) {
    if($Depth -gt 12 -or !(Test-Path -LiteralPath $Pom)) {return @()}
    [xml]$xml=Get-Content -LiteralPath $Pom -Raw
    $licenses=@($xml.SelectNodes("/*[local-name()='project']/*[local-name()='licenses']/*[local-name()='license']"))
    if($licenses.Count) {
        return @($licenses | ForEach-Object {@{name=[string]$_.name;url=[string]$_.url;pomSha256=(Get-FileHash -LiteralPath $Pom -Algorithm SHA256).Hash.ToLowerInvariant()}})
    }
    $parent=$xml.SelectSingleNode("/*[local-name()='project']/*[local-name()='parent']")
    if(!$parent) {return @()}
    $parentPom=Join-Path $Root ('maven-repository\'+$parent.groupId.Replace('.','\')+'\'+$parent.artifactId+'\'+$parent.version+'\'+$parent.artifactId+'-'+$parent.version+'.pom')
    return @(Get-Licenses $parentPom ($Depth+1))
}
try {
    $env:JAVA_HOME=Join-Path $Root $manifest.javaHome; $env:MAVEN_USER_HOME="$Root\maven-user"
    $env:TEMP=$work; $env:TMP=$work; $env:MAVEN_OPTS="-Djava.io.tmpdir=$work"
    Push-Location $repo
    try {
        & .\mvnw.cmd --batch-mode --no-transfer-progress "-Dmaven.repo.local=$Root\maven-repository" -f "$work\pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.8.1:list -DincludeScope=runtime -DoutputAbsoluteArtifactFilename=true "-DoutputFile=$work\dependencies.txt" *> "$work\list.log"
        if($LASTEXITCODE -ne 0) {throw 'Format dependency resolution failed'}
    } finally {Pop-Location}
    $dependencies=New-Object System.Collections.Generic.List[object]
    $missingLicenses=New-Object System.Collections.Generic.List[string]
    foreach($line in (Get-Content "$work\dependencies.txt")) {
        if($line -notmatch '^\s+([^:]+):([^:]+):jar:([^:]+):(compile|runtime):(.+?\.jar)(?:\s|$)') {continue}
        $group=$matches[1];$artifact=$matches[2];$version=$matches[3];$file=$matches[5]
        $runtime=@(Get-ChildItem (Join-Path $Root "$($manifest.neo4jHome)\lib") -Filter *.jar | Where-Object {$_.Name -match ('^'+[regex]::Escape($artifact)+'-\d')})
        $allExisting=@($spatialLock.dependencies)+@($ocflLock.dependencies);$shared=@($allExisting | Where-Object { $_.artifactId -eq $artifact -and $_.ship })
        if($shared.Count -gt 0 -and @($shared | Where-Object { $_.file -ne [IO.Path]::GetFileName($file) -or $_.version -ne $version -or $_.sha256 -ne (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() }).Count) {throw "Format dependency conflicts with an already locked plugin dependency: $artifact"}
        $repository='https://repo.maven.apache.org/maven2/'
        $origin=Join-Path (Split-Path $file -Parent) '_remote.repositories'
        if((Get-Content $origin -Raw) -match ([regex]::Escape([IO.Path]::GetFileName($file))+'>osgeo=')) {$repository='https://repo.osgeo.org/repository/release/'}
        $pom=[IO.Path]::ChangeExtension($file,'pom')
        $licenses=@(Get-Licenses $pom)
        $key="${group}:${artifact}:${version}"
        if(!$licenses.Count -and $overrides.PSObject.Properties[$key]) {$licenses=@($overrides.$key)}
        if(!$licenses.Count) {$missingLicenses.Add($key)}
        $name=[IO.Path]::GetFileName($file)
        if($runtime.Count -and ($runtime.Count -ne 1 -or $runtime[0].Name -ne $name -or (Get-FileHash $runtime[0].FullName -Algorithm SHA256).Hash -ne (Get-FileHash $file -Algorithm SHA256).Hash)){throw "Format dependency conflicts with provided runtime: $name"}
        $ship=$runtime.Count -eq 0 -and $shared.Count -eq 0
        $dependencies.Add(@{groupId=$group;artifactId=$artifact;version=$version;file=$name;sha256=(Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant();url=$repository+$group.Replace('.','/')+'/'+$artifact+'/'+$version+'/'+$name;licenses=$licenses;ship=$ship;providedByRuntime=@($runtime | ForEach-Object Name);providedByExistingLock=@($shared | ForEach-Object file)})
        if($ship) {Copy-Item -LiteralPath $file -Destination "$Root\cache\format-dependencies\$name" -Force}
    }
    if(!$dependencies.Count) {throw 'Empty Format dependency graph'}
    if($missingLicenses.Count) {
        $missingLicenses.ToArray() | ConvertTo-Json | Set-Content "$work\unresolved-licenses.json"
        throw "Missing license evidence for: $($missingLicenses -join ', ')"
    }
    $lock=@{formatAdapters='poi-ooxml 5.5.1, sqlite-jdbc 3.53.4.0';specification='1.1 (1.1.1 clarifications)';sourcePomSha256=(Get-FileHash "$repo\runtime\format-dependencies.pom.xml" -Algorithm SHA256).Hash.ToLowerInvariant();dependencies=@($dependencies | Sort-Object groupId,artifactId)}
    [IO.File]::WriteAllText("$repo\runtime\format-runtime-lock.json",($lock | ConvertTo-Json -Depth 10),(New-Object Text.UTF8Encoding($false)))
    Write-Output "Pinned $($dependencies.Count) resolved format dependencies; $(@($dependencies | Where-Object ship).Count) shipped separately."
} finally {foreach($key in $saved.Keys) {[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}}
