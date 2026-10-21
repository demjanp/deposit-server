function New-DeterministicZip([string]$SourceDirectory,[string]$Destination) {
    Add-Type -AssemblyName System.IO.Compression
    $archive=[IO.Compression.ZipFile]::Open($Destination,[IO.Compression.ZipArchiveMode]::Create)
    try {
        $base=[IO.Path]::GetFullPath($SourceDirectory).TrimEnd('\')+'\'
        foreach($file in (Get-ChildItem -LiteralPath $SourceDirectory -File -Recurse | Sort-Object { $_.FullName.Substring($base.Length).Replace('\','/') })) {
            $relative=$file.FullName.Substring($base.Length).Replace('\','/')
            $entry=$archive.CreateEntry($relative,[IO.Compression.CompressionLevel]::Optimal)
            $entry.LastWriteTime=[DateTimeOffset]'2026-10-08T00:00:00Z'
            $input=[IO.File]::OpenRead($file.FullName);$output=$entry.Open()
            try {$input.CopyTo($output)} finally {$output.Dispose();$input.Dispose()}
        }
    } finally {$archive.Dispose()}
}