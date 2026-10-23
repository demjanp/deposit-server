Set-StrictMode -Version Latest

function Get-DepositBuildMetadata([string]$RepositoryRoot,[string]$HarnessRoot=(Join-Path (Split-Path ([IO.Path]::GetFullPath($RepositoryRoot)) -Parent) 'test_harness')) {
    $repository=[IO.Path]::GetFullPath($RepositoryRoot)
    $metadataPath=Join-Path $HarnessRoot 'build\deposit-build.properties'
    if(!(Test-Path -LiteralPath $metadataPath -PathType Leaf)){throw 'Build metadata is missing; run the Maven build first'}
    $metadata=@{}
    foreach($line in [IO.File]::ReadAllLines($metadataPath)){
        if($line -match '^\s*([^#!\s][^=]*)=(.*)$'){$metadata[$matches[1].Trim()]=$matches[2].Trim()}
    }
    $version=[string]$metadata['version'];$pluginFile=[string]$metadata['pluginFile'];$probeFile=[string]$metadata['probeFile'];$archiveFile=[string]$metadata['archiveFile']
    if($version -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$' -or $version.Contains('${')){throw "Invalid resolved Deposit build version: $version"}
    foreach($file in @($pluginFile,$probeFile,$archiveFile)){if([string]::IsNullOrWhiteSpace($file) -or [IO.Path]::GetFileName($file) -cne $file){throw 'Invalid build artifact name metadata'}}
    if($pluginFile -cne "deposit-plugin-$version.jar" -or $probeFile -cne "deposit-runtime-probe-$version.jar" -or $archiveFile -cne "deposit-server-$version.zip"){throw 'Build artifact names do not match the embedded Deposit version'}
    return [pscustomobject]@{Version=$version;PluginFile=$pluginFile;ProbeFile=$probeFile;ArchiveFile=$archiveFile}
}

function Get-DepositJarVersion([string]$JarPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry=$archive.GetEntry('deposit-build.properties')
        if(!$entry){throw "Build metadata is missing from JAR: $JarPath"}
        $reader=New-Object IO.StreamReader($entry.Open())
        try{$version=$null;while(($line=$reader.ReadLine()) -ne $null){if($line -match '^\s*version\s*=\s*(.*?)\s*$'){$version=$matches[1];break}}}finally{$reader.Dispose()}
        if([string]::IsNullOrWhiteSpace($version) -or $version.Contains('${')){throw "JAR has invalid Deposit build metadata: $JarPath"}
        return $version
    } finally {$archive.Dispose()}
}
