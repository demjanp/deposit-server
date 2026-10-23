[CmdletBinding()]
param([string]$RepositoryRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if([string]::IsNullOrWhiteSpace($RepositoryRoot)){$RepositoryRoot=Split-Path $PSScriptRoot -Parent}
$repository=[IO.Path]::GetFullPath($RepositoryRoot)
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('deposit-git-version-'+[guid]::NewGuid().ToString('N'))
$metadataRoot=Join-Path $fixture 'metadata-output'
function Invoke-Git([string[]]$Arguments) {
    $preference=$ErrorActionPreference
    try{$ErrorActionPreference='Continue';$output=& git -C $fixture @Arguments 2>&1;$exitCode=$LASTEXITCODE}finally{$ErrorActionPreference=$preference}
    if($exitCode -ne 0){throw "Git failed: $($output -join "`n")"}
    return ($output -join "`n").Trim()
}
try {
    New-Item -ItemType Directory -Force -Path $fixture|Out-Null
    $null=Invoke-Git @('init')
    $null=Invoke-Git @('checkout','-b','main')
    $null=Invoke-Git @('config','user.name','Deposit version test')
    $null=Invoke-Git @('config','user.email','version-test@example.invalid')
    [IO.File]::WriteAllText((Join-Path $fixture 'change.txt'),'base tagged commit')
    $null=Invoke-Git @('add','.')
    $null=Invoke-Git @('commit','-m','base version tag')
    $null=Invoke-Git @('tag','1.2.3')
    [IO.File]::WriteAllText((Join-Path $fixture 'change.txt'),'first post-tag commit')
    $null=Invoke-Git @('add','change.txt')
    $null=Invoke-Git @('commit','-m','first post-tag commit')
    $null=Invoke-Git @('tag','2.0.0-invalid_suffix')
    [IO.File]::AppendAllText((Join-Path $fixture 'change.txt'),"`nsecond post-tag commit")
    $null=Invoke-Git @('add','change.txt')
    $null=Invoke-Git @('commit','-m','second post-tag commit')

    $head=Invoke-Git @('rev-parse','HEAD')
    $short=$head.Substring(0,7)
    $expected="1.2.3-post.2.g$short-SNAPSHOT"
    $javaHome=$env:JAVA_HOME
    if(!$javaHome){$candidate=Join-Path (Split-Path $repository -Parent) 'test_harness\tools\jdk-21.0.12.1+1';if(Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')){$javaHome=$candidate}}
    if(!$javaHome){throw 'Git version fixture requires Java 21'}
    $java=Join-Path $javaHome 'bin\java.exe'
    & $java (Join-Path $repository 'scripts\ValidateGitVersion.java') $fixture $expected $metadataRoot
    if($LASTEXITCODE -ne 0){throw 'Git version validator rejected the supported first-parent tag'}
    $metadata=Get-Content -LiteralPath (Join-Path $metadataRoot 'build\deposit-build.properties') -Raw
    if($metadata -notmatch "(?m)^version=$([regex]::Escape($expected))$"){throw 'Version metadata did not match the validated version'}
    Write-Output "PASS: Git-derived post-tag version ignores unsupported nearby tags ($expected)"
} finally {
    $resolved=[IO.Path]::GetFullPath($fixture)
    $temporary=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    if(!$resolved.StartsWith($temporary,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'deposit-git-version-*'){throw 'Unsafe Git version fixture cleanup path'}
    if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
