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
[IO.File]::WriteAllText((Join-Path $hub 'TODO.md'),'navigation only')
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

# Gate finalization must accept both checkpoint identity fields without
# triggering StrictMode when one schema-specific field is absent.
$finalizationId='finalization-'+[Guid]::NewGuid().ToString('N')
$finalizationDirectory=Join-Path $root ('evidence\'+$finalizationId)
$childId=$finalizationId+'-child'
$childDirectory=Join-Path $root ('evidence\'+$childId)
$scanId=$finalizationId+'-scan'
$scanDirectory=Join-Path $root ('evidence\'+$scanId)
$scanCheckpoint=Join-Path $root ($finalizationId+'-unrelated-checkpoint.json')
try {
    Start-Stage0Result $root $finalizationDirectory $finalizationId 'gate' $null | Out-Null
    [IO.File]::WriteAllText((Join-Path $finalizationDirectory 'checkpoint.json'),(@{status='accepted';sourceIdentity='source-identity-only'} | ConvertTo-Json))
    Start-Stage0Result $root $childDirectory $childId 'phase' 'child-source' @() $finalizationId | Out-Null
    Complete-Stage0GateResult $root $finalizationDirectory $finalizationId
    $finalized=Get-Content -LiteralPath (Join-Path $finalizationDirectory 'result-manifest.json') -Raw | ConvertFrom-Json
    $finalizedChild=Get-Content -LiteralPath (Join-Path $childDirectory 'result-manifest.json') -Raw | ConvertFrom-Json
    if($finalized.lifecycle -ne 'finished' -or $finalized.outcome -ne 'accepted' -or $finalized.sourceIdentity -ne 'source-identity-only') {throw 'Gate finalization did not preserve sourceIdentity-only accepted checkpoint'}
    if($finalizedChild.retention -ne 'legacy') {throw 'Accepted gate retention was not propagated to its child result'}

    Start-Stage0Result $root $scanDirectory $scanId 'gate' $null | Out-Null
    [IO.File]::WriteAllText($scanCheckpoint,(@{status='accepted'} | ConvertTo-Json))
    Complete-Stage0GateResult $root $scanDirectory $scanId
    $scanResult=Get-Content -LiteralPath (Join-Path $scanDirectory 'result-manifest.json') -Raw | ConvertFrom-Json
    if($scanResult.lifecycle -ne 'finished' -or $scanResult.outcome -ne 'incomplete' -or $scanResult.sourceIdentity) {throw 'Gate finalization did not safely ignore a checkpoint without gate or identity fields'}
} finally {
    foreach($path in @($finalizationDirectory,$childDirectory,$scanDirectory)) {if(Test-Path -LiteralPath $path) {Remove-Item -LiteralPath $path -Recurse -Force}}
    if(Test-Path -LiteralPath $scanCheckpoint) {Remove-Item -LiteralPath $scanCheckpoint -Force}
}
Write-Output 'PASS: filesystem identity, addition/change/deletion, metadata exclusions and exact snapshot bytes'
Write-Output 'PASS: gate result finalization with optional checkpoint schema fields and child retention propagation'
