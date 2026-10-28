[CmdletBinding()]
param([string]$Root,[string]$FixtureTag='2.0.0-dev.77')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(!$Root){$Root=Join-Path $PSScriptRoot '..\..\test_harness'}
Add-Type -AssemblyName System.IO.Compression
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
. (Join-Path $PSScriptRoot 'IntegrationHarnessSecrets.ps1')
. (Join-Path $PSScriptRoot 'DepositBuildMetadata.ps1')
. (Join-Path $PSScriptRoot 'helpers\PythonAcceptanceBuildBundle.ps1')
$Root=[IO.Path]::GetFullPath($Root).TrimEnd('\')
Assert-HarnessRoot $Root
$checkout=Split-Path $PSScriptRoot -Parent
if($FixtureTag -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$'){throw 'FixtureTag must be an exact server version tag.'}
$version=$FixtureTag
$tagRef="refs/tags/$FixtureTag"
& git -C $checkout show-ref --verify --quiet $tagRef
$tagExistsExit=$LASTEXITCODE
if($tagExistsExit -ne 0){throw "Could not resolve fixture tag '$FixtureTag' to a commit."}
$tagCommit=(& git -C $checkout rev-parse --verify "$tagRef^{commit}").Trim()
if($LASTEXITCODE -ne 0 -or $tagCommit -notmatch '^[0-9a-f]{40}$'){throw "Could not resolve fixture tag '$FixtureTag' to a commit."}
$tagPoints=@(& git -C $checkout tag --points-at $tagCommit 2>$null|Where-Object {$_ -ceq $FixtureTag})
if($LASTEXITCODE -ne 0 -or $tagPoints.Count -ne 1){throw "Fixture tag '$FixtureTag' is not the exact tag at its source commit."}
$sourceWorktree=Join-Path $Root ('cache\python-acceptance-build-bundles\probe-source-'+[Guid]::NewGuid().ToString('N'))
$bundleRoot=Join-Path $Root ('cache\python-acceptance-build-bundles\test-fixtures-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $bundleRoot -Force|Out-Null
function Add-ZipText([IO.Compression.ZipArchive]$Zip,[string]$Name,[string]$Text){$entry=$Zip.CreateEntry($Name);$stream=$entry.Open();try{$writer=New-Object IO.StreamWriter($stream,(New-Object Text.UTF8Encoding($false)));try{$writer.Write($Text)}finally{$writer.Dispose()}}finally{$stream.Dispose()}}
function New-TestPlugin([string]$Path,[string]$Version){$zip=[IO.Compression.ZipFile]::Open($Path,[IO.Compression.ZipArchiveMode]::Create);try{Add-ZipText $zip 'deposit-build.properties' ("version=$Version`n")}finally{$zip.Dispose()}}
function New-TestRelease([string]$Path,[string]$PluginPath,[string]$Version){
    $zip=[IO.Compression.ZipFile]::Open($Path,[IO.Compression.ZipArchiveMode]::Create)
    try{$entry=$zip.CreateEntry("plugins/deposit-plugin-$Version.jar");$source=[IO.File]::OpenRead($PluginPath);$target=$entry.Open();try{$source.CopyTo($target)}finally{$target.Dispose();$source.Dispose()};Add-ZipText $zip 'release-manifest.json' '{"schemaVersion":1,"files":[]}';Add-ZipText $zip 'distribution-manifest.json' '{"schemaVersion":1,"plugins":[]}'}finally{$zip.Dispose()}
}
function Write-TestBundleManifest([string]$Path,[string]$Version,[string]$Commit){
    $payload=@(Get-PythonAcceptanceBundleTree $Path|Where-Object {$_ -ine (Join-Path $Path 'bundle.json')}|ForEach-Object{$relative=Get-PythonAcceptanceBundleRelativePath $Path $_;$item=Get-Item -LiteralPath $_;[ordered]@{path=$relative;size=[long]$item.Length;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})
    $value=[ordered]@{schemaVersion=1;version=$Version;source=[ordered]@{commit=$Commit;tag=$Version;status='clean';statusPorcelain=@()};toolVersions=[ordered]@{java='fixture-java';maven='fixture-maven';neo4j='2026.05.0';powershell='fixture-powershell'};files=$payload}
    [IO.File]::WriteAllText((Join-Path $Path 'bundle.json'),(ConvertTo-Json -InputObject $value -Depth 8),(New-Object Text.UTF8Encoding($false)))
}
function Expect-BundleFailure([scriptblock]$Action,[string]$Label,[string]$MessagePattern){
    $failure=$null
    try{& $Action|Out-Null}catch{$failure=$_}
    if(!$failure){throw "Build bundle validation accepted $Label."}
    if($MessagePattern -and $failure.Exception.Message -notmatch $MessagePattern){throw "Unexpected failure for ${Label}: $($failure.Exception.Message)"}
}
function Assert-ImportedBundleLayout([string]$ImportedRoot,[string]$Archive){
    $build=Get-DepositBuildMetadata $repo $ImportedRoot
    $expectedArchive=Join-Path $ImportedRoot ('build\'+$build.ArchiveFile)
    if($Archive -cne $expectedArchive){throw "Importer returned the wrong Install release path: $Archive"}
    $expected=@{
        'build/deposit-build.properties'='build/deposit-build.properties'
        "build/deposit-plugin/target/deposit-plugin-$version.jar"="build/deposit-plugin/target/deposit-plugin-$version.jar"
        "release/deposit-server-$version.zip"="build/deposit-server-$version.zip"
        'build/deposit-integration-tests/target/classes/org/deposit/it/ReadinessClient.class'='build/deposit-integration-tests/target/classes/org/deposit/it/ReadinessClient.class'
        'build/deposit-integration-tests/target/classes/org/deposit/it/support/ReadinessSupport.class'='build/deposit-integration-tests/target/classes/org/deposit/it/support/ReadinessSupport.class'
        'build/deposit-integration-tests/target/dependency/neo4j-java-driver-fixture.jar'='build/deposit-integration-tests/target/dependency/neo4j-java-driver-fixture.jar'
    }
    $actual=@(Get-ChildItem -LiteralPath (Join-Path $ImportedRoot 'build') -File -Recurse)
    if($actual.Count -ne $expected.Count){throw 'Imported build contains missing or unexpected files.'}
    foreach($source in $expected.Keys){
        $target=Join-Path $ImportedRoot ($expected[$source].Replace('/','\'))
        if(!(Test-Path -LiteralPath $target -PathType Leaf) -or
           (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -cne [string]$valid.Files[$source].sha256){throw "Imported output differs from its manifest: $target"}
    }
    $pluginPath=Join-Path $ImportedRoot ('build\deposit-plugin\target\'+$build.PluginFile)
    if((Get-DepositJarVersion $pluginPath) -cne $build.Version){throw 'Install cannot consume the imported plugin version.'}

    # Evaluate the real readiness classpath expression, without starting a JVM or
    # reading credentials, so a harness consumer path change is covered here.
    $tokens=$null;$errors=$null
    $runtimeAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'helpers\HarnessRuntime.ps1'),[ref]$tokens,[ref]$errors)
    if($errors.Count){throw 'HarnessRuntime.ps1 failed to parse.'}
    $readiness=$runtimeAst.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Invoke-Readiness'},$true)
    $classpath=$readiness.Body.Find({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -ceq '$arguments'},$true)
    if(!$classpath){throw 'Could not find the readiness classpath expression.'}
    $Root=$ImportedRoot;$readinessUri='bolt://127.0.0.1:17770';$arguments=$null
    . ([scriptblock]::Create($classpath.Extent.Text))
    if($arguments -notmatch '-cp "([^"]+)"\s+([A-Za-z0-9_.]+)'){throw 'Could not read the actual readiness classpath.'}
    $paths=$matches[1].Split(';');$mainClass=$matches[2].Replace('.','\')+'.class'
    if($paths.Count -ne 2 -or !(Test-Path -LiteralPath (Join-Path $paths[0] $mainClass) -PathType Leaf) -or
       !@(Get-ChildItem -Path $paths[1] -File | Where-Object Extension -eq '.jar').Count){throw 'Imported outputs do not satisfy the actual readiness classpath.'}
}
try{
    # These bundle contents are synthetic; their provenance is the explicitly
    # selected historical Server tag, not a runtime package version.
    & git -C $checkout worktree add --detach $sourceWorktree $tagRef 2>&1|Out-Null
    if($LASTEXITCODE -ne 0){throw 'Could not create the clean exact-version source worktree for bundle probes.'}
    $repo=$sourceWorktree
    $commit=(& git -C $repo rev-parse --verify HEAD 2>$null).Trim()
    if($LASTEXITCODE -ne 0 -or $commit -cne $tagCommit){throw 'The bundle probe source worktree does not match the exact-version tag.'}
    $sourceStatus=@(& git -C $repo status --porcelain 2>$null)
    if($LASTEXITCODE -ne 0 -or $sourceStatus.Count -ne 0){throw 'The exact-version bundle probe worktree is not clean.'}
    $null=New-Item -ItemType Directory -Path (Join-Path $bundleRoot 'release') -Force
    $plugin=Join-Path $bundleRoot "build\deposit-plugin\target\deposit-plugin-$version.jar"
    $metadata=Join-Path $bundleRoot 'build\deposit-build.properties'
    $archive=Join-Path $bundleRoot "release\deposit-server-$version.zip"
    $class=Join-Path $bundleRoot 'build\deposit-integration-tests\target\classes\org\deposit\it\ReadinessClient.class'
    $dependency=Join-Path $bundleRoot 'build\deposit-integration-tests\target\dependency\neo4j-java-driver-fixture.jar'
    $supportClass=Join-Path $bundleRoot 'build\deposit-integration-tests\target\classes\org\deposit\it\support\ReadinessSupport.class'
    New-Item -ItemType Directory -Path (Split-Path $plugin -Parent),(Split-Path $class -Parent),(Split-Path $dependency -Parent),(Split-Path $supportClass -Parent) -Force|Out-Null
    [IO.File]::WriteAllText($metadata,"version=$version`npluginFile=deposit-plugin-$version.jar`nprobeFile=deposit-runtime-probe-$version.jar`narchiveFile=deposit-server-$version.zip`n",(New-Object Text.UTF8Encoding($false)))
    New-TestPlugin $plugin $version
    [IO.File]::WriteAllBytes($class,[byte[]](1,2,3,4))
    [IO.File]::WriteAllBytes($dependency,[byte[]](5,6,7,8))
    [IO.File]::WriteAllBytes($supportClass,[byte[]](10,11,12))
    New-TestRelease $archive $plugin $version
    Write-TestBundleManifest $bundleRoot $version $commit
    $valid=Test-PythonAcceptanceBuildBundle -BundleRoot $bundleRoot -ExpectedVersion $version -RepositoryRoot $repo
    if($valid.Version -cne $version){throw 'Valid bundle returned an unexpected version.'}
    $importRoot=Join-Path $Root ('cache\python-acceptance-bundle-import-'+[Guid]::NewGuid().ToString('N'))
    $savedRoot=$root
    try{
        $pythonFixtureRoot=Join-Path $importRoot 'python-fixture'
        $pythonManifestDirectory=Join-Path $pythonFixtureRoot 'src\deposit'
        New-Item -ItemType Directory -Path $pythonManifestDirectory -Force|Out-Null
        $pythonManifest=[ordered]@{support=[ordered]@{target_server_version=$FixtureTag}}
        [IO.File]::WriteAllText((Join-Path $pythonManifestDirectory '_procedure_manifest.json'),($pythonManifest|ConvertTo-Json -Depth 4),(New-Object Text.UTF8Encoding($false)))
        $root=$importRoot
        foreach($state in @('absent','empty')){
            if($state -eq 'empty'){New-Item -ItemType Directory -Path (Join-Path $root 'build') -Force|Out-Null}
            $importedArchive=Import-PythonAcceptanceBuildBundle -BundleRoot $bundleRoot -ExpectedVersion $version -RepositoryRoot $repo
            Assert-ImportedBundleLayout $root $importedArchive
            Expect-BundleFailure {Import-PythonAcceptanceBuildBundle -BundleRoot $bundleRoot -ExpectedVersion $version -RepositoryRoot $repo} 'populated build destination' 'fresh, empty harness build'
            Remove-Item -LiteralPath (Join-Path $root 'build') -Recurse -Force
        }
        [IO.File]::WriteAllText((Join-Path $root 'build'),'existing file')
        Expect-BundleFailure {Import-PythonAcceptanceBuildBundle -BundleRoot $bundleRoot -ExpectedVersion $version -RepositoryRoot $repo} 'file as build destination' 'fresh, empty harness build'
        Remove-Item -LiteralPath (Join-Path $root 'build') -Force

        # Simulate a source changing after validation; copied bytes must still
        # match the validated manifest, not merely the now-changed source.
        & {
            function Test-PythonAcceptanceBuildBundle {param($BundleRoot,$ExpectedVersion,$RepositoryRoot) return $valid}
            $bytes=[IO.File]::ReadAllBytes($class)
            try{
                [IO.File]::WriteAllBytes($class,[byte[]](9,9,9))
                Expect-BundleFailure {Import-PythonAcceptanceBuildBundle -BundleRoot $bundleRoot -ExpectedVersion $version -RepositoryRoot $repo} 'source changed after validation' 'Copied build output failed verification'
            }finally{[IO.File]::WriteAllBytes($class,$bytes)}
        }
        Remove-Item -LiteralPath (Join-Path $root 'build') -Recurse -Force

        # Execute the actual suite build/install branch with phase and journal
        # boundaries stubbed; no runtime is installed and no owner state changes.
        $tokens=$null;$errors=$null
        $suiteAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Invoke-IntegrationSuite.ps1'),[ref]$tokens,[ref]$errors)
        if($errors.Count){throw 'Invoke-IntegrationSuite.ps1 failed to parse.'}
        $dispatch=$suiteAst.Find({param($node) $node -is [Management.Automation.Language.IfStatementAst] -and $node.Extent.Text.StartsWith("if(`$SelectedKind -ne 'Unit')")},$true)
        if(!$dispatch){throw 'Could not locate the suite build/install branch.'}
        foreach($selection in @('bundle','python-build','java-build','unit')){
            & {
                $phases=New-Object 'System.Collections.Generic.List[string]'
                function Invoke-SuitePhase([string]$Name,[object[]]$Extra=@()){
                    $phases.Add($Name)
                    if($Name -eq 'Build'){
                        New-Item -ItemType Directory -Path (Join-Path $root 'build') -Force|Out-Null
                        Copy-Item -LiteralPath $metadata -Destination (Join-Path $root 'build\deposit-build.properties')
                    }
                    if($Name -eq 'Install' -and ($Extra.Count -ne 2 -or $Extra[0] -cne '-ReleasePackage' -or $Extra[1] -cne (Join-Path $root "build\deposit-server-$version.zip"))){throw 'Suite Install did not receive the expected release path.'}
                }
                function Update-SuiteJournal($Path,[scriptblock]$Action){}
                $journalPath='unused-fixture'
                $SelectedKind=if($selection -eq 'unit'){'Unit'}else{'Integration'}
                $SelectedPythonTest=if($selection -in @('bundle','python-build')){'Smoke'}else{$null}
                $PythonBuildBundle=if($selection -eq 'bundle'){$bundleRoot}else{$null}
                $PythonBuildSourceRepository=$repo
                $PythonRoot=$pythonFixtureRoot
                . ([scriptblock]::Create($dispatch.Extent.Text))
                $expected=if($selection -eq 'bundle'){'Install'}elseif($selection -eq 'unit'){''}else{'Build,Package,Install'}
                if(($phases -join ',') -cne $expected){throw "Unexpected suite phases for ${selection}: $($phases -join ',')"}
                if($selection -eq 'bundle'){Assert-ImportedBundleLayout $root $archive}
            }
            if(Test-Path -LiteralPath (Join-Path $root 'build')){Remove-Item -LiteralPath (Join-Path $root 'build') -Recurse -Force}
        }
    }finally{
        $root=$savedRoot
        if(Test-Path -LiteralPath $importRoot){Assert-PythonAcceptanceBundlePlainPath $importRoot;Remove-Item -LiteralPath $importRoot -Recurse -Force}
    }

    $manifestPath=Join-Path $bundleRoot 'bundle.json'
    $manifestBytes=[IO.File]::ReadAllBytes($manifestPath)
    $metadataBytes=[IO.File]::ReadAllBytes($metadata)
    [IO.File]::WriteAllText($metadata,"version=$version`npluginFile=deposit-plugin-$version.jar`narchiveFile=deposit-server-$version.zip`n",(New-Object Text.UTF8Encoding($false)))
    Write-TestBundleManifest $bundleRoot $version $commit
    Expect-BundleFailure {Test-PythonAcceptanceBuildBundle -BundleRoot $bundleRoot -ExpectedVersion $version -RepositoryRoot $repo} 'rehashed metadata missing probeFile' 'Invalid build artifact name metadata'
    [IO.File]::WriteAllBytes($metadata,$metadataBytes)
    [IO.File]::WriteAllBytes($manifestPath,$manifestBytes)

    $manifest=Get-Content -LiteralPath $manifestPath -Raw|ConvertFrom-Json
    $manifest.version='1.0.0'
    [IO.File]::WriteAllText($manifestPath,($manifest|ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
    Expect-BundleFailure {Test-PythonAcceptanceBuildBundle -BundleRoot $bundleRoot -ExpectedVersion $version -RepositoryRoot $repo} 'wrong version' 'version does not match'
    [IO.File]::WriteAllBytes($manifestPath,$manifestBytes)

    $classBytes=[IO.File]::ReadAllBytes($class)
    [IO.File]::WriteAllBytes($class,[byte[]](9,9,9))
    Expect-BundleFailure {Test-PythonAcceptanceBuildBundle -BundleRoot $bundleRoot -ExpectedVersion $version -RepositoryRoot $repo} 'tampered readiness bytes' 'SHA-256 verification'
    [IO.File]::WriteAllBytes($class,$classBytes)

    Remove-Item -LiteralPath $class -Force
    Expect-BundleFailure {Test-PythonAcceptanceBuildBundle -BundleRoot $bundleRoot -ExpectedVersion $version -RepositoryRoot $repo} 'missing readiness class' 'files differ'
    Write-TestBundleManifest $bundleRoot $version $commit
    Expect-BundleFailure {Test-PythonAcceptanceBuildBundle -BundleRoot $bundleRoot -ExpectedVersion $version -RepositoryRoot $repo} 'readiness class omitted from manifest' 'missing required output'
    [IO.File]::WriteAllBytes($class,$classBytes)
    [IO.File]::WriteAllBytes($manifestPath,$manifestBytes)

    Remove-Item -LiteralPath $dependency -Force
    Expect-BundleFailure {Test-PythonAcceptanceBuildBundle -BundleRoot $bundleRoot -ExpectedVersion $version -RepositoryRoot $repo} 'missing readiness dependency' 'files differ'
    Write-TestBundleManifest $bundleRoot $version $commit
    Expect-BundleFailure {Test-PythonAcceptanceBuildBundle -BundleRoot $bundleRoot -ExpectedVersion $version -RepositoryRoot $repo} 'readiness dependency omitted from manifest' 'readiness dependencies are missing'
    [IO.File]::WriteAllBytes($dependency,[byte[]](5,6,7,8))
    [IO.File]::WriteAllBytes($manifestPath,$manifestBytes)

    Expect-BundleFailure {Test-PythonAcceptanceBuildBundle -BundleRoot (Join-Path $bundleRoot 'missing') -ExpectedVersion $version -RepositoryRoot $repo} 'missing bundle' 'bundle is missing'
    $suite=Join-Path $PSScriptRoot 'Invoke-IntegrationSuite.ps1'
    Expect-BundleFailure {& $suite -PythonBuildBundle $bundleRoot} 'bundle without Python selection' 'available only with SelectedPythonTest'
    $pythonSelection=@{SelectedPythonTest='Smoke';PythonRoot=$pythonFixtureRoot;PythonEnvironment=(Join-Path $Root 'tools\python-tests');PythonBuildBundle=$bundleRoot}
    Expect-BundleFailure {& $suite @pythonSelection -Phase Setup} 'setup phase with bundle' 'requires a Run phase'
    Expect-BundleFailure {& $suite @pythonSelection -SetupOnly} 'setup-only with bundle' 'requires a Run phase'
    Expect-BundleFailure {& $suite @pythonSelection -SelectedKind Unit -SelectedTest FixtureTest} 'mixed Python and Java selections' 'one independent test or scenario'
    Write-Output "PASS: synthetic bundle provenance tag=$FixtureTag commit=$commit; metadata/Install/readiness consumers, absent/empty build import, manifest copy hashes, tamper, missing inputs, wrong version, and incompatible parameters"
} finally {
    Assert-PythonAcceptanceBundlePlainPath $bundleRoot
    Remove-Item -LiteralPath $bundleRoot -Recurse -Force
    if(Test-Path -LiteralPath $sourceWorktree -PathType Container){
        & git -C $checkout worktree remove --force $sourceWorktree 2>&1|Out-Null
        if($LASTEXITCODE -ne 0 -or (Test-Path -LiteralPath $sourceWorktree)){throw 'Could not clean up the exact-version bundle probe worktree.'}
    }
}
