[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Smoke')][string]$Selection,
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
if(!(Test-Path -LiteralPath $python -PathType Leaf) -or !(Test-Path -LiteralPath $test -PathType Leaf)){throw 'Installed Python environment or Stage 1 live test is missing.'}
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
$testWork=Join-Path $logs 'python-acceptance-work'
New-Item -ItemType Directory -Force -Path $testWork | Out-Null
Assert-HarnessPlainPath $testWork
$stdout=Join-Path $logs ("python-smoke-$IdentityPhase.stdout.log")
$stderr=Join-Path $logs ("python-smoke-$IdentityPhase.stderr.log")
$secrets=@($env:DEPOSIT_TEST_PASSWORD)
try {
    $result=Invoke-RunnerProcess -FilePath $python -Arguments @('-m','pytest','-q','--import-mode=importlib',$test) -WorkingDirectory $testWork -StandardOutputPath $stdout -StandardErrorPath $stderr -TimeoutSeconds $TimeoutSeconds -Secrets $secrets
} finally {
    Remove-Item Env:\DEPOSIT_TEST_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:\DEPOSIT_TEST_URI,Env:\DEPOSIT_EXPECTED_VERSION,Env:\DEPOSIT_IDENTITY_PHASE,Env:\DEPOSIT_IDENTITY_RECORD_PATH,Env:\DEPOSIT_PYTHON_SOURCE_ROOT -ErrorAction SilentlyContinue
    Remove-Module PythonRunnerProcess -ErrorAction SilentlyContinue
}
if($result.TimedOut){throw "Python acceptance timed out after $TimeoutSeconds seconds"}
if($result.ExitCode -ne 0){throw "Python acceptance failed ($($result.ExitCode)); see retained harness logs"}
Write-Output "PASS: installed Python package live discovery, bootstrap, authentication, session, and logout smoke ($IdentityPhase)"
Write-Output "Stdout: $stdout"
Write-Output "Stderr: $stderr"
