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

function Enter-Stage0CleanupBarrier([string]$Root='C:\data_processed\Deposit_v2\stage0') {
    $rootPath=[IO.Path]::GetFullPath($Root).TrimEnd('\')
    if($rootPath -ne 'C:\data_processed\Deposit_v2\stage0') {throw 'Use the approved Stage 0 root'}
    if(!(Test-Path -LiteralPath $rootPath -PathType Container)) {throw "Stage 0 root does not exist: $rootPath"}
    return [IO.File]::Open((Join-Path $rootPath 'cleanup-barrier.lock'),'OpenOrCreate','ReadWrite','ReadWrite')
}

function Write-Stage0ResultManifest([string]$Root,[string]$Directory,$Manifest) {
    if($Manifest -isnot [System.Collections.IDictionary]) {
        $converted=@{}
        foreach($property in $Manifest.PSObject.Properties) {$converted[$property.Name]=$property.Value}
        $Manifest=$converted
    }
    $rootPath=[IO.Path]::GetFullPath($Root).TrimEnd('\')
    $directoryPath=[IO.Path]::GetFullPath($Directory).TrimEnd('\')
    $evidenceRoot=[IO.Path]::GetFullPath((Join-Path $rootPath 'evidence')).TrimEnd('\')+'\'
    $phaseRoot=[IO.Path]::GetFullPath((Join-Path $rootPath 'runs\stage0-host\evidence')).TrimEnd('\')+'\'
    $runnerRoot=[IO.Path]::GetFullPath((Join-Path $rootPath 'runner')).TrimEnd('\')+'\'
    $performanceRoot=[IO.Path]::GetFullPath((Join-Path $rootPath 'performance')).TrimEnd('\')+'\'
    if(!$directoryPath.StartsWith($evidenceRoot,[StringComparison]::OrdinalIgnoreCase) -and !$directoryPath.StartsWith($phaseRoot,[StringComparison]::OrdinalIgnoreCase) -and !$directoryPath.StartsWith($runnerRoot,[StringComparison]::OrdinalIgnoreCase) -and !$directoryPath.StartsWith($performanceRoot,[StringComparison]::OrdinalIgnoreCase)) {throw 'Result manifest must be inside an approved Stage 0 result root'}
    if(!(Test-Path -LiteralPath $directoryPath -PathType Container)) {New-Item -ItemType Directory -Path $directoryPath -Force | Out-Null}
    $relative=$directoryPath.Substring($rootPath.Length+1).Replace('/','\')
    $Manifest.ownedPaths=@($relative)
    $refs=@()
    foreach($reference in @($Manifest.requiredReferences)) {
        $refPath=[IO.Path]::GetFullPath((Join-Path $rootPath ([string]$reference)))
        if(!$refPath.StartsWith($rootPath+'\',[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $refPath)) {throw "Result dependency reference is invalid: $reference"}
        $refs+= $refPath.Substring($rootPath.Length+1).Replace('/','\')
    }
    $Manifest.requiredReferences=$refs
    if($Manifest.resultId -notmatch '^[A-Za-z0-9_-]+$' -or $Manifest.kind -notin @('gate','environment','phase','live-runner','artifact-audit','runner','performance')) {throw 'Result manifest identity or kind is invalid'}
    if($Manifest.lifecycle -notin @('running','finished','interrupted') -or $Manifest.outcome -notin @('accepted','passed','failed','incomplete','unknown') -or $Manifest.retention -notin @('retain','legacy')) {throw 'Result manifest lifecycle, outcome or retention is invalid'}
    $Manifest.schemaVersion=2
    $manifestPath=Join-Path $directoryPath 'result-manifest.json';$temporary=$manifestPath+'.'+[Guid]::NewGuid().ToString('N')+'.partial'
    $bytes=[Text.Encoding]::UTF8.GetBytes(($Manifest|ConvertTo-Json -Depth 12))
    $stream=New-Object IO.FileStream($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)} finally {$stream.Dispose()}
    if(Test-Path -LiteralPath $manifestPath) {[IO.File]::Replace($temporary,$manifestPath,$manifestPath+'.previous')} else {[IO.File]::Move($temporary,$manifestPath)}
    $validated=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -ErrorAction Stop
    if($validated.schemaVersion -ne 2 -or $validated.resultId -ne $Manifest.resultId -or $validated.lifecycle -ne $Manifest.lifecycle -or $validated.retention -ne $Manifest.retention) {throw 'Published result manifest failed validation'}
    return $manifestPath
}

function Start-Stage0Result([string]$Root,[string]$Directory,[string]$ResultId,[string]$Kind,[string]$SourceIdentity,[string[]]$RequiredReferences=@(),[string]$OwnerResultId) {
    $manifest=@{resultId=$ResultId;ownerResultId=$OwnerResultId;kind=$Kind;lifecycle='running';outcome='unknown';retention='retain';startedUtc=[DateTime]::UtcNow.ToString('o');finishedUtc=$null;sourceIdentity=$SourceIdentity;requiredReferences=$RequiredReferences}
    Write-Stage0ResultManifest $Root $Directory $manifest | Out-Null
}

function Complete-Stage0Result([string]$Root,[string]$Directory,[string]$Outcome,[string]$SourceIdentity) {
    $manifestPath=Join-Path $Directory 'result-manifest.json'
    if(!(Test-Path -LiteralPath $manifestPath -PathType Leaf)) {throw "Initial result manifest is missing: $manifestPath"}
    $old=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -ErrorAction Stop
    if($old.schemaVersion -ne 2 -or $old.lifecycle -ne 'running') {throw 'Result manifest is not a running schema-2 manifest'}
    if($Outcome -notin @('accepted','passed','failed','incomplete','unknown')) {throw 'Result outcome is invalid'}
    $manifest=@{resultId=[string]$old.resultId;ownerResultId=[string]$old.ownerResultId;kind=[string]$old.kind;lifecycle='finished';outcome=$Outcome;retention=$(if($Outcome -in @('failed','incomplete','unknown')){'retain'}else{'legacy'});startedUtc=[string]$old.startedUtc;finishedUtc=[DateTime]::UtcNow.ToString('o');sourceIdentity=$SourceIdentity;requiredReferences=@($old.requiredReferences)}
    Write-Stage0ResultManifest $Root $Directory $manifest | Out-Null
}

function Complete-Stage0GateResult([string]$Root,[string]$Directory,[string]$ResultId) {
    if(!(Test-Path -LiteralPath (Join-Path $Directory 'result-manifest.json') -PathType Leaf)) {return}
    $checkpoint=$null
    $localCheckpoint=Join-Path $Directory 'checkpoint.json'
    if(Test-Path -LiteralPath $localCheckpoint -PathType Leaf) {try {$checkpoint=Get-Content -LiteralPath $localCheckpoint -Raw | ConvertFrom-Json -ErrorAction Stop} catch {}}
    if(!$checkpoint) {
        foreach($path in @(Get-ChildItem -LiteralPath $Root -File -Filter '*-checkpoint.json' -ErrorAction SilentlyContinue)) {
            try {$candidate=Get-Content -LiteralPath $path.FullName -Raw | ConvertFrom-Json -ErrorAction Stop} catch {continue}
            $candidateGate=$candidate.PSObject.Properties['gate']
            if($candidateGate -and [string]$candidateGate.Value -eq $ResultId) {$checkpoint=$candidate;break}
        }
    }
    $outcome='incomplete';$identity=$null
    if($checkpoint) {
        $statusProperty=$checkpoint.PSObject.Properties['status']
        $status=if($statusProperty){[string]$statusProperty.Value}else{$null}
        if($status -eq 'accepted') {$outcome='accepted'}
        elseif($status -eq 'failed') {$outcome='failed'}
        elseif($status -in @('passed','completed')) {$outcome='passed'}
        $inputsProperty=$checkpoint.PSObject.Properties['inputs']
        $sourceIdentityProperty=$checkpoint.PSObject.Properties['sourceIdentity']
        if($inputsProperty -and ![string]::IsNullOrWhiteSpace([string]$inputsProperty.Value)) {$identity=[string]$inputsProperty.Value}
        elseif($sourceIdentityProperty -and ![string]::IsNullOrWhiteSpace([string]$sourceIdentityProperty.Value)) {$identity=[string]$sourceIdentityProperty.Value}
    }
    Complete-Stage0Result $Root $Directory $outcome $identity
    $parent=Get-Content -LiteralPath (Join-Path $Directory 'result-manifest.json') -Raw | ConvertFrom-Json -ErrorAction Stop
    $childManifests=@()
    foreach($childRoot in @((Join-Path $Root 'evidence'),(Join-Path $Root 'runner'))) {
      if(Test-Path -LiteralPath $childRoot -PathType Container) {
        $childManifests+=@(Get-ChildItem -LiteralPath $childRoot -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {Join-Path $_.FullName 'result-manifest.json'})
      }
    }
    foreach($childManifest in $childManifests) {
        if(!(Test-Path -LiteralPath $childManifest -PathType Leaf)) {continue}
        $m=Get-Content -LiteralPath $childManifest -Raw | ConvertFrom-Json -ErrorAction Stop
        if($m.ownerResultId -ne $ResultId) {continue}
        $child=@{resultId=[string]$m.resultId;ownerResultId=[string]$m.ownerResultId;kind=[string]$m.kind;lifecycle=[string]$m.lifecycle;outcome=[string]$m.outcome;retention=[string]$parent.retention;startedUtc=[string]$m.startedUtc;finishedUtc=[string]$m.finishedUtc;sourceIdentity=[string]$m.sourceIdentity;requiredReferences=@($m.requiredReferences)}
        Write-Stage0ResultManifest $Root (Split-Path $childManifest -Parent) $child | Out-Null
    }
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
