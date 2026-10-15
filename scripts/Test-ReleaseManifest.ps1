$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ReleaseManifest.ps1')
$root=Join-Path ([IO.Path]::GetTempPath()) ('deposit-release-manifest-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$checks=0
function Assert-True([bool]$Value,[string]$Message){if(!$Value){throw $Message};$script:checks++}
function Assert-Fails([scriptblock]$Action,[string]$Label){$failed=$false;try{& $Action}catch{$failed=$true};Assert-True $failed "Expected rejection: $Label"}
function Write-JsonFile([string]$Path,$Value){[IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 10)+"`n",(New-Object Text.UTF8Encoding($false)))}
function Reset-Package {
    Get-ChildItem -LiteralPath $root -Force | Remove-Item -Recurse -Force
    New-Item -ItemType Directory -Force (Join-Path $root 'plugins'),(Join-Path $root 'helpers'),(Join-Path $root 'notices') | Out-Null
    [IO.File]::WriteAllText((Join-Path $root 'plugins/deposit-plugin.jar'),'plugin-bytes')
    [IO.File]::WriteAllText((Join-Path $root 'plugins/apoc.jar'),'apoc-bytes')
    [IO.File]::WriteAllText((Join-Path $root 'helpers/start.ps1'),'launcher-helper')
    [IO.File]::WriteAllText((Join-Path $root 'notices/LICENSE.txt'),'license-text')
    [IO.File]::WriteAllText((Join-Path $root 'artifacts.json'),'{"neo4jVersion":"5.26.0"}')
    Get-ReleaseManifestData $root @('plugins/deposit-plugin.jar','plugins/apoc.jar')
}
function Read-Json([string]$Path){Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -ErrorAction Stop}
function Save-Manifests($Release,$Distribution){Write-JsonFile (Join-Path $root 'release-manifest.json') $Release;Write-JsonFile (Join-Path $root 'distribution-manifest.json') $Distribution}
function New-InstallationState {
    [ordered]@{schemaVersion=1;mode='local';neo4jHome='C:\test\neo4j';javaHome='C:\test\java';dataRoot='C:\test\data';exportRoot='C:\test\exports';secretRoot='C:\test\neo4j\deposit-secrets';serviceName='DepositNeo4j-0123abcd';distributionHome=[IO.Path]::GetFullPath($root);neo4jVersion='5.26.0';createdUtc='2026-10-15T00:00:00.0000000Z'}
}

try {
    Reset-Package
    Assert-True (Test-DepositReleasePackage $root) 'Valid package rejected'
    $releasePath=Join-Path $root 'release-manifest.json';$distributionPath=Join-Path $root 'distribution-manifest.json'
    $releaseBytes=[IO.File]::ReadAllBytes($releasePath);$distributionBytes=[IO.File]::ReadAllBytes($distributionPath)
    Assert-True ((Read-Json $releasePath).files.path -contains 'notices/LICENSE.txt') 'Nested license missing from release manifest'
    Assert-True ((Read-Json $releasePath).files.path -contains 'distribution-manifest.json') 'Distribution manifest was not checksummed'
    Assert-True (!((Read-Json $releasePath).files.path -contains 'release-manifest.json')) 'Release manifest checksums itself'
    Assert-True (((Read-Json $distributionPath).plugins.path -join ',') -eq 'plugins/apoc.jar,plugins/deposit-plugin.jar') 'Plugin list is not ordinal sorted or complete'
    [IO.File]::WriteAllText((Join-Path $root 'deposit.install.json'),'{}')
    Assert-Fails {Get-ReleaseManifestData $root @('plugins/deposit-plugin.jar','plugins/apoc.jar')} 'installation state in generated package'
    Assert-Fails {Test-DepositReleasePackage $root} 'installation state in strict package mode'
    [IO.File]::WriteAllText((Join-Path $root 'deposit.install.json'),(ConvertTo-Json -InputObject (New-InstallationState) -Depth 6))
    $expected=@{neo4jHome='C:\test\neo4j';javaHome='C:\test\java';dataRoot='C:\test\data';exportRoot='C:\test\exports';mode='local'}
    Assert-True (Test-DepositReleasePackage -Root $root -ConfiguredInstallation -ExpectedInstallation $expected) 'Configured installation rejected'
    $statePath=Join-Path $root 'deposit.install.json';Write-JsonFile $statePath @{schemaVersion=1;mode='local'}
    Assert-Fails {Test-DepositReleasePackage -Root $root -ConfiguredInstallation -ExpectedInstallation $expected} 'incomplete installation state'
    $state=New-InstallationState;$state.neo4jHome='C:\other\neo4j';Write-JsonFile $statePath $state
    Assert-Fails {Test-DepositReleasePackage -Root $root -ConfiguredInstallation -ExpectedInstallation $expected} 'mismatched installation path'
    [IO.File]::WriteAllText($statePath,(ConvertTo-Json -InputObject (New-InstallationState) -Depth 6))
    [IO.File]::WriteAllText((Join-Path $root 'extra.txt'),'unexpected')
    Assert-Fails {Test-DepositReleasePackage -Root $root -ConfiguredInstallation -ExpectedInstallation $expected} 'other configured-installation extra file'
    Remove-Item -LiteralPath (Join-Path $root 'extra.txt')
    $payload=Join-Path $root 'helpers/start.ps1';[IO.File]::AppendAllText($payload,'changed')
    Assert-Fails {Test-DepositReleasePackage -Root $root -ConfiguredInstallation -ExpectedInstallation $expected} 'configured payload tampering'
    [IO.File]::WriteAllText($payload,'launcher-helper')
    Assert-True (Test-DepositReleasePackage -Root $root -ConfiguredInstallation -ExpectedInstallation $expected) 'Restored configured installation rejected'
    Remove-Item -LiteralPath (Join-Path $root 'deposit.install.json')
    $stateTarget=Join-Path $root 'state-target.json';Write-JsonFile $stateTarget (New-InstallationState)
    $stateLink=Join-Path $root 'deposit.install.json'
    try {
        New-Item -ItemType SymbolicLink -Path $stateLink -Target $stateTarget -ErrorAction Stop|Out-Null
        Assert-Fails {Test-DepositReleasePackage -Root $root -ConfiguredInstallation -ExpectedInstallation $expected} 'installation state reparse point'
        Remove-Item -LiteralPath $stateLink -Force
    } catch {
        Remove-Item -LiteralPath $stateLink -Force -ErrorAction SilentlyContinue
        if($_.Exception.Message -notmatch 'privilege|access|symbolic link|not allowed|administrator'){throw}
    }
    Remove-Item -LiteralPath $stateTarget -Force
    Remove-Item -LiteralPath $root -Force -Recurse;New-Item -ItemType Directory -Path $root|Out-Null;Reset-Package
    Get-ReleaseManifestData $root @('plugins/deposit-plugin.jar','plugins/apoc.jar')
    Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$releaseBytes,[byte[]][IO.File]::ReadAllBytes($releasePath))) 'Manifest generation is not deterministic'
    Assert-True ([Linq.Enumerable]::SequenceEqual([byte[]]$distributionBytes,[byte[]][IO.File]::ReadAllBytes($distributionPath))) 'Distribution manifest generation is not deterministic'

    $payload=Join-Path $root 'helpers/start.ps1';$original=[IO.File]::ReadAllText($payload)
    [IO.File]::AppendAllText($payload,'changed');Assert-Fails {Test-DepositReleasePackage $root} 'modified bytes';[IO.File]::WriteAllText($payload,$original)
    Assert-True (Test-DepositReleasePackage $root) 'Restored package rejected'
    $release=Read-Json $releasePath;$release.files|Where-Object path -eq 'helpers/start.ps1'|ForEach-Object {$_.size++};Save-Manifests $release (Read-Json $distributionPath);Assert-Fails {Test-DepositReleasePackage $root} 'length mismatch';Reset-Package
    Remove-Item -LiteralPath (Join-Path $root 'helpers/start.ps1');Assert-Fails {Test-DepositReleasePackage $root} 'missing file';Reset-Package
    [IO.File]::WriteAllText((Join-Path $root 'extra.txt'),'unexpected');Assert-Fails {Test-DepositReleasePackage $root} 'extra file';Reset-Package
    $distribution=Read-Json $distributionPath;$distribution.plugins=@($distribution.plugins|Where-Object path -ne 'plugins/apoc.jar');Save-Manifests (Read-Json $releasePath) $distribution;Assert-Fails {Test-DepositReleasePackage $root} 'missing plugin';Reset-Package
    [IO.File]::WriteAllText((Join-Path $root 'plugins/extra.jar'),'unexpected');Assert-Fails {Test-DepositReleasePackage $root} 'extra plugin';Reset-Package
    [IO.File]::WriteAllText($releasePath,'{');Assert-Fails {Test-DepositReleasePackage $root} 'malformed JSON';Reset-Package
    $release=Read-Json $releasePath;$release.schemaVersion=2;Write-JsonFile $releasePath $release;Assert-Fails {Test-DepositReleasePackage $root} 'unsupported schema';Reset-Package
    $release=Read-Json $releasePath;$release.files=@($release.files)+$release.files[0];Save-Manifests $release (Read-Json $distributionPath);Assert-Fails {Test-DepositReleasePackage $root} 'duplicate path';Reset-Package
    $release=Read-Json $releasePath;$release.files+=([ordered]@{path='NOTICES/LICENSE.txt';size=11;sha256='0'*64});Save-Manifests $release (Read-Json $distributionPath);Assert-Fails {Test-DepositReleasePackage $root} 'case-colliding path';Reset-Package
    $release=Read-Json $releasePath;$release.files[0].path='../outside';Write-JsonFile $releasePath $release;Assert-Fails {Test-DepositReleasePackage $root} 'traversal path';Reset-Package
    $release=Read-Json $releasePath;$release.files[0].path='C:/outside';Write-JsonFile $releasePath $release;Assert-Fails {Test-DepositReleasePackage $root} 'absolute path';Reset-Package
    $distribution=Read-Json $distributionPath;$distribution.plugins=@($distribution.plugins)+([ordered]@{path='../evil.jar'});Save-Manifests (Read-Json $releasePath) $distribution;Assert-Fails {Test-DepositReleasePackage $root} 'unsafe plugin path';Reset-Package
    $outside=Join-Path $root 'outside';New-Item -ItemType Directory -Path $outside | Out-Null
    $junction=Join-Path $root 'junction'
    try {New-Item -ItemType Junction -Path $junction -Target $outside | Out-Null;Assert-Fails {Test-DepositReleasePackage $root} 'reparse point';Remove-Item -LiteralPath $junction -Force} catch {if($_.Exception.Message -notmatch 'privilege|access|symbolic link|junction'){throw}}
    Assert-True (Test-DepositReleasePackage $root) 'Final valid package rejected'
    Write-Output "PASS: release manifest checks ($checks assertions)"
} finally {if(Test-Path -LiteralPath $root){Remove-Item -LiteralPath $root -Recurse -Force}}
