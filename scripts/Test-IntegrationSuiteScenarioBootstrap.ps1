[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'

$path=Join-Path $PSScriptRoot 'Invoke-IntegrationSuite.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
if($errors.Count){throw ('Invoke-IntegrationSuite.ps1 did not parse: '+($errors|ForEach-Object Message -join '; '))}

$scenarioBranch=$ast.Find({param($node)
    $node -is [Management.Automation.Language.IfStatementAst] -and
    $node.Extent.Text.Contains('scenarioScript=Join-Path')
},$true)
if(!$scenarioBranch){throw 'Could not locate the selected-scenario orchestration branch.'}
$scenarioClause=@($scenarioBranch.Clauses|Where-Object {$_.Item1.Extent.Text -ceq '$SelectedScenario'})|Select-Object -First 1
if(!$scenarioClause){throw 'Could not locate the selected-scenario dispatch clause.'}
$branchText=$scenarioClause.Item2.Extent.Text
$expectedScenarios=@('LocalShutdownDrain','ServiceShutdown','ServiceCrashRecovery')
$condition=[regex]::Match($branchText,'if\s*\(\$SelectedScenario\s+-in\s+@\(([^)]*)\)\)\s*\{(?<body>.*?)\}',[Text.RegularExpressions.RegexOptions]::Singleline)
if(!$condition.Success){throw 'Selected-scenario bootstrap condition is missing.'}
$actualScenarios=@([regex]::Matches($condition.Groups[1].Value,"'([^']+)'")|ForEach-Object {$_.Groups[1].Value})
if(($actualScenarios -join '|') -cne ($expectedScenarios -join '|')){throw "Bootstrap must run for exactly $($expectedScenarios -join ', '); found $($actualScenarios -join ', ')."}
$body=$condition.Groups['body'].Value
if($body -notmatch "Invoke-SuitePhase\s+'Start'" -or $body -notmatch "Invoke-SuiteSelection\s+'AuthenticationNamespaceLifecycleIT#bootstrapRollbackThenCommitAndLoginReadOnlyCompletion'"){
    throw 'Scenario bootstrap must start the fresh local harness and run the existing lifecycle selection.'
}
$conversion=[regex]::Match($branchText,'if\s*\(\$SelectedScenario\s+-in\s+@\((?<values>[^)]*)\)\)\s*\{\s*Enable-TestService\s*\}')
if(!$conversion.Success){throw 'Existing service conversion branch is missing or moved from its selected-scenario owner.'}
$conversionScenarios=@([regex]::Matches($conversion.Groups['values'].Value,"'([^']+)'")|ForEach-Object {$_.Groups[1].Value})
if(($conversionScenarios -join '|') -cne 'CleanProvisioning|DownloadListener|ServiceShutdown|ServiceCrashRecovery'){
    throw "Service conversion must remain limited to its existing scenarios; found $($conversionScenarios -join ', ')."
}
$bootstrapIndex=$branchText.IndexOf($condition.Value,[StringComparison]::Ordinal)
$conversionIndex=$branchText.IndexOf($conversion.Value,[StringComparison]::Ordinal)
$killGateIndex=$branchText.IndexOf('Test-KillTestOwnedJvm.ps1',[StringComparison]::Ordinal)
$dispatchIndex=$branchText.IndexOf('$scenarioScript=Join-Path',[StringComparison]::Ordinal)
if($bootstrapIndex -lt 0 -or $conversionIndex -lt $bootstrapIndex -or $killGateIndex -lt $conversionIndex -or $dispatchIndex -lt $killGateIndex){throw 'Bootstrap, service conversion, owned-service refusal gate and scenario dispatch are out of order.'}
$gate=$branchText.Substring($conversionIndex,$dispatchIndex-$conversionIndex)
if($gate -notmatch 'if\s*\(\$SelectedScenario\s+-eq\s+''ServiceShutdown''\)' -or $gate -notmatch 'Invoke-SuiteChild[^\r\n]*Test-KillTestOwnedJvm\.ps1'){
    throw 'The owned-service refusal gate must be a suite-contained preflight for selected ServiceShutdown only.'
}
if($gate -notmatch 'ReadAllBytes' -or $gate -notmatch 'WriteAllBytes' -or $gate -notmatch 'startMillis' -or $gate -notmatch 'Stop-SuiteChildTree'){
    throw 'The owned-service refusal gate must preserve exact process record bytes, service PID/start identity, and quiesce failed children before restoration.'
}
$localScenario=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Invoke-LocalShutdownDrainScenario.ps1') -Raw
$distributionScript=Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\runtime\distribution\deposit.ps1') -Raw
$launcherHelpers=@([regex]::Matches($distributionScript,"Join-Path \`$script:PackageRoot 'helpers\\([^']+\.ps1)'")|ForEach-Object {$_.Groups[1].Value})
if(!$launcherHelpers.Count){throw 'Could not enumerate the packaged launcher helper dependencies.'}
if($localScenario -notmatch '\$runtimeHelperDirectory\s*=\s*Join-Path\s+\$repo\s+''runtime\\distribution\\helpers''' -or
   $localScenario -notmatch 'Get-ChildItem\s+-LiteralPath\s+\$runtimeHelperDirectory\s+-File\s*\|\s*Copy-Item\s+-Destination\s+\$launcherHelpers\s+-Force' -or
   $localScenario -notmatch '\$launcherHelpers\s*=\s*Join-Path\s+\$launcher\s+''helpers'''){
    throw 'LocalShutdownDrain must stage the full packaged launcher helper set before invoking production Stop.'
}
foreach($helper in @('Request-DepositStop.ps1','Stop-Console.ps1')){
    if($localScenario -notmatch [regex]::Escape($helper)){throw "LocalShutdownDrain does not stage its launcher dependency $helper."}
}
foreach($helper in $launcherHelpers){
    $source=if($helper -ceq 'Request-DepositStop.ps1'){Join-Path $PSScriptRoot $helper}else{Join-Path (Join-Path $PSScriptRoot '..\runtime\distribution\helpers') $helper}
    if(!(Test-Path -LiteralPath $source -PathType Leaf)){throw "Packaged launcher helper source is missing: $helper."}
}
$terminalIndex=$localScenario.IndexOf('Confirmed the owned process exited after admitted work completed cleanup.',[StringComparison]::Ordinal)
$ownedStopPattern='Invoke-ChildScript\s+\(Join-Path\s+\$PSScriptRoot\s+''Invoke-TestHarnessPhase\.ps1''\)\s+@\(''-Phase'',''Stop'''
$ownedStop=[regex]::Match($localScenario,$ownedStopPattern)
$restartIndex=$localScenario.IndexOf('Start-TestHarness',[Math]::Max(0,$terminalIndex),[StringComparison]::Ordinal)
$verifyIndex=$localScenario.IndexOf('ShutdownDrainIT#verifyCommittedTransactionSurvivedServiceShutdown',[StringComparison]::Ordinal)
if($terminalIndex -lt 0 -or !$ownedStop.Success -or $ownedStop.Index -le $terminalIndex -or $restartIndex -le $ownedStop.Index -or $verifyIndex -le $restartIndex){
    throw 'LocalShutdownDrain must retire the exact stopped runtime through the existing suite-owned Stop phase before restart and commit verification.'
}
if($localScenario -notmatch 'runtimeIdentityBeforeStop\.runtimeGeneration' -or $localScenario -notmatch 'state\s+-cne\s+''retired''' -or
   $localScenario -notmatch '\[DepositSuiteJobs\]::Members' -or $localScenario -notmatch 'runtimeIdentityAfterRestart\.runtimeGeneration' -or
   $localScenario -notmatch 'jobAcknowledged' -or $localScenario -notmatch '\[DepositSuiteJobs\]::Contains'){
    throw 'LocalShutdownDrain must verify the old generation is retired and empty, then verify a distinct acknowledged generation owns the restarted server.'
}
Write-Output 'PASS: selected bootstrap, existing service conversion, ServiceShutdown-only contained refusal gate and dispatch are ordered; exact record preservation and service incarnation verification are present.'
