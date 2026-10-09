param([string]$Root='C:\data_processed\Deposit_v2\stage0',[string]$Inputs)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repo=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot\Inputs.ps1"
$manifest=Get-Content "$repo\runtime\artifacts.json" -Raw | ConvertFrom-Json
$Root=[IO.Path]::GetFullPath($Root).TrimEnd('\')
if($Root -ne 'C:\data_processed\Deposit_v2\stage0') {throw 'Use the approved Stage 0 root'}
$inventory=New-Object System.Collections.Generic.List[object]
$classes=@{}
$notices=Join-Path $Root 'evidence\artifact-audit\notices'
New-Item -ItemType Directory -Force $notices | Out-Null
$files=@(Get-ChildItem (Join-Path $Root "$($manifest.neo4jHome)\lib") -Filter *.jar)
$files+=@($manifest.artifacts | Where-Object {$_.id -in @('apoc','spatial','bcrypt-provider')} | ForEach-Object {Get-Item (Join-Path $Root "cache\$($_.file)")})
$dependencyLock=Get-Content (Join-Path "$repo\runtime" $manifest.dependencyLock) -Raw | ConvertFrom-Json
$files+=@($dependencyLock.dependencies | Where-Object ship | ForEach-Object {Get-Item (Join-Path $Root "cache\spatial-dependencies\$($_.file)")})
$files+=Get-Item (Join-Path $Root 'build\deposit-plugin\target\deposit-plugin-0.0.0-stage3.jar')
foreach($file in $files) {
    $jar=[IO.Compression.ZipFile]::OpenRead($file.FullName)
    try {
        $components=New-Object System.Collections.Generic.List[string]
        $licenseEvidence=New-Object System.Collections.Generic.List[object]
        foreach($entry in $jar.Entries) {
            if($entry.FullName -match '^org/(neo4j|locationtech/jts)/.*\.class$') {
                if(!$classes.ContainsKey($entry.FullName)) {$classes[$entry.FullName]=New-Object System.Collections.Generic.List[object]}
                $classes[$entry.FullName].Add(@{jar=$file.Name})
            }
            if($entry.FullName.EndsWith('pom.properties')) {
                $reader=New-Object IO.StreamReader($entry.Open())
                try {$components.Add($reader.ReadToEnd())} finally {$reader.Dispose()}
            }
            if($entry.FullName -match '(?i)(^|/)(LICENSE[^/]*|NOTICE[^/]*)$' -and $entry.Length -gt 0) {
                $destination=Join-Path $notices ($file.Name+'__'+$entry.FullName.Replace('/','_'))
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$destination,$true)
                $licenseEvidence.Add(@{entry=$entry.FullName;file=$destination})
            }
        }
        $inventory.Add(@{file=$file.Name;components=$components.ToArray();licenseEvidence=$licenseEvidence.ToArray()})
    } finally {$jar.Dispose()}
}
$duplicates=@($classes.GetEnumerator() | Where-Object {$_.Value.Count -gt 1} | Sort-Object Name | ForEach-Object {@{class=$_.Key;definitions=$_.Value.ToArray();conflicting=$true}})
$conflicts=@($duplicates | Where-Object conflicting)
@{inputs=$(if($Inputs){$Inputs}else{(Get-Stage0Inputs $repo).sha256});status=$(if($conflicts.Count){'conflicting-classes'}else{'no-conflicting-neo4j-or-jts-classes'});artifacts=$inventory.ToArray();duplicateClasses=$duplicates;conflicts=$conflicts.Count} | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $Root 'evidence\artifact-audit\inventory.json') -Encoding UTF8
Write-Output "Inventoried $($inventory.Count) JARs; $($duplicates.Count) duplicate Neo4j/JTS paths, $($conflicts.Count) conflicting definitions. See evidence\artifact-audit."
if($conflicts.Count) {throw 'Conflicting Neo4j/JTS definitions block runtime acceptance'}
