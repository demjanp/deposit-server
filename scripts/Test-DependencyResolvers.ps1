[CmdletBinding()]
param([string]$EvidenceRoot=(Join-Path $PSScriptRoot '..\cache\project-run-evidence\deposit-reuse-20261021-r6'))
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$resolverNames=@('Spatial','Ocfl','Format')
$temp=Join-Path ([IO.Path]::GetTempPath()) ('resolver-fixtures-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp -Force | Out-Null
$results=New-Object System.Collections.Generic.List[string]
function Assert-Equal($Expected,$Actual,[string]$Label) {
    if(($Expected | ConvertTo-Json -Depth 20 -Compress) -cne ($Actual | ConvertTo-Json -Depth 20 -Compress)) { throw "$Label expected $($Expected | ConvertTo-Json -Depth 20 -Compress), got $($Actual | ConvertTo-Json -Depth 20 -Compress)" }
    $script:results.Add("PASS $Label")
}
function Write-Pom([string]$Path,[string]$Body) {
    $parent=Split-Path $Path -Parent
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    [IO.File]::WriteAllText($Path,$Body,[Text.UTF8Encoding]::new($false))
}
try {
    foreach($name in $resolverNames) {
        $path=Join-Path $PSScriptRoot "Resolve-$name`Dependencies.ps1"
        $text=Get-Content -LiteralPath $path -Raw
        $tokens=$null; $parseErrors=$null
        $ast=[Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$parseErrors)
        if($parseErrors.Count) { throw "$name baseline source parse failed: $($parseErrors[0].Message)" }
        $functions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Get-Licenses'},$true))
        $loops=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.ForEachStatementAst] -and $n.Extent.Text -match '(?s)^foreach\(\$line in \(Get-Content "\$work\\dependencies\.txt"\)\)'},$true))
        if($functions.Count -eq 1) {
            $funcText=$functions[0].Extent.Text
            . ([scriptblock]::Create($funcText))
            $licenseCall={param($Pom) Get-Licenses $Pom}
            $results.Add("AST $name selected legacy Get-Licenses function=1")
        } elseif($functions.Count -eq 0) {
            $helperPath=Join-Path $PSScriptRoot 'DependencyResolverHelpers.ps1'
            $helperText=Get-Content -LiteralPath $helperPath -Raw; $helperTokens=$null; $helperErrors=$null
            $helperAst=[Management.Automation.Language.Parser]::ParseInput($helperText,[ref]$helperTokens,[ref]$helperErrors)
            if($helperErrors.Count) { throw "helper source parse failed: $($helperErrors[0].Message)" }
            $functions=@($helperAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Get-ResolverLicenses'},$true))
            if($functions.Count -ne 1) { throw "$name helper AST selector expected exactly one Get-ResolverLicenses function, found $($functions.Count)" }
            . ([scriptblock]::Create($functions[0].Extent.Text))
            $licenseCall={param($Pom) Get-ResolverLicenses $global:Root $Pom}
            $results.Add("AST $name selected helper Get-ResolverLicenses function=1")
        } else { throw "$name AST selector expected zero or one Get-Licenses function, found $($functions.Count)" }
        if($loops.Count -ne 1) { throw "$name baseline AST selector expected exactly one dependency-processing foreach, found $($loops.Count)" }
        $results.Add("AST $name function=$($functions.Count) foreach=$($loops.Count) source=$path")
        $Root=Join-Path $temp "$name-root"
        $repoPom=Join-Path $Root 'maven-repository\g\parent\1\parent-1.pom'
        $childPom=Join-Path $Root 'fixture\child.pom'
        $directPom=Join-Path $Root 'fixture\direct.pom'
        Write-Pom $repoPom '<project xmlns="urn:m"><licenses><license><name>Parent A</name><url>https://parent/a</url></license><license><name>Parent B</name><url>https://parent/b</url></license></licenses></project>'
        Write-Pom $childPom '<project xmlns="urn:m"><parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version><relativePath>wrong/local/pom.xml</relativePath></parent><dependencies><dependency><licenses><license><name>Nested noise</name></license></licenses></dependency></dependencies></project>'
        Write-Pom $directPom '<project xmlns="urn:m"><licenses><license><name>Direct A</name><url>https://direct/a</url></license><license><name>Direct A</name><url>https://direct/a</url></license></licenses><parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent></project>'
        $global:Root=$Root
        $expectedParentHash='0825093587e9e9efa004ca10ec2d2a03f1989d8d923b155e308b065572f093aa'
        $parentResult=@(& $licenseCall $childPom)
        Assert-Equal @(@{name='Parent A';url='https://parent/a';pomSha256=$expectedParentHash},@{name='Parent B';url='https://parent/b';pomSha256=$expectedParentHash}) $parentResult "$name namespaced parent license order, local-name selection, repository coordinates, POM hash"
        $directHash='d9b17c66ed6eee1d419c52da9b48a08740d0f013728194af2817d6ece7d20e76'
        Assert-Equal @(@{name='Direct A';url='https://direct/a';pomSha256=$directHash},@{name='Direct A';url='https://direct/a';pomSha256=$directHash}) @(& $licenseCall $directPom) "$name direct licenses preserve order and duplicates and suppress inheritance"
        $none=Join-Path $Root 'fixture\absent.pom'
        Assert-Equal @() @(& $licenseCall $none) "$name missing POM returns empty"
        $noLicense=Join-Path $Root 'fixture\no-parent.pom'
        Write-Pom $noLicense '<project><dependencies><dependency><licenses><license><name>Nested</name></license></licenses></dependency></dependencies></project>'
        Assert-Equal @() @(& $licenseCall $noLicense) "$name absent direct and parent licenses returns empty"
        $missingParent=Join-Path $Root 'fixture\missing-parent.pom'
        Write-Pom $missingParent '<project><parent><groupId>missing</groupId><artifactId>nothing</artifactId><version>99</version></parent></project>'
        Assert-Equal @() @(& $licenseCall $missingParent) "$name missing parent POM returns empty"
        foreach($depth in 0..13) {
            for($i=0;$i -le $depth;$i++) {
                $current=Join-Path $Root ("maven-repository\g\a$i\v\a$i-v.pom")
                if($i -lt $depth) {$body="<project><parent><groupId>g</groupId><artifactId>a$($i+1)</artifactId><version>v</version></parent></project>"}
                else {$body='<project><licenses><license><name>Boundary</name><url>u</url></license></licenses></project>'}
                Write-Pom $current $body
            }
            $global:Root=$Root
            $boundary=Join-Path $Root 'maven-repository\g\a0\v\a0-v.pom'
            $actual=@(& $licenseCall $boundary)
            if($depth -le 12) { if($actual.Count -ne 1 -or $actual[0].name -ne 'Boundary') {throw "$name depth $depth should resolve a license"}; if($depth -eq 12) {$results.Add("PASS $name recursion depth 12 remains eligible")} }
            else {Assert-Equal @() $actual "$name recursion depth 13 returns empty"}
        }
        $cycleRoot=Join-Path $Root 'maven-repository'
        Write-Pom (Join-Path $cycleRoot 'g\a\1\a-1.pom') '<project><parent><groupId>g</groupId><artifactId>b</artifactId><version>1</version></parent></project>'
        Write-Pom (Join-Path $cycleRoot 'g\b\1\b-1.pom') '<project><parent><groupId>g</groupId><artifactId>a</artifactId><version>1</version></parent></project>'
        $global:Root=$Root
        $cycleResult=@(& $licenseCall (Join-Path $cycleRoot 'g\a\1\a-1.pom'))
        Assert-Equal @() $cycleResult "$name cyclic parent chain is bounded and empty"
        if(Test-Path Function:\Get-Licenses) {Remove-Item Function:\Get-Licenses}
        if(Test-Path Function:\Get-ResolverLicenses) {Remove-Item Function:\Get-ResolverLicenses}
    }
    $results | Set-Content -Encoding utf8 (Join-Path $EvidenceRoot 'traversal-fixtures.log')
    "PASS $($results.Count) assertions" | Add-Content -Encoding utf8 (Join-Path $EvidenceRoot 'traversal-fixtures.log')
    Get-Content (Join-Path $EvidenceRoot 'traversal-fixtures.log')
} finally {
    if(Test-Path $temp) {Remove-Item -LiteralPath $temp -Recurse -Force}
}
