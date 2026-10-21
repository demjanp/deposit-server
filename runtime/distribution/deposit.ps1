[CmdletBinding()]
param(
    [Parameter(Position=0)][string]$Command='help',
    [Parameter(Position=1,ValueFromRemainingArguments=$true)][string[]]$Arguments=@()
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$script:PackageRoot=[IO.Path]::GetFullPath($PSScriptRoot)
$script:StatePath=Join-Path $script:PackageRoot 'deposit.install.json'
$manifestPath=Join-Path $script:PackageRoot 'artifacts.json'
if(!(Test-Path -LiteralPath $manifestPath)){$manifestPath=Join-Path (Split-Path $script:PackageRoot -Parent) 'artifacts.json'}
$script:Manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
. (Join-Path $script:PackageRoot 'helpers\Request-DepositStop.ps1')
. (Join-Path $script:PackageRoot 'helpers\LauncherPathHelpers.ps1')
. (Join-Path $script:PackageRoot 'helpers\LauncherState.ps1')
. (Join-Path $script:PackageRoot 'helpers\LauncherSetup.ps1')
. (Join-Path $script:PackageRoot 'helpers\LauncherService.ps1')
. (Join-Path $script:PackageRoot 'helpers\LauncherLocal.ps1')

function Fail([string]$Message) { throw $Message }






















function Show-Help { @'
Deposit Windows launcher
  deposit.bat setup <neo4j-home> <java-home> <data-root> <export-root> [bolt-port]
  deposit.bat start | status | stop | diagnose
  deposit.bat install-service [dedicated-account]
  deposit.bat remove-service

Java and Neo4j are separate pinned runtime components. Headless service setup
requires an elevated shell and uses a dedicated virtual service account by default.
See OPERATIONS.md for TLS, firewall, permissions and configuration guidance.
'@ }

try {
    switch($Command.ToLowerInvariant()){
        'help' {Show-Help;exit 0}
        'setup' {Setup $Arguments $script:PackageRoot $script:StatePath $script:Manifest;exit 0}
        'start' {$state=Read-State $script:StatePath;if($state.mode -eq 'service'){Service 'start' $state $script:StatePath $script:PackageRoot $Arguments}else{Start-Local $state $script:PackageRoot};exit 0}
        'stop' {$state=Read-State $script:StatePath;if($state.mode -eq 'service'){Service 'stop' $state $script:StatePath $script:PackageRoot $Arguments}else{Stop-Local $state $script:PackageRoot};exit 0}
        'status' {$state=Read-State $script:StatePath;Status $state $script:StatePath $script:PackageRoot;exit 0}
        'diagnose' {$state=Read-State $script:StatePath;Diagnose $state $script:PackageRoot;exit 0}
        'install-service' {$state=Read-State $script:StatePath;Service 'install' $state $script:StatePath $script:PackageRoot $Arguments;exit 0}
        'remove-service' {$state=Read-State $script:StatePath;Service 'remove' $state $script:StatePath $script:PackageRoot $Arguments;exit 0}
        default {Show-Help;[Console]::Error.WriteLine("Unknown command: $Command");exit 2}
    }
} catch { [Console]::Error.WriteLine("Deposit launcher: $($_.Exception.Message)");exit 1 }
