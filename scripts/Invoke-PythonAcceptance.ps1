[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Smoke','Core')][string]$Selection,
    [Parameter(Mandatory=$true)][string]$PythonRoot,
    [Parameter(Mandatory=$true)][string]$PythonEnvironment,
    [Parameter(Mandatory=$true)][string]$Root,
    [Parameter(Mandatory=$true)][ValidateSet('Seed','Verify')][string]$IdentityPhase,
    [Parameter(Mandatory=$true)][string]$IdentityRecordPath,
    [ValidateRange(1024,65535)][int]$Port=17770,
    [ValidateRange(30,3600)][int]$TimeoutSeconds=300
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
. (Join-Path $PSScriptRoot 'IntegrationRunContext.ps1')
. (Join-Path $PSScriptRoot 'IntegrationHarnessSecrets.ps1')
. (Join-Path $PSScriptRoot 'DepositBuildMetadata.ps1')
Import-Module (Join-Path $PythonRoot 'scripts\PythonRunnerProcess.psm1') -Force
$Root=[IO.Path]::GetFullPath($Root).TrimEnd('\')
Assert-HarnessRoot $Root
$context=Get-ValidatedIntegrationRunContext $Root
if(!$context){throw 'Python acceptance must run inside the server suite-owned child context.'}
$PythonRoot=[IO.Path]::GetFullPath($PythonRoot)
$PythonEnvironment=[IO.Path]::GetFullPath($PythonEnvironment)
Assert-HarnessPlainPath $PythonEnvironment
$python=Join-Path $PythonEnvironment 'Scripts\python.exe'
$test=Join-Path $PythonRoot 'tests\integration\test_live_smoke.py'
$tests=@($test)
$stage2Test=Join-Path $PythonRoot 'tests\integration\test_stage2_store.py'
if($Selection -eq 'Core'){$tests+= $stage2Test}
if(!(Test-Path -LiteralPath $python -PathType Leaf) -or @($tests|Where-Object {!(Test-Path -LiteralPath $_ -PathType Leaf)}).Count){throw 'Installed Python environment or selected Python acceptance test is missing.'}
$IdentityRecordPath=[IO.Path]::GetFullPath($IdentityRecordPath)
Assert-HarnessPlainPath $IdentityRecordPath
if($IdentityPhase -eq 'Verify' -and !(Test-Path -LiteralPath $IdentityRecordPath -PathType Leaf)){throw 'Native identity checkpoint is missing before restart verification.'}
$env:DEPOSIT_TEST_URI="bolt://127.0.0.1:$Port"
$env:DEPOSIT_TEST_PASSWORD=[DepositHarnessSecretStore]::Read('Deposit/IntegrationHarness/test-host')
$env:DEPOSIT_EXPECTED_VERSION=(Get-DepositBuildMetadata $repo $Root).Version
$env:DEPOSIT_IDENTITY_PHASE=$IdentityPhase
$env:DEPOSIT_IDENTITY_RECORD_PATH=$IdentityRecordPath
$env:DEPOSIT_PYTHON_SOURCE_ROOT=$PythonRoot
$logs=[IO.Path]::GetFullPath([string]$context.logs)
Assert-HarnessPlainPath $logs
# Keep acceptance-only recovery coordination with the suite-owned result tree.
# The installed package still uses the current Windows user and DPAPI identity,
# while the suite owner can remove the disposable quota index and journals
# together during terminal cleanup instead of leaving stale roots in the user's
# real LocalAppData registry.
$env:LOCALAPPDATA=Join-Path $logs 'python-local-app-data'
Set-OwnerOnlyDirectory $env:LOCALAPPDATA
$env:DEPOSIT_STAGE2_RECOVERY_ROOT=Join-Path $logs 'python-stage2-recovery'
$testWork=Join-Path $logs 'python-acceptance-work'
New-Item -ItemType Directory -Force -Path $testWork | Out-Null
Assert-HarnessPlainPath $testWork
$stdout=Join-Path $logs ("python-$($Selection.ToLowerInvariant())-$IdentityPhase.stdout.log")
$stderr=Join-Path $logs ("python-$($Selection.ToLowerInvariant())-$IdentityPhase.stderr.log")
$secrets=@($env:DEPOSIT_TEST_PASSWORD)
try {
    $arguments=@('-m','pytest','-q','--import-mode=importlib')+$tests
    $result=Invoke-RunnerProcess -FilePath $python -Arguments $arguments -WorkingDirectory $testWork -StandardOutputPath $stdout -StandardErrorPath $stderr -TimeoutSeconds $TimeoutSeconds -Secrets $secrets
} finally {
    Remove-Item Env:\DEPOSIT_TEST_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:\DEPOSIT_TEST_URI,Env:\DEPOSIT_EXPECTED_VERSION,Env:\DEPOSIT_IDENTITY_PHASE,Env:\DEPOSIT_IDENTITY_RECORD_PATH,Env:\DEPOSIT_PYTHON_SOURCE_ROOT,Env:\DEPOSIT_STAGE2_RECOVERY_ROOT,Env:\DEPOSIT_STAGE2_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Module PythonRunnerProcess -ErrorAction SilentlyContinue
}
if($result.TimedOut){throw "Python acceptance timed out after $TimeoutSeconds seconds"}
if($result.ExitCode -ne 0){throw "Python acceptance failed ($($result.ExitCode)); see retained harness logs"}
Write-Output "PASS: installed Python package $Selection acceptance ($IdentityPhase)"
Write-Output "Stdout: $stdout"
Write-Output "Stderr: $stderr"
