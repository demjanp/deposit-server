# Deliberate lock regeneration only. Normal provisioning downloads the resulting exact URLs/hashes.
[CmdletBinding()]
param([string]$Root=(Join-Path $PSScriptRoot '..\..\test_harness'))
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
. (Join-Path $PSScriptRoot 'DependencyResolverHelpers.ps1')
Assert-HarnessRoot $Root
$manifest=Get-Content "$repo\runtime\artifacts.json" -Raw | ConvertFrom-Json
$work=Join-Path $Root 'dependency-resolution'
New-Item -ItemType Directory -Force $work,"$Root\cache\spatial-dependencies" | Out-Null
Copy-Item -LiteralPath "$repo\runtime\spatial-dependencies.pom.xml" -Destination "$work\pom.xml" -Force
$saved=@{}
$overrides=Get-Content "$repo\runtime\license-overrides.json" -Raw | ConvertFrom-Json
foreach($key in @('JAVA_HOME','MAVEN_USER_HOME','MAVEN_OPTS','TEMP','TMP')) {$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
try {
    $env:JAVA_HOME=Join-Path $Root $manifest.javaHome; $env:MAVEN_USER_HOME="$Root\maven-user"
    $env:TEMP=$work; $env:TMP=$work; $env:MAVEN_OPTS="-Djava.io.tmpdir=$work"
    Push-Location $repo
    try {
        & .\mvnw.cmd --batch-mode --no-transfer-progress "-Dmaven.repo.local=$Root\maven-repository" -f "$work\pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.8.1:list -DincludeScope=runtime -DoutputAbsoluteArtifactFilename=true "-DoutputFile=$work\dependencies.txt" *> "$work\list.log"
        if($LASTEXITCODE -ne 0) {throw 'Spatial dependency resolution failed'}
    } finally {Pop-Location}
    $dependencies=New-Object System.Collections.Generic.List[object]
    $missingLicenses=New-Object System.Collections.Generic.List[string]
    foreach($line in (Get-Content "$work\dependencies.txt")) {
        $parsed=ConvertFrom-ResolverDependencyLine $line
        if(!$parsed) {continue}
        $group=$parsed.groupId;$artifact=$parsed.artifactId;$version=$parsed.version;$file=$parsed.file
        $runtime=@(Get-ChildItem (Join-Path $Root "$($manifest.neo4jHome)\lib") -Filter *.jar | Where-Object {$_.Name -match ('^'+[regex]::Escape($artifact)+'-\d')})
        $name=[IO.Path]::GetFileName($file)
        $repository=Get-ResolverRepositoryBase $file $name
        $pom=[IO.Path]::ChangeExtension($file,'pom')
        $licenses=@(Get-ResolverLicenses $Root $pom)
        $key="${group}:${artifact}:${version}"
        if(!$licenses.Count -and $overrides.PSObject.Properties[$key]) {$licenses=@($overrides.$key)}
        if(!$licenses.Count) {$missingLicenses.Add($key)}
        $metadata=Get-ResolverArtifactMetadata $group $artifact $version $file $repository
        $dependencies.Add(@{groupId=$group;artifactId=$artifact;version=$version;file=$metadata.file;sha256=$metadata.sha256;url=$metadata.url;licenses=$licenses;ship=($runtime.Count -eq 0);providedByRuntime=@($runtime | ForEach-Object Name)})
        if(!$runtime.Count) {Copy-Item -LiteralPath $file -Destination "$Root\cache\spatial-dependencies\$name" -Force}
    }
    if(!$dependencies.Count) {throw 'Empty Spatial dependency graph'}
    if($missingLicenses.Count) {
        $missingLicenses.ToArray() | ConvertTo-Json | Set-Content "$work\unresolved-licenses.json"
        throw "Missing license evidence for: $($missingLicenses -join ', ')"
    }
    $lock=@{spatial='2025.10.0';geotools='33.2';jts='1.20.0 (provided by Neo4j)';sourcePomSha256=(Get-FileHash "$repo\runtime\spatial-dependencies.pom.xml" -Algorithm SHA256).Hash.ToLowerInvariant();dependencies=@($dependencies | Sort-Object groupId,artifactId)}
    [IO.File]::WriteAllText("$repo\runtime\spatial-runtime-lock.json",($lock | ConvertTo-Json -Depth 10),(New-Object Text.UTF8Encoding($false)))
    Write-Output "Pinned $($dependencies.Count) resolved dependencies; $(@($dependencies | Where-Object ship).Count) shipped separately."
} finally {foreach($key in $saved.Keys) {[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}}
