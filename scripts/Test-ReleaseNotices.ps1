[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ReleaseNotices.ps1')
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('deposit-release-notices-'+[Guid]::NewGuid().ToString('N'))
$repository=Join-Path $testRoot 'repository';$package=Join-Path $testRoot 'package';$script:assertions=0
function Assert-Notice([bool]$Condition,[string]$Message){if(!$Condition){throw $Message};$script:assertions++}
function Reject-Notice([scriptblock]$Action,[string]$Message){$rejected=$false;try{& $Action|Out-Null}catch{$rejected=$true};Assert-Notice $rejected $Message}
function Write-NoticeJson([string]$Path,$Value){[IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 20),[Text.UTF8Encoding]::new($false))}
function Make-NoticeJar([string]$Path,$Entries){
    $archive=[IO.Compression.ZipFile]::Open($Path,'Create')
    try{foreach($name in $Entries.Keys){$entry=$archive.CreateEntry($name);$stream=$entry.Open();try{$bytes=[Text.Encoding]::UTF8.GetBytes($Entries[$name]);$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}}}finally{$archive.Dispose()}
}
try{
    New-Item -ItemType Directory -Force -Path "$repository/runtime/notices","$package/plugins"|Out-Null
    [IO.File]::WriteAllText("$repository/pom.xml",'<project><version>1.0</version></project>')
    [IO.File]::WriteAllText("$repository/runtime/notices/source.txt",'Exact upstream license and component copyright text.')
    $sourceHash=(Get-FileHash "$repository/runtime/notices/source.txt" -Algorithm SHA256).Hash.ToLowerInvariant()
    $components=New-Object 'System.Collections.Generic.List[object]';$artifacts=New-Object 'System.Collections.Generic.List[object]'
    foreach($spec in @(
        @{file='deposit-plugin-1.0.jar';id='org.deposit:deposit-plugin:1.0';version='1.0';entries=@{'deposit/Main.class'='class'}},
        @{file='apoc-1.0.jar';id='apoc';version='1.0';entries=@{'META-INF/LICENSE.txt'='license';'META-INF/NOTICE.txt'='copyright'}},
        @{file='spatial-1.0.jar';id='spatial';version='1.0';entries=@{'library/Main.class'='class'}},
        @{file='provider-1.0.jar';id='bcrypt-provider';version='1.0';entries=@{'about.html'='EPL 2.0';'about_files/third-party.txt'='third party attribution';'org/author/LICENSE.class'='class'}}
    )){
        if($spec.id -like 'org.deposit:*'){$spec.entries['deposit-build.properties']='version=1.0'}
        Make-NoticeJar "$package/plugins/$($spec.file)" $spec.entries;$hash=(Get-FileHash "$package/plugins/$($spec.file)" -Algorithm SHA256).Hash.ToLowerInvariant()
        $embedded=@($spec.entries.Keys|Where-Object {$_ -notmatch '\.class$' -and $_ -cne 'deposit-build.properties'}|Sort-Object|ForEach-Object {@{entry=$_;sha256=(Get-ReleaseNoticeHash ([Text.Encoding]::UTF8.GetBytes($spec.entries[$_])))}})
        $lockFile=$spec.file;$lockId=$spec.id;$lockVersion=$spec.version
        if($spec.id -like 'org.deposit:*'){$lockFile='deposit-plugin-{version}.jar';$lockId='org.deposit:deposit-plugin:{version}';$lockVersion='{version}'}
        $components.Add(@{file=$lockFile;id=$lockId;version=$lockVersion;sha256=$hash;embedded=$embedded;supplemental=@(@{path='runtime/notices/source.txt';sha256=$sourceHash;kind='upstreamLicenseText';source=@{url='https://upstream.example/version-1.0/LICENSE';sha256=$sourceHash}});declarationDiscrepancy='upstream declarations differ; unresolved'})
        if($spec.id -notlike 'org.deposit:*'){$artifacts.Add(@{file=$spec.file;id=$spec.id;sha256=$hash;url='https://upstream.example/'+$spec.file;license='test declaration'})}
    }
    foreach($id in @('neo4j','temurin')){$artifacts.Add(@{id=$id;file="$id-1.0.zip";sha256=('0'*64);url="https://upstream.example/$id";license='external declaration'})}
    Write-NoticeJson "$repository/runtime/artifacts.json" @{neo4jVersion='1.0';javaHome='tools/jdk-21';artifacts=@($artifacts.ToArray())}
    foreach($name in @('spatial','ocfl','format')){Write-NoticeJson "$repository/runtime/$name-runtime-lock.json" @{dependencies=@()}}
    $lockPath="$repository/runtime/release-notices-lock.json";Write-NoticeJson $lockPath @{schemaVersion=1;components=@($components.ToArray())};$originalLock=[IO.File]::ReadAllText($lockPath)
    Assert-Notice (Write-DepositReleaseNotices $package $repository) 'Valid notice package rejected'
    $index=Get-Content "$package/notices/component-index.json" -Raw|ConvertFrom-Json
    Assert-Notice ($index.components.Count -eq 4) 'Not every actual JAR mapped'
    Assert-Notice ($index.externalRuntimes.Count -eq 2) 'External runtime identities missing'
    Assert-Notice ($index.components[0].declarationDiscrepancy -ceq 'upstream declarations differ; unresolved') 'Declaration discrepancy lost'
    Assert-Notice (Test-Path "$package/notices/components/provider-1.0.jar/about_files/third-party.txt") 'Nested about_files evidence lost'
    Assert-Notice (Test-Path "$package/notices/components/apoc-1.0.jar/META-INF/NOTICE.txt") 'Embedded component NOTICE lost'
    Assert-Notice (!(Test-Path "$package/notices/components/provider-1.0.jar/org/author/LICENSE.class")) 'Class named LICENSE was misidentified as text evidence'
    $firstHash=(Get-FileHash "$package/notices/component-index.json").Hash
    $null=Write-DepositReleaseNotices $package $repository
    Assert-Notice ($firstHash -ceq (Get-FileHash "$package/notices/component-index.json").Hash) 'Notice generation is nondeterministic'
    [IO.File]::WriteAllText("$package/plugins/unknown.jar",'unknown');Reject-Notice {Write-DepositReleaseNotices $package $repository} 'Unknown JAR accepted';Remove-Item -LiteralPath "$package/plugins/unknown.jar"
    $jarBytes=[IO.File]::ReadAllBytes("$package/plugins/apoc-1.0.jar");[IO.File]::WriteAllText("$package/plugins/apoc-1.0.jar",'changed');Reject-Notice {Write-DepositReleaseNotices $package $repository} 'Changed JAR accepted';[IO.File]::WriteAllBytes("$package/plugins/apoc-1.0.jar",$jarBytes)
    [IO.File]::WriteAllText("$repository/runtime/notices/source.txt",'changed');Reject-Notice {Write-DepositReleaseNotices $package $repository} 'Changed upstream text accepted';[IO.File]::WriteAllText("$repository/runtime/notices/source.txt",'Exact upstream license and component copyright text.')
    Move-Item -LiteralPath "$repository/runtime/notices/source.txt" -Destination "$repository/runtime/notices/source.saved";Reject-Notice {Write-DepositReleaseNotices $package $repository} 'Missing license source accepted';Move-Item -LiteralPath "$repository/runtime/notices/source.saved" -Destination "$repository/runtime/notices/source.txt"
    $bad=ConvertFrom-Json $originalLock;$bad.components[1].embedded[0].sha256='0'*64;Write-NoticeJson $lockPath $bad;Reject-Notice {Write-DepositReleaseNotices $package $repository} 'Wrong embedded notice hash accepted';[IO.File]::WriteAllText($lockPath,$originalLock)
    $bad=ConvertFrom-Json $originalLock;$bad.components[1].embedded=@();Write-NoticeJson $lockPath $bad;Reject-Notice {Write-DepositReleaseNotices $package $repository} 'Omitted embedded notice accepted';[IO.File]::WriteAllText($lockPath,$originalLock)
    $bad=ConvertFrom-Json $originalLock;$bad.components[0].supplemental[0].path='../escape.txt';Write-NoticeJson $lockPath $bad;Reject-Notice {Write-DepositReleaseNotices $package $repository} 'Notice source traversal accepted';[IO.File]::WriteAllText($lockPath,$originalLock)
    $bad=ConvertFrom-Json $originalLock;$bad.components[1].id='incorrect-component';Write-NoticeJson $lockPath $bad;Reject-Notice {Write-DepositReleaseNotices $package $repository} 'Wrong component identity accepted';[IO.File]::WriteAllText($lockPath,$originalLock)
    [IO.File]::WriteAllText("$package/notices/components/apoc-1.0.jar/META-INF/NOTICE.txt",'changed');Reject-Notice {Test-DepositReleaseNotices $package} 'Changed packaged attribution accepted'
    Write-Output "PASS: Release notices ($script:assertions assertions)"
}finally{
    $resolved=[IO.Path]::GetFullPath($testRoot);$temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    if(!$resolved.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'deposit-release-notices-*'){throw 'Unsafe notice test cleanup path'}
    if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
