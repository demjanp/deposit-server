[CmdletBinding()]
param([string]$EvidenceRoot=(Join-Path $PSScriptRoot '..\cache\project-run-evidence\deposit-reuse-20261021-r6'))
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$log=New-Object System.Collections.Generic.List[string]
$helper=Join-Path $PSScriptRoot 'DependencyResolverHelpers.ps1'
$helperText=Get-Content -LiteralPath $helper -Raw
$tokens=$null;$errors=$null
$helperAst=[Management.Automation.Language.Parser]::ParseInput($helperText,[ref]$tokens,[ref]$errors)
if($errors.Count) {throw "helper parse failed: $($errors[0].Message)"}
$helperFunctions=@($helperAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst]},$true))
if($helperFunctions.Count -ne 4) {throw "expected four helper function definitions, found $($helperFunctions.Count)"}
$nonFunction=@($helperAst.EndBlock.Statements | Where-Object {$_ -isnot [Management.Automation.Language.FunctionDefinitionAst]})
if($nonFunction.Count) {throw "function-only helper contains executable top-level statements: $($nonFunction[0].Extent.Text)"}
$log.Add("PASS helper parses and contains exactly four function definitions with no executable top-level statements")
foreach($name in @('Spatial','Ocfl','Format')) {
    $path=Join-Path $PSScriptRoot "Resolve-$name`Dependencies.ps1"
    $source=Get-Content -LiteralPath $path -Raw; $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
    if($errors.Count) {throw "$name resolver parse failed: $($errors[0].Message)"}
    $statements=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.Extent.Text -match '^\.\s+' -and $n.Extent.Text -match 'DependencyResolverHelpers\.ps1'},$true))
    if($statements.Count -ne 1) {throw "$name expected exactly one helper source statement, found $($statements.Count)"}
    $loadPath=$PSScriptRoot
    $beforeLocation=Get-Location; $beforeEnv=@{}; foreach($key in 'JAVA_HOME','MAVEN_USER_HOME','MAVEN_OPTS','TEMP','TMP') {$beforeEnv[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
    $controlledRoot="'"+$PSScriptRoot.Replace("'","''")+"'"
    $statementText=$statements[0].Extent.Text.Replace('$PSScriptRoot',$controlledRoot)
    $output=@(Invoke-Expression $statementText)
    if($output.Count) {throw "$name helper source emitted output: $($output -join '|')"}
    foreach($functionName in 'Get-ResolverLicenses','ConvertFrom-ResolverDependencyLine','Get-ResolverRepositoryBase','Get-ResolverArtifactMetadata') {
        if(!(Get-Command $functionName -CommandType Function -ErrorAction SilentlyContinue)) {throw "$name source statement did not load $functionName"}
        Remove-Item "Function:\$functionName" -ErrorAction SilentlyContinue
    }
    if((Get-Location).Path -ne $beforeLocation.Path) {throw "$name helper load changed current location"}
    foreach($key in $beforeEnv.Keys) {if([Environment]::GetEnvironmentVariable($key,'Process') -cne $beforeEnv[$key]) {throw "$name helper load changed process environment $key"}}
    $log.Add("PASS $name executes the actual single helper-source statement with no output or environment/location side effects")
}
$log | Set-Content -Encoding utf8 (Join-Path $EvidenceRoot 'helper-load.log')
"PASS $($log.Count) assertions" | Add-Content -Encoding utf8 (Join-Path $EvidenceRoot 'helper-load.log')
Get-Content (Join-Path $EvidenceRoot 'helper-load.log')
