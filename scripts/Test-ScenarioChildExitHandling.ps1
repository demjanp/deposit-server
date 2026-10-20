$ErrorActionPreference='Stop'
$ast=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Invoke-CleanProvisioningScenario.ps1'),[ref]$null,[ref]$null)
foreach($name in @('Write-ScenarioLog','Invoke-Logged')){
    $definition=$ast.Find({param($node)$node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
    Invoke-Expression $definition.Extent.Text
}
$lines=New-Object 'System.Collections.Generic.List[string]'
$utf8=New-Object Text.UTF8Encoding($false)
$trackFreshChildren=$false
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
$harnessRoot=Get-HarnessRoot;$env:TEMP=Join-Path $harnessRoot 'tmp';$env:TMP=$env:TEMP
$LogPath=Join-Path (Join-Path $harnessRoot 'logs') ('operations-child-exit-'+[Guid]::NewGuid().ToString('N')+'.log')
New-Item -ItemType Directory -Force (Split-Path $LogPath -Parent)|Out-Null
Invoke-Logged (Join-Path $PSHOME 'powershell.exe') @('-NoProfile','-NonInteractive','-Command','exit 0') 'success-child'
$rejected=$false
try{Invoke-Logged (Join-Path $PSHOME 'powershell.exe') @('-NoProfile','-NonInteractive','-Command','exit 7') 'failed-child'}
catch{if($_.Exception.Message -notmatch 'failed \(7\)'){throw};$rejected=$true}
if(!$rejected){throw 'Failing child was accepted'}
Write-Output 'PASS: actual child exit 0 accepted, exit 7 rejected'
Write-Output "Log: $LogPath"
