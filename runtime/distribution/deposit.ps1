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
  deposit.bat init-password <neo4j-home> <java-home>
  deposit.bat setup <neo4j-home> <java-home> <data-root> <export-root> [bolt-port]
  deposit.bat shell [address] [username] [database]
  deposit.bat operation-id
  deposit.bat start | status | stop | diagnose
  deposit.bat install-service [dedicated-account]
  deposit.bat remove-service

Java and Neo4j are separate pinned runtime components. Headless service setup
requires an elevated shell and uses a dedicated virtual service account by default.
'@ }

try {
    switch($Command.ToLowerInvariant()){
        'help' {Show-Help;exit 0}
        'operation-id' {
            if ($Arguments.Count) { throw 'Usage: deposit.bat operation-id' }
            New-DepositOperationId
            exit 0
        }
        'init-password' {
            if ($Arguments.Count -ne 2) { throw 'Usage: deposit.bat init-password <neo4j-home> <java-home>' }
            . (Join-Path $script:PackageRoot 'helpers\Credentials.ps1')
            $password = Read-Host 'Choose the Neo4j administrator password (at least 8 characters)' -AsSecureString
            try {
                Set-InitialNeo4jPassword ([IO.Path]::GetFullPath($Arguments[0])) ([IO.Path]::GetFullPath($Arguments[1])) $password
            } finally { $password.Dispose() }
            exit 0
        }
        'setup' {Setup $Arguments $script:PackageRoot $script:StatePath $script:Manifest;exit 0}
        'shell' {
            if ($Arguments.Count -gt 3) { throw 'Usage: deposit.bat shell [address] [username] [database]' }
            $state = Read-State $script:StatePath
            Invoke-DepositShell $state @Arguments
            exit $LASTEXITCODE
        }
        'start' {$state=Read-State $script:StatePath;if($state.mode -eq 'service'){Service 'start' $state $script:StatePath $script:PackageRoot $Arguments}else{Start-Local $state $script:PackageRoot};exit 0}
        'stop' {$state=Read-State $script:StatePath;if($state.mode -eq 'service'){Service 'stop' $state $script:StatePath $script:PackageRoot $Arguments}else{Stop-Local $state $script:PackageRoot};exit 0}
        'status' {$state=Read-State $script:StatePath;Status $state $script:StatePath $script:PackageRoot;exit 0}
        'diagnose' {$state=Read-State $script:StatePath;Diagnose $state $script:PackageRoot;exit 0}
        'install-service' {$state=Read-State $script:StatePath;Service 'install' $state $script:StatePath $script:PackageRoot $Arguments;exit 0}
        'remove-service' {$state=Read-State $script:StatePath;Service 'remove' $state $script:StatePath $script:PackageRoot $Arguments;exit 0}
        default {Show-Help;[Console]::Error.WriteLine("Unknown command: $Command");exit 2}
    }
} catch { [Console]::Error.WriteLine("Deposit launcher: $($_.Exception.Message)");exit 1 }
