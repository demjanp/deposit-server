Set-StrictMode -Version Latest

$script:DepositServerRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:TestHarnessRoot = [IO.Path]::GetFullPath((Join-Path $script:DepositServerRoot '..\test_harness'))

function Get-DepositServerRoot { return $script:DepositServerRoot }
function Get-TestHarnessRoot { return $script:TestHarnessRoot }
function Get-HarnessRoot { return $script:TestHarnessRoot }

function Get-HarnessRunRoot([string]$RunId = 'test-host') {
    if ($RunId -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$') { throw 'Invalid harness run identifier' }
    return Join-Path (Join-Path $script:TestHarnessRoot 'runs') $RunId
}

function Assert-HarnessRoot([string]$Root) {
    $resolved = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    if (![string]::Equals($resolved, $script:TestHarnessRoot.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)) {
        throw "Integration test data must remain under the isolated harness root $($script:TestHarnessRoot)"
    }
}

function Assert-TestHarnessRoot([string]$Root) { Assert-HarnessRoot $Root }

function Test-HarnessTerminalCleanup {
    $journalPath = [Environment]::GetEnvironmentVariable('DEPOSIT_SUITE_JOURNAL','Process')
    if (!$journalPath -or !(Test-Path -LiteralPath $journalPath -PathType Leaf)) { return $false }
    $full = [IO.Path]::GetFullPath($journalPath)
    if ($full -ine $script:TestHarnessRoot -and !$full.StartsWith($script:TestHarnessRoot+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Suite journal path escaped the isolated harness' }
    $current = $full
    while ($current) {
        if ((Test-Path -LiteralPath $current) -and ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Suite journal path contains a reparse point: $current" }
        if ([string]::Equals($current.TrimEnd('\'),$script:TestHarnessRoot.TrimEnd('\'),[StringComparison]::OrdinalIgnoreCase)) { break }
        $current = Split-Path $current -Parent
    }
    try { return [bool]((Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json -ErrorAction Stop).terminalCleanup) }
    catch { throw "Cannot safely read the integration-suite cleanup state: $($_.Exception.Message)" }
}
