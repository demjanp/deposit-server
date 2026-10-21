function Test-NoConflictingRuntime([string]$Neo4jHome) {
    $needle=[IO.Path]::GetFullPath($Neo4jHome).TrimEnd('\')
    $found=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains($needle) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'})
    if($found.Count){throw 'A Neo4j process already uses this installation. Local and service modes cannot run together.'}
}
function Get-IcaclsPath([string]$Path) {
    if($Path.StartsWith('\\?\',[StringComparison]::OrdinalIgnoreCase)){return $Path}
    $absolute=[IO.Path]::GetFullPath($Path)
    if($absolute.StartsWith('\\')){return '\\?\UNC\'+$absolute.Substring(2)}
    return '\\?\'+$absolute
}
