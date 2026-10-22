function Get-ResolverLicenses([string]$RepositoryRoot,[string]$Pom,[int]$Depth=0) {
    if($Depth -gt 12 -or !(Test-Path -LiteralPath $Pom)) {return @()}
    [xml]$xml=Get-Content -LiteralPath $Pom -Raw
    $licenses=@($xml.SelectNodes("/*[local-name()='project']/*[local-name()='licenses']/*[local-name()='license']"))
    if($licenses.Count) {
        return @($licenses | ForEach-Object {@{name=[string]$_.name;url=[string]$_.url;pomSha256=(Get-FileHash -LiteralPath $Pom -Algorithm SHA256).Hash.ToLowerInvariant()}})
    }
    $parent=$xml.SelectSingleNode("/*[local-name()='project']/*[local-name()='parent']")
    if(!$parent) {return @()}
    $parentPom=Join-Path $RepositoryRoot ('maven-repository\'+$parent.groupId.Replace('.','\')+'\'+$parent.artifactId+'\'+$parent.version+'\'+$parent.artifactId+'-'+$parent.version+'.pom')
    return @(Get-ResolverLicenses $RepositoryRoot $parentPom ($Depth+1))
}

function ConvertFrom-ResolverDependencyLine([string]$Line) {
    if($Line -notmatch '^\s+([^:]+):([^:]+):jar:([^:]+):(compile|runtime):(.+?\.jar)(?:\s|$)') {return $null}
    return @{groupId=$matches[1];artifactId=$matches[2];version=$matches[3];file=$matches[5]}
}

function Get-ResolverRepositoryBase([string]$ArtifactPath,[string]$FileName) {
    $repository='https://repo.maven.apache.org/maven2/'
    $origin=Join-Path (Split-Path $ArtifactPath -Parent) '_remote.repositories'
    if((Get-Content $origin -Raw) -match ([regex]::Escape($FileName)+'>osgeo=')) {$repository='https://repo.osgeo.org/repository/release/'}
    return $repository
}

function Get-ResolverArtifactMetadata([string]$GroupId,[string]$ArtifactId,[string]$Version,[string]$FilePath,[string]$RepositoryBase) {
    $name=[IO.Path]::GetFileName($FilePath)
    return @{file=$name;sha256=(Get-FileHash $FilePath -Algorithm SHA256).Hash.ToLowerInvariant();url=$RepositoryBase+$GroupId.Replace('.','/')+'/'+$ArtifactId+'/'+$Version+'/'+$name}
}
