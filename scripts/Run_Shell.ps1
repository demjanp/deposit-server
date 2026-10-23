<#
.SYNOPSIS
Open an interactive Cypher Shell for the local Deposit server.
.DESCRIPTION
Run after Run_Server.ps1 has started the server. Uses the Java and Neo4j
versions recorded by that local installation under ..\..\test_harness.
Cypher Shell prompts for the Neo4j password. Deposit procedures use a separate
Deposit login and token.
.PARAMETER Address
Bolt address of the server. Defaults to the local server on port 7687.
.PARAMETER Username
Neo4j account used for the Bolt connection. Defaults to neo4j.
.PARAMETER Database
Neo4j database to open. Defaults to neo4j.
#>
[CmdletBinding()]
param(
    [string]$Address = 'bolt://127.0.0.1:7687',
    [string]$Username = 'neo4j',
    [string]$Database = 'neo4j'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\test_harness'))
$package = Join-Path $root 'runs\local-server\distribution'
$manifest = Get-Content -LiteralPath (Join-Path $package 'artifacts.json') -Raw | ConvertFrom-Json
$javaHome = Join-Path $root $manifest.javaHome
$neo4jHome = Join-Path $root $manifest.neo4jHome
$cypherShell = Join-Path $neo4jHome 'bin\cypher-shell.bat'

$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$shellExitCode = 0
try {
    # Use the JDK associated with the running local server, not whichever Java
    # happens to appear first in the caller's PATH.
    $env:JAVA_HOME = $javaHome
    $env:PATH = (Join-Path $javaHome 'bin') + ';' + $previousPath

    & $cypherShell -a $Address -u $Username -d $Database --history disable
    $shellExitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
}

exit $shellExitCode
