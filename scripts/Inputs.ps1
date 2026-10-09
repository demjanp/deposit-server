function Get-Stage0Inputs([string]$Repo) {
    $records = New-Object System.Collections.Generic.List[object]
    foreach ($entry in @(@{name='server';path=$Repo},@{name='hub';path=(Join-Path $Repo '..\deposit-hub')})) {
        $files = & git -C $entry.path ls-files --cached --others --exclude-standard
        if ($LASTEXITCODE -ne 0) {throw 'Cannot capture repository inputs'}
        foreach ($relative in ($files | Sort-Object -Unique)) {
            if($entry.name -eq 'hub' -and $relative -eq 'implementation_status.md') {continue}
            $file=Join-Path $entry.path $relative
            $hash='DELETED'
            if (Test-Path -LiteralPath $file -PathType Leaf) {$hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
            $records.Add([ordered]@{repository=$entry.name;path=$relative;sha256=$hash})
        }
    }
    # Hash the effective source tree; staging/committing an already-deleted file
    # must not invalidate identical build inputs. Keep deletion records as evidence.
    $json=ConvertTo-Json -InputObject @($records.ToArray() | Where-Object sha256 -ne 'DELETED') -Depth 5 -Compress
    $algorithm=[Security.Cryptography.SHA256]::Create()
    try {$hash=[BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($json))).Replace('-','').ToLowerInvariant()}
    finally {$algorithm.Dispose()}
    return @{sha256=$hash;files=$records.ToArray();serverBaseline=(& git -C $Repo rev-parse HEAD);hubBaseline=(& git -C (Join-Path $Repo '..\deposit-hub') rev-parse HEAD)}
}

function Save-Stage0SourceSnapshot([string]$Repo,[string]$Root,$Inputs) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $directory=Join-Path $Root 'inputs'
    New-Item -ItemType Directory -Force $directory | Out-Null
    $archive=Join-Path $directory ($Inputs.sha256+'.zip')
    if(!(Test-Path -LiteralPath $archive)) {
        $temporary=$archive+'.'+[Guid]::NewGuid().ToString('N')+'.partial'
        $zip=[IO.Compression.ZipFile]::Open($temporary,'Create')
        try {
            foreach($record in @($Inputs.files | Where-Object sha256 -ne 'DELETED')) {
                $source=$Repo
                if($record.repository -eq 'hub') {$source=Join-Path $Repo '..\deposit-hub'}
                $entry=$zip.CreateEntry($record.repository+'/'+$record.path.Replace('\','/'))
                $entry.LastWriteTime=[DateTimeOffset]'2026-10-08T00:00:00Z'
                $input=[IO.File]::OpenRead((Join-Path $source $record.path)); $output=$entry.Open()
                try {$input.CopyTo($output)} finally {$output.Dispose();$input.Dispose()}
            }
        } finally {$zip.Dispose()}
        [IO.File]::Move($temporary,$archive)
    }
    return @{path=$archive;inputs=$Inputs.sha256}
}
