function Get-Stage0Inputs([string]$Repo) {
    $records = New-Object System.Collections.Generic.List[object]
    foreach ($entry in @(@{name='server';path=$Repo},@{name='hub';path=(Join-Path $Repo '..\deposit-hub')})) {
        $base = [IO.Path]::GetFullPath($entry.path).TrimEnd('\')
        # Enumerate the filesystem, including new files, without consulting Git.
        $files = @(Get-SourceFiles $base $base)
        foreach ($relative in ($files | Sort-Object -Unique)) {
            if($entry.name -eq 'hub' -and $relative -in @('implementation_status.md','TODO.md')) {continue}
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
    $repositoryHashes=@{}
    foreach($name in @('server','hub')) {
        $bytes=[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject @($records.ToArray() | Where-Object repository -eq $name) -Depth 5 -Compress))
        $algorithm=[Security.Cryptography.SHA256]::Create()
        try {$repositoryHashes[$name]=[BitConverter]::ToString($algorithm.ComputeHash($bytes)).Replace('-','').ToLowerInvariant()} finally {$algorithm.Dispose()}
    }
    return @{schemaVersion=2;sha256=$hash;files=$records.ToArray();serverFingerprint=$repositoryHashes.server;hubFingerprint=$repositoryHashes.hub;serverBaseline=('filesystem:'+$repositoryHashes.server);hubBaseline=('filesystem:'+$repositoryHashes.hub)}
}

function Get-SourceFiles([string]$Base,[string]$Directory) {
    foreach($item in Get-ChildItem -LiteralPath $Directory -Force) {
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {throw "Source links require explicit review: $($item.FullName)"}
        if($item.PSIsContainer) {
            if($item.Name -in @('.git','.idea','.vscode','.cache','.generated','node_modules','target','build','__pycache__')) {continue}
            Get-SourceFiles $Base $item.FullName
        } else {
            $relative=$item.FullName.Substring($Base.Length+1).Replace('\','/')
            if($item.Extension -in @('.log','.class','.pyc','.zip','.jar') -and $relative -ne '.mvn/wrapper/maven-wrapper.jar') {continue}
            $relative
        }
    }
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
                $bytes=[IO.File]::ReadAllBytes((Join-Path $source $record.path));$algorithm=[Security.Cryptography.SHA256]::Create()
                try {$actual=[BitConverter]::ToString($algorithm.ComputeHash($bytes)).Replace('-','').ToLowerInvariant()} finally {$algorithm.Dispose()}
                if($actual -ne $record.sha256) {throw 'Source changed while capturing snapshot'}
                $output=$entry.Open()
                try {$output.Write($bytes,0,$bytes.Length)} finally {$output.Dispose()}
            }
        } finally {$zip.Dispose()}
        [IO.File]::Move($temporary,$archive)
    }
    return @{path=$archive;inputs=$Inputs.sha256}
}
