Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'ReleaseManifest.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-ReleaseNoticeHash([byte[]]$Bytes) {
    $algorithm=[Security.Cryptography.SHA256]::Create()
    try{return ([BitConverter]::ToString($algorithm.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant()}finally{$algorithm.Dispose()}
}

function Get-ReleaseNoticeEntries($Archive) {
    $names=@($Archive.Entries | Where-Object {
        !$_.FullName.EndsWith('/') -and $_.FullName -notmatch '\.(class|xsb)$' -and
        ($_.FullName -match '(?i)(^|/)(LICENSE|LICENCE|NOTICE|COPYING|COPYRIGHT|about)([._-].*)?$' -or $_.FullName -match '(?i)(^|/)about_files/')
    } | ForEach-Object {$_.FullName})
    return @((Get-OrdinalSorted $names)|ForEach-Object {$Archive.GetEntry($_)})
}

function Read-ReleaseNoticeEntry($Entry) {
    $inputStream=$Entry.Open();$buffer=New-Object IO.MemoryStream
    try{$inputStream.CopyTo($buffer);return ,$buffer.ToArray()}finally{$inputStream.Dispose();$buffer.Dispose()}
}

function Write-DepositReleaseNotices([string]$PackageRoot,[string]$RepositoryRoot) {
    $runtime=Join-Path $RepositoryRoot 'runtime'
    $noticesLock=Get-Content -LiteralPath (Join-Path $runtime 'release-notices-lock.json') -Raw | ConvertFrom-Json
    if($noticesLock.schemaVersion -ne 1){throw 'Unsupported release notice lock schema'}
    $artifacts=Get-Content -LiteralPath (Join-Path $runtime 'artifacts.json') -Raw | ConvertFrom-Json
    $inputs=@{};$provided=New-Object 'System.Collections.Generic.List[object]'
    foreach($name in @('spatial-runtime-lock.json','ocfl-runtime-lock.json','format-runtime-lock.json')) {
        $dependencyLock=Get-Content -LiteralPath (Join-Path $runtime $name) -Raw | ConvertFrom-Json
        foreach($artifact in $dependencyLock.dependencies){
            $identity="$($artifact.groupId):$($artifact.artifactId):$($artifact.version)"
            $record=[ordered]@{id=$identity;version=$artifact.version;sha256=$artifact.sha256;source=[ordered]@{lock=$name;url=$artifact.url};declarations=@($artifact.licenses)}
            if($artifact.ship){
                if($inputs.ContainsKey($artifact.file) -and $inputs[$artifact.file].sha256 -cne $artifact.sha256){throw "Conflicting dependency identity: $($artifact.file)"}
                $inputs[$artifact.file]=$record
            }else{$provided.Add([ordered]@{id=$identity;file=$artifact.file;version=$artifact.version;sha256=$artifact.sha256;providedBy='separately installed Neo4j runtime';lock=$name;declarations=@($artifact.licenses)})}
        }
    }
    foreach($id in @('apoc','spatial','bcrypt-provider')){
        $artifact=@($artifacts.artifacts|Where-Object id -eq $id)
        if($artifact.Count -ne 1){throw "Missing release artifact identity: $id"};$artifact=$artifact[0]
        $inputs[$artifact.file]=[ordered]@{id=$id;sha256=$artifact.sha256;source=[ordered]@{lock='artifacts.json';url=$artifact.url};declarations=@([ordered]@{name=$artifact.license;provenance='artifacts.json'})}
    }
    [xml]$project=Get-Content -LiteralPath (Join-Path $RepositoryRoot 'pom.xml') -Raw
    $version=$project.SelectSingleNode('/*[local-name()="project"]/*[local-name()="version"]').InnerText
    $projectFile="deposit-plugin-$version.jar"
    $inputs[$projectFile]=[ordered]@{id="org.deposit:deposit-plugin:$version";version=$version;sha256=$null;source=[ordered]@{lock='pom.xml';url='repository:plugin/pom.xml'};declarations=@([ordered]@{name='GPL-3.0';provenance='repository:LICENSE'})}
    $lockedFiles=@($noticesLock.components|ForEach-Object {[string]$_.file})
    $null=Get-ReleasePathSet $lockedFiles 'notice lock component'
    $actualFiles=@(Get-ChildItem -LiteralPath (Join-Path $PackageRoot 'plugins') -File -Filter '*.jar'|ForEach-Object {$_.Name})
    if($actualFiles.Count -ne $lockedFiles.Count -or @($actualFiles|Where-Object {$_ -cnotin $lockedFiles}).Count -or @($lockedFiles|Where-Object {$_ -cnotin $actualFiles}).Count){throw 'Notice component inventory differs from shipped plugin JARs'}
    if($inputs.Count -ne $lockedFiles.Count -or @($lockedFiles|Where-Object {!$inputs.ContainsKey($_)}).Count){throw 'Notice lock does not map every pinned release input'}
    $components=New-Object 'System.Collections.Generic.List[object]'
    foreach($file in Get-OrdinalSorted $lockedFiles){
        $component=@($noticesLock.components|Where-Object file -CEQ $file)[0];$inputRecord=$inputs[$file]
        $jarPath=Join-Path $PackageRoot ('plugins/'+$file);$jarHash=(Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if($component.id -cne $inputRecord.id -or ($inputRecord.Contains('version') -and $component.version -cne $inputRecord.version)){throw "Notice component identity mismatch: $file"}
        if($file -cne $projectFile -and ($jarHash -cne $inputRecord.sha256 -or $jarHash -cne $component.sha256)){throw "Notice component checksum mismatch: $file"}
        $evidence=New-Object 'System.Collections.Generic.List[object]'
        $archive=[IO.Compression.ZipFile]::OpenRead($jarPath)
        try{
            $entries=@(Get-ReleaseNoticeEntries $archive);$lockedEntries=@($component.embedded|ForEach-Object {$_.entry})
            $null=Get-ReleasePathSet $lockedEntries 'embedded notice'
            if($entries.Count -ne $lockedEntries.Count -or @($entries|Where-Object {$_.FullName -cnotin $lockedEntries}).Count){throw "Embedded notice inventory mismatch: $file"}
            foreach($entry in $entries){
                Assert-ReleaseManifestPath $entry.FullName
                $bytes=Read-ReleaseNoticeEntry $entry;$hash=Get-ReleaseNoticeHash $bytes
                $expected=@($component.embedded|Where-Object entry -CEQ $entry.FullName)[0]
                if($hash -cne $expected.sha256){throw "Embedded notice checksum mismatch: $file/$($entry.FullName)"}
                $relative='notices/components/'+$file+'/'+$entry.FullName;$destination=Join-Path $PackageRoot $relative
                New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent)|Out-Null;[IO.File]::WriteAllBytes($destination,$bytes)
                $evidence.Add([ordered]@{path=$relative;sha256=$hash;kind='embedded';source=[ordered]@{jar='plugins/'+$file;jarSha256=$jarHash;entry=$entry.FullName}})
            }
        }finally{$archive.Dispose()}
        foreach($text in $component.supplemental){
            Assert-ReleaseManifestPath $text.path
            $source=Join-Path $RepositoryRoot $text.path
            if(!(Test-Path -LiteralPath $source -PathType Leaf)){throw "Release notice source is missing: $($text.path)"}
            $hash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
            if($hash -cne $text.sha256 -or [string]$text.source.sha256 -cnotmatch '^[a-f0-9]{64}$' -or [string]::IsNullOrWhiteSpace([string]$text.source.url)){throw "Release notice source checksum/provenance mismatch: $($text.path)"}
            $relative='notices/sources/'+$text.path;$destination=Join-Path $PackageRoot $relative
            New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent)|Out-Null;Copy-Item -LiteralPath $source -Destination $destination -Force
            $evidence.Add([ordered]@{path=$relative;sha256=$hash;kind=$text.kind;source=$text.source})
        }
        if(!$evidence.Count){throw "Component has no license/notice evidence: $file"}
        $record=[ordered]@{id=$component.id;version=$component.version;path='plugins/'+$file;sha256=$jarHash;provenance=$inputRecord.source;declarations=$inputRecord.declarations;evidence=@($evidence.ToArray())}
        if($component.PSObject.Properties.Name -contains 'declarationDiscrepancy'){$record.declarationDiscrepancy=$component.declarationDiscrepancy}
        $components.Add($record)
    }
    $external=@($artifacts.artifacts|Where-Object {$_.id -in @('neo4j','temurin')}|ForEach-Object {
        $runtimeVersion=if($_.id -eq 'neo4j'){$artifacts.neo4jVersion}else{[IO.Path]::GetFileName([string]$artifacts.javaHome)}
        [ordered]@{id=$_.id;version=$runtimeVersion;file=$_.file;sha256=$_.sha256;url=$_.url;licenseDeclaration=$_.license;delivery='separately installed; not bundled in this distribution'}
    })
    $index=[ordered]@{schemaVersion=1;components=@($components.ToArray());externalRuntimes=$external;providedDependencies=@($provided.ToArray())}
    $indexPath=Join-Path $PackageRoot 'notices/component-index.json'
    New-Item -ItemType Directory -Force -Path (Split-Path $indexPath -Parent)|Out-Null
    [IO.File]::WriteAllText($indexPath,(ConvertTo-Json -InputObject $index -Depth 20)+"`n",(New-Object Text.UTF8Encoding($false)))
    Copy-Item -LiteralPath (Join-Path $runtime 'release-notices-lock.json') -Destination (Join-Path $PackageRoot 'release-notices-lock.json')
    return Test-DepositReleaseNotices $PackageRoot
}

function Test-DepositReleaseNotices([string]$PackageRoot) {
    $index=Get-Content -LiteralPath (Join-Path $PackageRoot 'notices/component-index.json') -Raw|ConvertFrom-Json
    if($index.schemaVersion -ne 1){throw 'Unsupported component notice index schema'}
    $paths=@($index.components|ForEach-Object {$_.path});$null=Get-ReleasePathSet $paths 'notice component'
    $actual=@(Get-ChildItem -LiteralPath (Join-Path $PackageRoot 'plugins') -File -Filter '*.jar'|ForEach-Object {'plugins/'+$_.Name})
    if($paths.Count -ne $actual.Count -or @($actual|Where-Object {$_ -cnotin $paths}).Count){throw 'Notice index differs from shipped plugin inventory'}
    foreach($component in $index.components){
        if(!$component.evidence.Count){throw "Component has no notice evidence: $($component.path)"}
        foreach($file in @($component)+@($component.evidence)){
            Assert-ReleaseManifestPath $file.path
            if((Get-FileHash -LiteralPath (Join-Path $PackageRoot $file.path) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $file.sha256){throw "Notice index checksum mismatch: $($file.path)"}
        }
    }
    return $true
}
