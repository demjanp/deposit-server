[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$CleanWorktree,
    [Parameter(Mandatory=$true)][string]$PreparedHarnessRoot,
    [string]$OutputPath
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath($CleanWorktree)
$harness=[IO.Path]::GetFullPath($PreparedHarnessRoot)
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
. (Join-Path $PSScriptRoot 'DepositBuildMetadata.ps1')
. (Join-Path $PSScriptRoot 'helpers\PythonAcceptanceBuildBundle.ps1')
$expectedHarness=[IO.Path]::GetFullPath((Join-Path (Split-Path $repo -Parent) 'test_harness'))
if($harness -ine $expectedHarness){throw 'Prepared harness must be the sibling test_harness owned by the clean worktree.'}
foreach($path in @($repo,$harness)){
    $current=[IO.Path]::GetFullPath($path)
    while($current){if((Test-Path -LiteralPath $current) -and ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Clean worktree or prepared harness contains a reparse point.'};$current=Split-Path $current -Parent}
}
function Assert-PreparedBundleSourcePath([string]$Path){
    $full=[IO.Path]::GetFullPath($Path)
    $current=$full
    while($current){if((Test-Path -LiteralPath $current) -and ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Prepared build output contains a reparse point.'};$current=Split-Path $current -Parent}
    if(Test-Path -LiteralPath $full -PathType Container){if(@(Get-ChildItem -LiteralPath $full -Force -Recurse|Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count){throw 'Prepared build output contains a reparse point.'}}
}
$status=@(& git -C $repo status --porcelain --untracked-files=all 2>$null)
if($LASTEXITCODE -ne 0 -or $status.Count){throw 'Build bundle source must be a clean Git worktree.'}
$commit=(& git -C $repo rev-parse --verify HEAD 2>$null).Trim()
if($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$'){throw 'Build bundle source commit is invalid.'}
$metadata=Get-DepositBuildMetadata $repo $harness
$tags=@(& git -C $repo tag --points-at $commit 2>$null|Where-Object {$_ -ceq $metadata.Version})
if($LASTEXITCODE -ne 0 -or $tags.Count -ne 1){throw 'Build bundle must come from a clean exact-version tagged worktree.'}
$artifactManifest=Get-Content -LiteralPath (Join-Path $repo 'runtime\artifacts.json') -Raw|ConvertFrom-Json -ErrorAction Stop
$tools=Get-Content -LiteralPath (Join-Path $repo 'runtime\integration-tools.json') -Raw|ConvertFrom-Json -ErrorAction Stop
$java=Join-Path $harness ($artifactManifest.javaHome+'\bin\java.exe')
$maven=Join-Path $repo 'mvnw.cmd'
$python=Join-Path $harness $tools.pythonExe
foreach($file in @($java,$maven,$python)){if(!(Test-Path -LiteralPath $file -PathType Leaf)){throw "Pinned build tool is missing: $file"}}
function Get-PythonAcceptanceBundleToolOutput([string]$Executable,[string[]]$Arguments,[string]$Label){
    $previousPreference=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    try{$output=@(& $Executable @Arguments 2>&1);$exitCode=$LASTEXITCODE}finally{$ErrorActionPreference=$previousPreference}
    if($exitCode -ne 0){throw "Could not read the $Label version."}
    return $output
}
$javaOutput=@(Get-PythonAcceptanceBundleToolOutput $java @('-version') 'clean worktree Java')
$mavenOutput=@(Get-PythonAcceptanceBundleToolOutput $maven @('--version') 'clean worktree Maven')
$pythonOutput=@(Get-PythonAcceptanceBundleToolOutput $python @('--version') 'prepared Python')
$toolVersions=[ordered]@{java=([string]$javaOutput[0]).Trim();maven=([string]($mavenOutput|Where-Object {$_ -match '^Apache Maven'}|Select-Object -First 1)).Trim();neo4j=[string]$artifactManifest.neo4jVersion;python=([string]$pythonOutput[0]).Trim();powershell=$PSVersionTable.PSVersion.ToString()}
if(!$toolVersions.maven){throw 'Maven version output is missing.'}
$sourceFiles=[ordered]@{}
$sourceFiles['release/deposit-server-'+$metadata.Version+'.zip']=Join-Path $harness ('build\'+$metadata.ArchiveFile)
$sourceFiles['build/deposit-build.properties']=Join-Path $harness 'build\deposit-build.properties'
$sourceFiles['build/deposit-plugin/target/'+$metadata.PluginFile]=Join-Path $harness ('build\deposit-plugin\target\'+$metadata.PluginFile)
$sourceFiles['build/deposit-integration-tests/target/classes']=Join-Path $harness 'build\deposit-integration-tests\target\classes'
$sourceFiles['build/deposit-integration-tests/target/dependency']=Join-Path $harness 'build\deposit-integration-tests\target\dependency'
foreach($path in $sourceFiles.Values){if(!(Test-Path -LiteralPath $path)){throw "Prepared clean build output is missing: $path"};Assert-PreparedBundleSourcePath $path}
$readinessClass=Join-Path $sourceFiles['build/deposit-integration-tests/target/classes'] 'org\deposit\it\ReadinessClient.class'
$readinessDependencies=@(Get-ChildItem -LiteralPath $sourceFiles['build/deposit-integration-tests/target/dependency'] -Filter '*.jar' -File -ErrorAction Stop)
if(!(Test-Path -LiteralPath $readinessClass -PathType Leaf) -or !$readinessDependencies.Count){throw 'Prepared clean build is missing readiness classes or dependencies.'}
$pluginPath=$sourceFiles[('build/deposit-plugin/target/'+$metadata.PluginFile)]
if((Get-DepositJarVersion $pluginPath) -cne $metadata.Version){throw 'Prepared plugin JAR version differs from generated build metadata.'}
if(!$OutputPath){$OutputPath=Join-Path (Join-Path (Get-HarnessRoot) 'cache\python-acceptance-build-bundles') ($metadata.Version+'-'+$commit.Substring(0,12))}
$output=[IO.Path]::GetFullPath($OutputPath).TrimEnd('\')
$allowed=(Join-Path (Get-HarnessRoot) 'cache\python-acceptance-build-bundles').TrimEnd('\')+'\'
if(!$output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Build bundle output must be inside the harness-owned cache.'}
if(Test-Path -LiteralPath $output){throw 'Build bundle output already exists; choose a fresh bundle path.'}
New-Item -ItemType Directory -Path $output -Force|Out-Null
foreach($relative in $sourceFiles.Keys){
    $source=$sourceFiles[$relative];$destination=Join-Path $output ($relative.Replace('/','\'))
    if((Get-Item -LiteralPath $source -Force).PSIsContainer){
        New-Item -ItemType Directory -Path $destination -Force|Out-Null
        foreach($file in Get-ChildItem -LiteralPath $source -File -Recurse){$rel=$file.FullName.Substring($source.Length).TrimStart('\');$target=Join-Path $destination $rel;New-Item -ItemType Directory -Force (Split-Path $target -Parent)|Out-Null;Copy-Item -LiteralPath $file.FullName -Destination $target}
    }else{
        New-Item -ItemType Directory -Force (Split-Path $destination -Parent)|Out-Null
        Copy-Item -LiteralPath $source -Destination $destination
    }
}
$payload=@(Get-PythonAcceptanceBundleTree $output | ForEach-Object {
    $relative=Get-PythonAcceptanceBundleRelativePath $output $_;$item=Get-Item -LiteralPath $_
    [ordered]@{path=$relative;size=[long]$item.Length;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$bundle=[ordered]@{
    schemaVersion=1
    version=$metadata.Version
    source=[ordered]@{commit=$commit;tag=$metadata.Version;status='clean';statusPorcelain=@()}
    toolVersions=$toolVersions
    files=$payload
}
[IO.File]::WriteAllText((Join-Path $output 'bundle.json'),(ConvertTo-Json -InputObject $bundle -Depth 8)+"`n",(New-Object Text.UTF8Encoding($false)))
$mainRepo=Split-Path $PSScriptRoot -Parent
$verified=Test-PythonAcceptanceBuildBundle -BundleRoot $output -ExpectedVersion $metadata.Version -RepositoryRoot $mainRepo
Write-Output "PASS: clean tagged build bundle $($verified.Version)"
Write-Output "Bundle: $output"
