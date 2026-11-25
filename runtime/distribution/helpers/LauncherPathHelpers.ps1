function Get-LocalNeo4jRuntime([string]$Neo4jHome) {
    $needle=[IO.Path]::GetFullPath($Neo4jHome).TrimEnd('\')+'\'
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Replace('/','\').IndexOf($needle,[StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'
    }
}
function Test-NoConflictingRuntime([string]$Neo4jHome) {
    if(Get-LocalNeo4jRuntime $Neo4jHome){throw 'A Neo4j process already uses this installation. Local and service modes cannot run together.'}
}
function Get-IcaclsPath([string]$Path) {
    if($Path.StartsWith('\\?\',[StringComparison]::OrdinalIgnoreCase)){return $Path}
    $absolute=[IO.Path]::GetFullPath($Path)
    if($absolute.StartsWith('\\')){return '\\?\UNC\'+$absolute.Substring(2)}
    return '\\?\'+$absolute
}
