<#
.SYNOPSIS
Open an interactive Cypher Shell for the local Deposit server.
.DESCRIPTION
Run after Run_Server.bat has started the server. Uses the Java and Neo4j
versions recorded by that local installation under ..\..\test_harness.
Cypher Shell prompts for the Neo4j password. Deposit procedures use a separate
Deposit login and token.
.PARAMETER Address
Bolt address of the server. Defaults to the local server on port 7687.
.PARAMETER Username
Neo4j account used for the Bolt connection. Defaults to neo4j.
.PARAMETER Database
Neo4j database to open. Defaults to neo4j.
.PARAMETER OperationId
Print a fresh UUIDv7 for use in a Deposit procedure call.
#>
[CmdletBinding()]
param(
    [string]$Address = 'bolt://127.0.0.1:7687',
    [string]$Username = 'neo4j',
    [string]$Database = 'neo4j',
    [switch]$OperationId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = Split-Path $PSScriptRoot -Parent
. (Join-Path $repo 'runtime/distribution/helpers/LauncherLocal.ps1')
if ($OperationId) {
    New-DepositOperationId
    exit 0
}

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\test_harness'))
$package = Join-Path $root 'runs\local-server\distribution'
$statePath = Join-Path $package 'deposit.install.json'
if (!(Test-Path -LiteralPath $statePath -PathType Leaf)) {
    throw 'No local server is configured. Run Run_Server.bat first.'
}

$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
Invoke-DepositShell $state $Address $Username $Database
exit $LASTEXITCODE
