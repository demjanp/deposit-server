param([Parameter(Mandatory=$true)][string]$Neo4jHome,[Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference='Stop'
$env:JAVA_HOME=[IO.Path]::GetFullPath($JavaHome)
$env:NEO4J_HOME=[IO.Path]::GetFullPath($Neo4jHome)
$env:NEO4J_CONF=Join-Path $env:NEO4J_HOME 'conf'
$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
& (Join-Path $env:NEO4J_HOME 'bin\neo4j.bat') console
exit $LASTEXITCODE
