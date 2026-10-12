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
$ocflLock=Get-Content (Join-Path "$repo\runtime" $manifest.ocflDependencyLock) -Raw | ConvertFrom-Json
$files+=@($ocflLock.dependencies | Where-Object ship | ForEach-Object {Get-Item (Join-Path $Root "cache\ocfl-dependencies\$($_.file)")})
$formatLock=Get-Content (Join-Path "$repo\runtime" $manifest.formatDependencyLock) -Raw | ConvertFrom-Json
$files+=@($formatLock.dependencies | Where-Object ship | ForEach-Object {Get-Item (Join-Path $Root "cache\format-dependencies\$($_.file)")})
$files+=Get-Item (Join-Path $Root 'build\deposit-plugin\target\deposit-plugin-0.0.0-stage65.jar')
foreach($file in $files) {
    $jar=[IO.Compression.ZipFile]::OpenRead($file.FullName)
    try {
        $components=New-Object System.Collections.Generic.List[string]
        $licenseEvidence=New-Object System.Collections.Generic.List[object]
        foreach($entry in $jar.Entries) {
            if($entry.FullName -match '^(org/(neo4j|locationtech/jts|slf4j|apache/poi|apache/xmlbeans|apache/commons/(collections4|compress|io)|apache/logging/log4j|xerial/sqlitejdbc|sqlite)|io/ocfl|com/fasterxml/jackson|com/github/benmanes/caffeine|com/google/common|com/github/virtuald/curvesapi)/.*\.class$') {
                if(!$classes.ContainsKey($entry.FullName)) {$classes[$entry.FullName]=New-Object System.Collections.Generic.List[object]}
                $stream=$entry.Open();$hash=[Security.Cryptography.SHA256]::Create()
                try {$classHash=[BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$hash.Dispose()}
                $classes[$entry.FullName].Add(@{jar=$file.Name;sha256=$classHash})
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
        $inventory.Add(@{file=$file.Name;sha256=(Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant();components=$components.ToArray();licenseEvidence=$licenseEvidence.ToArray()})
    } finally {$jar.Dispose()}
}
$duplicates=@($classes.GetEnumerator() | Where-Object {$_.Value.Count -gt 1} | Sort-Object Name | ForEach-Object {
    # The accepted APOC distribution already embeds byte-identical SLF4J 2.0.17.
    # Permit only that exact existing pair; all new duplicate definitions still block.
    $baseline=$_.Key.StartsWith('org/slf4j/') -and (($_.Value.jar | Sort-Object) -join ',') -eq 'apoc-2026.05.0-core.jar,slf4j-api-2.0.17.jar' -and @($_.Value.sha256 | Sort-Object -Unique).Count -eq 1
    @{class=$_.Key;definitions=$_.Value.ToArray();conflicting=(-not $baseline);baselineIdentical=$baseline}
})
$conflicts=@($duplicates | Where-Object conflicting)
@{inputs=$(if($Inputs){$Inputs}else{(Get-Stage0Inputs $repo).sha256});status=$(if($conflicts.Count){'conflicting-classes'}else{'no-conflicting-runtime-classes'});artifacts=$inventory.ToArray();duplicateClasses=$duplicates;conflicts=$conflicts.Count} | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $Root 'evidence\artifact-audit\inventory.json') -Encoding UTF8
Write-Output "Inventoried $($inventory.Count) JARs; $($duplicates.Count) duplicate Neo4j/JTS/OCFL/support class paths, $($conflicts.Count) conflicting definitions. See evidence\artifact-audit."
if($conflicts.Count) {throw 'Conflicting Neo4j/JTS definitions block runtime acceptance'}
