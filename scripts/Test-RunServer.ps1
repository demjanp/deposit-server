[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Exercise real extraction/download validation using tiny local archives. The
# existing shared runtimes and databases are never changed by these checks.
$source = Join-Path $PSScriptRoot 'Run_Server.ps1'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw ($errors | Out-String) }
foreach ($name in @('Get-ServerArtifact','Initialize-ServerTools','Set-ServerPluginFiles')) {
    $definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name },$true)
    . ([scriptblock]::Create($definition.Extent.Text))
}
$root = Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\test_harness'))) ('tmp\run-server-test-'+[guid]::NewGuid().ToString('N'))
$downloads = 0
function Invoke-WebRequest {
    param([switch]$UseBasicParsing,[string]$Uri,[string]$OutFile)
    $script:downloads++
    Copy-Item -LiteralPath $Uri -Destination $OutFile
}
function Assert-ServerTest([bool]$Condition,[string]$Message) { if (!$Condition) { throw $Message } }
try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $artifacts = @()
    foreach ($fixture in @(
        @{ Id='temurin'; Folder='jdk-fixture'; Files=@('java.exe','javac.exe') },
        @{ Id='neo4j'; Folder='neo4j-fixture'; Files=@('neo4j.bat','neo4j-admin.bat') },
        @{ Id='maven'; Folder='apache-maven-fixture'; Files=@('mvn.cmd') }
    )) {
        $content = Join-Path $root ('source\'+$fixture.Folder)
        New-Item -ItemType Directory -Path "$content\bin" -Force | Out-Null
        foreach ($name in $fixture.Files) { [IO.File]::WriteAllText((Join-Path "$content\bin" $name),'fixture') }
        $archive = Join-Path $root ($fixture.Folder+'-bin.zip')
        Compress-Archive -LiteralPath $content -DestinationPath $archive
        $artifacts += [pscustomobject]@{id=$fixture.Id;file=[IO.Path]::GetFileName($archive);url=$archive;sha256=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash}
    }
    $manifest = [pscustomobject]@{javaHome='tools/jdk-fixture';neo4jHome='tools/neo4j-fixture';artifacts=$artifacts}
    Initialize-ServerTools
    Assert-ServerTest ($downloads -eq 3) 'Missing runtimes were not downloaded'
    Assert-ServerTest (Test-Path "$root\tools\neo4j-fixture\bin\neo4j-admin.bat") 'Neo4j was not extracted'
    Initialize-ServerTools
    Assert-ServerTest ($downloads -eq 3) 'Cached runtimes unexpectedly downloaded again'
    Remove-Item -LiteralPath "$root\tools\jdk-fixture\bin\javac.exe"
    Initialize-ServerTools
    Assert-ServerTest ($downloads -eq 3 -and (Test-Path "$root\tools\jdk-fixture\bin\javac.exe")) 'Incomplete runtime was not repaired from cache'

    $bad = [pscustomobject]@{file='bad.zip';url=$artifacts[0].url;sha256=('0'*64)}
    $rejected = $false
    try { $null = Get-ServerArtifact $bad "$root\cache" } catch { $rejected = $_.Exception.Message -like '*checksum mismatch*' }
    Assert-ServerTest $rejected 'Corrupt download was accepted'
    Assert-ServerTest (!(Test-Path "$root\cache\bad.zip")) 'Corrupt download was published'
    Assert-ServerTest (@(Get-ChildItem "$root\cache" -Filter '*.partial').Count -eq 0) 'Temporary download leaked'
    [IO.File]::WriteAllText((Join-Path "$root\cache" $artifacts[0].file),'corrupt')
    $rejected = $false
    try { $null = Get-ServerArtifact $artifacts[0] "$root\cache" } catch { $rejected = $_.Exception.Message -like '*checksum mismatch*' }
    Assert-ServerTest $rejected 'Corrupt cache was accepted'
    New-Item -ItemType Directory -Path "$root\staging" -Force | Out-Null
    [IO.File]::WriteAllText("$root\staging\obsolete.jar",'old')
    [IO.File]::WriteAllText("$root\current.jar",'new')
    Set-ServerPluginFiles "$root\staging" @("$root\current.jar")
    Assert-ServerTest (!(Test-Path "$root\staging\obsolete.jar") -and (Test-Path "$root\staging\current.jar")) 'Setup retry retained obsolete plugin JARs'

    # An installed server must remain controllable after repository pins change.
    $fixtureRepo = Join-Path $root 'checkout\server'
    $fixtureHarness = Join-Path $root 'checkout\test_harness'
    $fixtureLocal = Join-Path $fixtureHarness 'runs\local-server'
    $fixturePackage = Join-Path $fixtureLocal 'distribution'
    New-Item -ItemType Directory -Path "$fixtureRepo\scripts","$fixtureRepo\runtime",$fixturePackage -Force | Out-Null
    Copy-Item -LiteralPath $source,(Join-Path $PSScriptRoot 'DepositBuildMetadata.ps1') -Destination "$fixtureRepo\scripts"
    [IO.File]::WriteAllText("$fixtureRepo\runtime\artifacts.json",'{"javaHome":"tools/new-java","neo4jVersion":"new"}')
    [IO.File]::WriteAllText("$fixturePackage\artifacts.json",'{"javaHome":"tools/installed-java","neo4jVersion":"installed"}')
    $state = @{mode='local';neo4jHome="$fixtureLocal\neo4j";distributionHome=$fixturePackage;javaHome="$fixtureHarness\tools\installed-java";neo4jVersion='installed';dataRoot="$fixtureLocal\resources";exportRoot="$fixtureLocal\exports"}
    [IO.File]::WriteAllText("$fixturePackage\deposit.install.json",($state|ConvertTo-Json))
    [IO.File]::WriteAllText("$fixturePackage\deposit.ps1",'param($Command) if ($Command -ne "stop") { exit 8 }; exit 0')
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$fixtureRepo\scripts\Run_Server.ps1" -Action Stop
    Assert-ServerTest ($LASTEXITCODE -eq 0) 'Source pin changes prevented stopping the installed server'
    Write-Host 'Run_Server provisioning checks passed: missing tools, cache reuse, partial extraction, download/cache hash rejection.'
} finally {
    $target = [IO.Path]::GetFullPath($root)
    $parent = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\test_harness\tmp')).TrimEnd('\')+'\'
    if (!$target.StartsWith($parent,[StringComparison]::OrdinalIgnoreCase)) { throw 'Test cleanup escaped the harness temp directory' }
    Remove-Item -LiteralPath $target -Recurse -Force
}
