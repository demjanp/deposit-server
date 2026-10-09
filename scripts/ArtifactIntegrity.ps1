# Locate the pinned tools; checksums are verified once during provisioning.
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-ToolDirectory($Manifest,[string]$Id,[string]$Root) {
    $relative=switch($Id) {
        'temurin' {$Manifest.javaHome}
        'neo4j' {$Manifest.neo4jHome}
        'maven' {'tools/'+(($Manifest.artifacts | Where-Object id -eq 'maven').file -replace '-bin\.zip$','')}
        default {throw "Unknown pinned tool: $Id"}
    }
    $directory=Join-Path $Root $relative
    if(!(Test-Path -LiteralPath $directory -PathType Container)) {throw "Pinned tool is missing: $Id; run Provision"}
    return $directory
}
