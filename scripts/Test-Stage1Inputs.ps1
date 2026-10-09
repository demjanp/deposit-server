# Independent fixtures live only under the approved external test root.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Inputs.ps1"
$root='C:\data_processed\Deposit_v2\stage0'
$fixture=Join-Path $root ('input-tests\'+[Guid]::NewGuid().ToString('N'))
$server=Join-Path $fixture 'deposit-server'; $hub=Join-Path $fixture 'deposit-hub'
New-Item -ItemType Directory -Force $server,$hub,(Join-Path $server '.git'),(Join-Path $server '.mvn\wrapper') | Out-Null
[IO.File]::WriteAllText((Join-Path $server 'a.java'),'source')
[IO.File]::WriteAllText((Join-Path $server '.mvn\wrapper\maven-wrapper.jar'),'wrapper fixture')
[IO.File]::WriteAllText((Join-Path $hub 'contract.md'),'contract')
$initial=Get-Stage0Inputs $server
if((Get-Stage0Inputs $server).sha256 -ne $initial.sha256) {throw 'Input fingerprints are not deterministic'}
[IO.File]::WriteAllText((Join-Path $server '.git\ignored'),'metadata')
[IO.File]::WriteAllText((Join-Path $hub 'implementation_status.md'),'status')
if((Get-Stage0Inputs $server).sha256 -ne $initial.sha256) {throw 'Metadata/status changed source identity'}
[IO.File]::WriteAllText((Join-Path $server 'new.java'),'added')
if((Get-Stage0Inputs $server).sha256 -eq $initial.sha256) {throw 'New file was omitted'}
Remove-Item -LiteralPath (Join-Path $server 'new.java')
[IO.File]::WriteAllText((Join-Path $server 'a.java'),'changed')
if((Get-Stage0Inputs $server).sha256 -eq $initial.sha256) {throw 'Changed file was omitted'}
Remove-Item -LiteralPath (Join-Path $server 'a.java')
if((Get-Stage0Inputs $server).sha256 -eq $initial.sha256) {throw 'Deleted file was omitted'}
[IO.File]::WriteAllText((Join-Path $server 'a.java'),'source')
$snapshot=Save-Stage0SourceSnapshot $server $fixture $initial
$zip=[IO.Compression.ZipFile]::OpenRead($snapshot.path)
try {
    if($zip.Entries.Count -ne $initial.files.Count) {throw 'Snapshot file inventory differs'}
    foreach($entry in $zip.Entries) {
        $expected=$initial.files | Where-Object {($_.repository+'/'+$_.path) -eq $entry.FullName}
        $algorithm=[Security.Cryptography.SHA256]::Create();$stream=$entry.Open()
        try {$hash=[BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$algorithm.Dispose()}
        if($expected.sha256 -ne $hash) {throw 'Snapshot bytes differ from captured inputs'}
    }
} finally {$zip.Dispose()}
Write-Output 'PASS: filesystem identity, addition/change/deletion, metadata exclusions and exact snapshot bytes'
