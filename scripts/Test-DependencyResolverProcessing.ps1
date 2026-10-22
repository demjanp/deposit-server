[CmdletBinding()]
param([string]$EvidenceRoot=(Join-Path $PSScriptRoot '..\cache\project-run-evidence\deposit-reuse-20261021-r6'))
$ErrorActionPreference='Stop'
trap { Write-Host $_.InvocationInfo.PositionMessage; Write-Host $_.ScriptStackTrace; break }
$script:repo=Split-Path $PSScriptRoot -Parent
$script:temp=Join-Path ([IO.Path]::GetTempPath()) ('resolver-processing-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $script:temp -Force | Out-Null
$script:results=New-Object System.Collections.Generic.List[string]
function Assert-True([bool]$Condition,[string]$Label) { if(!$Condition) {throw "FAIL $Label"}; $script:results.Add("PASS $Label") }
function New-ProcessingContext([string]$Resolver,[string[]]$Lines,[hashtable]$Options=@{}) {
    $Root=Join-Path $script:temp ([guid]::NewGuid().ToString('N'))
    $work=Join-Path $Root 'work'; $lib=Join-Path $Root 'neo4j\lib'; $cache=Join-Path $Root 'cache'
    New-Item -ItemType Directory -Force $work,$lib,$cache,(Join-Path $cache ("$($Resolver.ToLowerInvariant())-dependencies")) | Out-Null
    $manifest=[pscustomobject]@{neo4jHome='neo4j'}
    $overrides=[pscustomobject]@{}
    $dependencies=New-Object System.Collections.Generic.List[object]
    $missingLicenses=New-Object System.Collections.Generic.List[string]
    if($Resolver -eq 'Format') {
       $spatialLock=[pscustomobject]@{dependencies=@()}; $ocflLock=[pscustomobject]@{dependencies=@()}
    } else {$spatialLock=$null;$ocflLock=$null}
    $specs=@()
    foreach($item in $Options.Artifacts) {
        $dir=Join-Path $Root ("m2\"+$item.group.Replace('.','\')+"\"+$item.artifact+"\"+$item.version)
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        $file=Join-Path $dir $item.file
        [IO.File]::WriteAllBytes($file,[byte[]]$item.bytes)
        if($item.origin -ne $null) {[IO.File]::WriteAllText((Join-Path $dir '_remote.repositories'),$item.origin)}
        if($item.licenses) {[IO.File]::WriteAllText([IO.Path]::ChangeExtension($file,'pom'),$item.licenses)}
        $specs += [pscustomobject]@{group=$item.group;artifact=$item.artifact;version=$item.version;file=$file}
        if($item.runtime) {
            $runtimeNames=if($item.runtimeNames){@($item.runtimeNames)}else{@($(if($item.runtimeName){$item.runtimeName}else{$item.file}))}
            foreach($rname in $runtimeNames) {
                $rbytes=if($item.runtimeBytes){[byte[]]$item.runtimeBytes}else{[byte[]]$item.bytes}
                [IO.File]::WriteAllBytes((Join-Path $lib $rname),$rbytes)
            }
        }
        if($item.override) {$overrides | Add-Member -NotePropertyName "$($item.group):$($item.artifact):$($item.version)" -NotePropertyValue @(@{name='Override';url='https://override'})}
    }
    if($Options.SpatialLocks) {$spatialLock=[pscustomobject]@{dependencies=@($Options.SpatialLocks)}}
    if($Options.OcflLocks) {$ocflLock=[pscustomobject]@{dependencies=@($Options.OcflLocks)}}
    [IO.File]::WriteAllLines((Join-Path $work 'dependencies.txt'),$Lines)
    return @{Root=$Root;work=$work;manifest=$manifest;overrides=$overrides;dependencies=$dependencies;missingLicenses=$missingLicenses;spatialLock=$spatialLock;ocflLock=$ocflLock;specs=$specs}
}
function Invoke-Processing([scriptblock]$Loop,[scriptblock]$LicenseFunction,[hashtable]$Context) {
    $Root=$Context.Root; $work=$Context.work; $manifest=$Context.manifest; $overrides=$Context.overrides
    $dependencies=$Context.dependencies; $missingLicenses=$Context.missingLicenses
    $spatialLock=$Context.spatialLock; $ocflLock=$Context.ocflLock
    $failure=$null
    if($LicenseFunction) {. $LicenseFunction}
    elseif(Test-Path (Join-Path $script:repo 'scripts\DependencyResolverHelpers.ps1')) {. (Join-Path $script:repo 'scripts\DependencyResolverHelpers.ps1')}
    try { & $Loop } catch { $failure=$_.Exception.Message }
    return @{dependencies=@($dependencies.ToArray());missing=@($missingLicenses.ToArray());failure=$failure;Root=$Root}
}
function Get-Loop([string]$Resolver) {
    $path=Join-Path $script:repo "scripts\Resolve-$Resolver`Dependencies.ps1"
    $text=Get-Content -LiteralPath $path -Raw; $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
    if($errors.Count) {throw "$Resolver parse failed: $($errors[0].Message)"}
    $loops=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.ForEachStatementAst] -and $n.Extent.Text -match '(?s)^foreach\(\$line in \(Get-Content "\$work\\dependencies\.txt"\)\)'},$true))
    if($loops.Count -ne 1) {throw "$Resolver AST selector expected exactly one dependency-processing foreach, found $($loops.Count)"}
    $functions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Get-Licenses'},$true))
    if($functions.Count -gt 1) {throw "$Resolver AST selector expected at most one legacy Get-Licenses function, found $($functions.Count)"}
    $license=$null; if($functions.Count -eq 1) {$license=[scriptblock]::Create($functions[0].Extent.Text)}
    return @{loop=[scriptblock]::Create($loops[0].Extent.Text);license=$license}
}
try {
 foreach($resolver in @('Spatial','Ocfl','Format')) {
    $selected=Get-Loop $resolver; $loop=$selected.loop
    $pom='<project xmlns="urn:test"><licenses><license><name>MIT</name><url>https://license</url></license></licenses></project>'
    $artifact=@{group='example.org';artifact='alpha';version='1.2';file='alpha-1.2.jar';bytes=[byte[]](1,2,3);origin='alpha-1.2.jar>central=';licenses=$pom;override=$true}
    $line='  example.org:alpha:jar:1.2:compile:'+ (Join-Path $script:temp 'placeholder')
    $ctx=New-ProcessingContext $resolver @('not a dependency','  example.org:alpha:jar:1.2:test:C:\ignored\alpha-1.2.jar','  example.org:alpha:war:1.2:compile:C:\ignored\alpha-1.2.jar',$line) @{Artifacts=@($artifact)}
    $realLine='  example.org:alpha:jar:1.2:compile:'+ $ctx.specs[0].file
    [IO.File]::WriteAllLines((Join-Path $ctx.work 'dependencies.txt'),@('not a dependency','  example.org:alpha:jar:1.2:test:C:\ignored\alpha-1.2.jar','  example.org:alpha:war:1.2:compile:C:\ignored\alpha-1.2.jar',$realLine))
    $out=Invoke-Processing $loop $selected.license $ctx
    Assert-True ($out.dependencies.Count -eq 1 -and $out.failure -eq $null) "$resolver ignores unparsable lines and captures one accepted compile dependency (count=$($out.dependencies.Count), failure=$($out.failure), line=$realLine)"
    $dep=$out.dependencies[0]; $hash='039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81'
    Assert-True ($dep.groupId -ceq 'example.org' -and $dep.artifactId -ceq 'alpha' -and $dep.version -ceq '1.2' -and $dep.file -ceq 'alpha-1.2.jar') "$resolver captures coordinate fields and filename"
    Assert-True ((Get-FileHash $ctx.specs[0].file -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $hash -and $dep.url -ceq 'https://repo.maven.apache.org/maven2/example/org/alpha/1.2/alpha-1.2.jar' -and $dep.sha256 -is [string] -and $dep.sha256 -ceq $hash -and $dep.sha256 -cmatch '^[0-9a-f]{64}$') "$resolver Central URL and lowercase SHA-256 string metadata"
    Assert-True ($dep.licenses.Count -eq 1 -and $dep.licenses[0].name -ceq 'MIT' -and $dep.ship -eq $true -and (Test-Path (Join-Path $ctx.Root "cache\$($resolver.ToLowerInvariant())-dependencies\alpha-1.2.jar"))) "$resolver license metadata and shipped copy decision"
    $osgeo=@{group='example.org';artifact='geo';version='2';file='geo-2.jar';bytes=[byte[]](4,5);origin='geo-2.jar>osgeo=';licenses=$pom}
    $ctx2=New-ProcessingContext $resolver @('  example.org:geo:jar:2:runtime:FILE') @{Artifacts=@($osgeo)}
    $real='  example.org:geo:jar:2:runtime:'+$ctx2.specs[0].file
    [IO.File]::WriteAllLines((Join-Path $ctx2.work 'dependencies.txt'),@($real))
    $out2=Invoke-Processing $loop $selected.license $ctx2
    Assert-True ($out2.dependencies[0].url -ceq 'https://repo.osgeo.org/repository/release/example/org/geo/2/geo-2.jar') "$resolver exact osgeo origin marker selects repository (url=$($out2.dependencies[0].url), error=$($out2.failure))"
    $unrelated=@{group='example.org';artifact='other';version='3';file='other-3.jar';bytes=[byte[]](6);origin='other-3.jar>osgeo-snapshot=';licenses=$pom}
    $ctx3=New-ProcessingContext $resolver @('') @{Artifacts=@($unrelated)}; $real='  example.org:other:jar:3:compile:'+$ctx3.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctx3.work 'dependencies.txt'),@($real))
    $out3=Invoke-Processing $loop $selected.license $ctx3
    Assert-True ($out3.dependencies[0].url.StartsWith('https://repo.maven.apache.org/')) "$resolver unrelated repository marker retains Central"
    $missingOrigin=@{group='example.org';artifact='noorigin';version='4';file='noorigin-4.jar';bytes=[byte[]](7);licenses=$pom}
    $ctx4=New-ProcessingContext $resolver @('') @{Artifacts=@($missingOrigin)}; $real='  example.org:noorigin:jar:4:compile:'+$ctx4.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctx4.work 'dependencies.txt'),@($real))
    $out4=Invoke-Processing $loop $selected.license $ctx4
    Assert-True ($out4.failure -match 'remote\.repositories|Cannot find path|could not be found') "$resolver missing origin file preserves current terminating error"
    # A same-coordinate duplicate stays duplicated in metadata; shipping copies remain a single destination.
    $ctx5=New-ProcessingContext $resolver @('') @{Artifacts=@($artifact)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctx5.specs[0].file
    [IO.File]::WriteAllLines((Join-Path $ctx5.work 'dependencies.txt'),@($real,$real)); $out5=Invoke-Processing $loop $selected.license $ctx5
    Assert-True ($out5.dependencies.Count -eq 2 -and $out5.dependencies[0].artifactId -ceq $out5.dependencies[1].artifactId) "$resolver duplicate lines retain duplicate dependency metadata"

    $runtimeItem=@{group='example.org';artifact='alpha';version='1.2';file='alpha-1.2.jar';bytes=[byte[]](1,2,3);origin='alpha-1.2.jar>central=';licenses=$pom;runtime=$true;runtimeName='alpha-9.jar';runtimeBytes=[byte[]](9)}
    $ctx6=New-ProcessingContext $resolver @('') @{Artifacts=@($runtimeItem)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctx6.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctx6.work 'dependencies.txt'),@($real)); $out6=Invoke-Processing $loop $selected.license $ctx6
    if($resolver -eq 'Spatial') {
        Assert-True ($out6.failure -eq $null -and $out6.dependencies[0].ship -eq $false -and $out6.dependencies[0].providedByRuntime.Count -eq 1 -and !(Test-Path (Join-Path $ctx6.Root 'cache\spatial-dependencies\alpha-1.2.jar'))) 'Spatial runtime collision is accepted and suppresses shipment without a new conflict throw'
    } else {
        Assert-True ($out6.failure -match "$resolver dependency conflicts with provided runtime" -and $out6.dependencies.Count -eq 0) "$resolver rejects runtime filename conflict"
    }

    if($resolver -eq 'Ocfl') {
        $exact=@{group='example.org';artifact='alpha';version='1.2';file='alpha-1.2.jar';bytes=[byte[]](1,2,3);origin='alpha-1.2.jar>central=';licenses=$pom;runtime=$true;runtimeName='alpha-1.2.jar'}
        $ctx7=New-ProcessingContext $resolver @('') @{Artifacts=@($exact)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctx7.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctx7.work 'dependencies.txt'),@($real)); $out7=Invoke-Processing $loop $selected.license $ctx7
        Assert-True ($out7.failure -eq $null -and $out7.dependencies[0].ship -eq $false -and $out7.dependencies[0].providedByRuntime[0] -ceq 'alpha-1.2.jar') 'OCFL accepts one exact runtime name and hash match'
        foreach($bad in @(@{name='alpha-2.jar';bytes=[byte[]](1,2,3)},@{name='alpha-1.2.jar';bytes=[byte[]](8,8,8)},@{names=@('alpha-1.2.jar','alpha-2.jar')})) {
            $item=@{group='example.org';artifact='alpha';version='1.2';file='alpha-1.2.jar';bytes=[byte[]](1,2,3);origin='alpha-1.2.jar>central=';licenses=$pom;runtime=$true}
            if($bad.name) {$item.runtimeName=$bad.name;$item.runtimeBytes=$bad.bytes} else {$item.runtimeNames=$bad.names}
            $ctxBad=New-ProcessingContext $resolver @('') @{Artifacts=@($item)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctxBad.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctxBad.work 'dependencies.txt'),@($real)); $badOut=Invoke-Processing $loop $selected.license $ctxBad
            Assert-True ($badOut.failure -match 'OCFL dependency conflicts with provided runtime' -and $badOut.dependencies.Count -eq 0) "OCFL rejects wrong runtime name/hash or multiple matching runtime jars"
        }
        $noLicenseRuntime=@{group='example.org';artifact='alpha';version='1.2';file='alpha-1.2.jar';bytes=[byte[]](1,2,3);origin='alpha-1.2.jar>central=';runtime=$true;runtimeName='alpha-9.jar';runtimeBytes=[byte[]](9)}
        $ctxOrder=New-ProcessingContext $resolver @('') @{Artifacts=@($noLicenseRuntime)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctxOrder.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctxOrder.work 'dependencies.txt'),@($real)); $orderOut=Invoke-Processing $loop $selected.license $ctxOrder
        Assert-True ($orderOut.failure -match 'OCFL dependency conflicts with provided runtime' -and $orderOut.missing.Count -eq 1 -and $orderOut.missing[0] -ceq 'example.org:alpha:1.2') 'OCFL license/override processing and unresolved accumulation precede runtime-conflict failure'
    }

    if($resolver -eq 'Format') {
        $digest=(Get-FileHash $ctx.specs[0].file -Algorithm SHA256).Hash.ToLowerInvariant()
        $shared=@{artifactId='alpha';ship=$true;file='alpha-1.2.jar';version='1.2';sha256=$digest}
        $ctx7=New-ProcessingContext $resolver @('') @{Artifacts=@($artifact);SpatialLocks=@($shared)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctx7.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctx7.work 'dependencies.txt'),@($real)); $out7=Invoke-Processing $loop $selected.license $ctx7
        Assert-True ($out7.failure -eq $null -and $out7.dependencies[0].ship -eq $false -and $out7.dependencies[0].providedByExistingLock[0] -ceq 'alpha-1.2.jar' -and !(Test-Path (Join-Path $ctx7.Root 'cache\format-dependencies\alpha-1.2.jar'))) 'Format matching shipped existing lock suppresses copy and records existing lock'
        $ctxOcflLock=New-ProcessingContext $resolver @('') @{Artifacts=@($artifact);OcflLocks=@($shared)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctxOcflLock.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctxOcflLock.work 'dependencies.txt'),@($real)); $outOcflLock=Invoke-Processing $loop $selected.license $ctxOcflLock
        Assert-True ($outOcflLock.failure -eq $null -and $outOcflLock.dependencies[0].ship -eq $false -and $outOcflLock.dependencies[0].providedByExistingLock[0] -ceq 'alpha-1.2.jar') 'Format compares matching shipped OCFL lock entries'
        foreach($badLock in @(@{file='alpha-other.jar';version='1.2';sha256=$digest},@{file='alpha-1.2.jar';version='9';sha256=$digest},@{file='alpha-1.2.jar';version='1.2';sha256=('0'*64)})) {
            $sharedBad=@{artifactId='alpha';ship=$true;file=$badLock.file;version=$badLock.version;sha256=$badLock.sha256}
            $ctxBad=New-ProcessingContext $resolver @('') @{Artifacts=@($artifact);SpatialLocks=@($sharedBad)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctxBad.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctxBad.work 'dependencies.txt'),@($real)); $badOut=Invoke-Processing $loop $selected.license $ctxBad
            Assert-True ($badOut.failure -match 'Format dependency conflicts with an already locked plugin dependency: alpha') 'Format rejects existing-lock filename, version or hash mismatch'
        }
        $ocflBad=@{artifactId='alpha';ship=$true;file='alpha-1.2.jar';version='1.2';sha256=('0'*64)}
        $ctxBadOcfl=New-ProcessingContext $resolver @('') @{Artifacts=@($artifact);OcflLocks=@($ocflBad)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctxBadOcfl.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctxBadOcfl.work 'dependencies.txt'),@($real)); $badOcfl=Invoke-Processing $loop $selected.license $ctxBadOcfl
        Assert-True ($badOcfl.failure -match 'Format dependency conflicts with an already locked plugin dependency: alpha') 'Format rejects mismatched shipped OCFL lock hash'
        $unshipped=@{artifactId='alpha';ship=$false;file='wrong.jar';version='wrong';sha256='wrong'}
        $ctxUnshipped=New-ProcessingContext $resolver @('') @{Artifacts=@($artifact);SpatialLocks=@($unshipped)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctxUnshipped.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctxUnshipped.work 'dependencies.txt'),@($real)); $outUnshipped=Invoke-Processing $loop $selected.license $ctxUnshipped
        Assert-True ($outUnshipped.failure -eq $null -and $outUnshipped.dependencies[0].ship -eq $true -and $outUnshipped.dependencies[0].providedByExistingLock.Count -eq 0) 'Format ignores non-shipped existing lock entries'
        $noLicenseRuntime=@{group='example.org';artifact='alpha';version='1.2';file='alpha-1.2.jar';bytes=[byte[]](1,2,3);origin='alpha-1.2.jar>central=';runtime=$true;runtimeName='alpha-9.jar';runtimeBytes=[byte[]](9)}
        $ctxRuntimeOrder=New-ProcessingContext $resolver @('') @{Artifacts=@($noLicenseRuntime)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctxRuntimeOrder.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctxRuntimeOrder.work 'dependencies.txt'),@($real)); $runtimeOrder=Invoke-Processing $loop $selected.license $ctxRuntimeOrder
        Assert-True ($runtimeOrder.failure -match 'Format dependency conflicts with provided runtime' -and $runtimeOrder.missing.Count -eq 1) 'Format license/override processing and unresolved accumulation precede runtime-conflict failure'
        $hashRuntime=@{group='example.org';artifact='alpha';version='1.2';file='alpha-1.2.jar';bytes=[byte[]](1,2,3);origin='alpha-1.2.jar>central=';licenses=$pom;runtime=$true;runtimeName='alpha-1.2.jar';runtimeBytes=[byte[]](9)}
        $ctxHashRuntime=New-ProcessingContext $resolver @('') @{Artifacts=@($hashRuntime)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctxHashRuntime.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctxHashRuntime.work 'dependencies.txt'),@($real)); $hashRuntimeOut=Invoke-Processing $loop $selected.license $ctxHashRuntime
        Assert-True ($hashRuntimeOut.failure -match 'Format dependency conflicts with provided runtime' -and $hashRuntimeOut.dependencies.Count -eq 0) 'Format rejects same-name runtime jar with different hash'
        $missingOriginItem=@{group='example.org';artifact='alpha';version='1.2';file='alpha-1.2.jar';bytes=[byte[]](1,2,3);licenses=$pom}
        $orderBadLock=@{artifactId='alpha';ship=$true;file='wrong.jar';version='wrong';sha256='wrong'}
        $ctxOrder=New-ProcessingContext $resolver @('') @{Artifacts=@($missingOriginItem);SpatialLocks=@($orderBadLock)}; $real='  example.org:alpha:jar:1.2:compile:'+$ctxOrder.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctxOrder.work 'dependencies.txt'),@($real)); $orderOut=Invoke-Processing $loop $selected.license $ctxOrder
        Assert-True ($orderOut.failure -match 'Format dependency conflicts with an already locked plugin dependency' -and $orderOut.failure -notmatch 'remote\.repositories|Cannot find path') "Format existing-lock conflict check precedes origin lookup (actual=$($orderOut.failure))"
    }

    $overrideItem=@{group='example.org';artifact='no-license';version='5';file='no-license-5.jar';bytes=[byte[]](5,5);origin='no-license-5.jar>central=';override=$true}
    $ctxOverride=New-ProcessingContext $resolver @('') @{Artifacts=@($overrideItem)}; $real='  example.org:no-license:jar:5:compile:'+$ctxOverride.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctxOverride.work 'dependencies.txt'),@($real)); $overrideOut=Invoke-Processing $loop $selected.license $ctxOverride
    Assert-True ($overrideOut.dependencies[0].licenses.Count -eq 1 -and $overrideOut.dependencies[0].licenses[0].name -ceq 'Override' -and $overrideOut.missing.Count -eq 0) "$resolver applies override only when traversal yields no license"
    $unresolvedItem=@{group='example.org';artifact='unlicensed';version='6';file='unlicensed-6.jar';bytes=[byte[]](6,6);origin='unlicensed-6.jar>central='}
    $ctxUnresolved=New-ProcessingContext $resolver @('') @{Artifacts=@($unresolvedItem)}; $real='  example.org:unlicensed:jar:6:compile:'+$ctxUnresolved.specs[0].file; [IO.File]::WriteAllLines((Join-Path $ctxUnresolved.work 'dependencies.txt'),@($real)); $unresolvedOut=Invoke-Processing $loop $selected.license $ctxUnresolved
    Assert-True ($unresolvedOut.missing.Count -eq 1 -and $unresolvedOut.missing[0] -ceq 'example.org:unlicensed:6') "$resolver preserves unresolved license accumulation"
    Remove-Item -LiteralPath $ctx.Root,$ctx2.Root,$ctx3.Root,$ctx4.Root,$ctx5.Root -Recurse -Force
 }
    $results | Set-Content -Encoding utf8 (Join-Path $EvidenceRoot 'processing-fixtures.log')
    "PASS $($results.Count) assertions" | Add-Content -Encoding utf8 (Join-Path $EvidenceRoot 'processing-fixtures.log')
    Get-Content (Join-Path $EvidenceRoot 'processing-fixtures.log')
} finally { if(Test-Path $script:temp) {Remove-Item -LiteralPath $script:temp -Recurse -Force} }
