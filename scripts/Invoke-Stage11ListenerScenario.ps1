[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$LogDirectory,
      [Parameter(Mandatory=$true)][string]$TlsMaterialDirectory)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
$rootPath=Get-HarnessRoot
$run=Get-HarnessRunRoot
$env:STAGE0_ROOT=$rootPath
$config=Join-Path $run 'neo4j\conf\deposit.properties'
$state=Get-Content (Join-Path $run 'distribution\deposit.install.json') -Raw|ConvertFrom-Json
if($state.mode -ne 'service'){throw 'Selected listener scenarios require the test-owned service installation'}
$original=[IO.File]::ReadAllBytes($config)
$text=[Text.Encoding]::UTF8.GetString($original)
$base=[uri]([regex]::Match($text,'(?m)^download\.publicBaseUrl=(.+)$').Groups[1].Value.Trim())
$listenerPort=[int]([regex]::Match($text,'(?m)^download\.port=(.+)$').Groups[1].Value.Trim())
$contentPath=$null;$contentBytes=$null;$occupied=$null
$spatial=Join-Path $run 'neo4j\plugins\neo4j-spatial-server-plugin-2025.10.0.jar'
$spatialBackup=Join-Path $LogDirectory 'listener-scenario-spatial.jar'
$spatialMoved=$false
$tls=Join-Path $run 'neo4j\deposit-secrets\stage11-tls'
if(Test-Path -LiteralPath $tls){throw 'Refuse to replace an existing TLS fixture'}
$tlsCreated=$false
foreach($name in @('stage11-download-test.p12','stage11-download-test.password')){
    if(!(Test-Path -LiteralPath (Join-Path $TlsMaterialDirectory $name) -PathType Leaf)){throw "TLS source fixture is missing: $name"}
}
function Selected([string]$Test,[string]$Label){
    & "$PSScriptRoot\Invoke-Tests.ps1" -Kind Integration -Test $Test -LogPath (Join-Path $LogDirectory "$Label.log")
    if($LASTEXITCODE -ne 0){throw "Selected listener test failed: $Label"}
}
function Stop-OwnedService {
    $service=Get-Service -Name $state.serviceName
    if($service.Status -ne 'Stopped'){Stop-Service -Name $state.serviceName;$service.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(90))}
}
function Start-NormalFixture([string]$Label){
    & (Join-Path $PSHOME 'powershell.exe') -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PSScriptRoot\Stage0.ps1" -Phase Start -Root $rootPath
    if($LASTEXITCODE -ne 0){throw "Normal fixture did not start: $Label"}
}
function Start-FailedFixture([string]$Label){
    Start-Service -Name $state.serviceName
    $deadline=[DateTime]::UtcNow.AddSeconds(60);$open=$false
    do{try{$tcp=New-Object Net.Sockets.TcpClient;$open=$tcp.ConnectAsync('127.0.0.1',17770).Wait(300) -and $tcp.Connected} catch{} finally{if($tcp){$tcp.Dispose()}};if(!$open){Start-Sleep -Milliseconds 200}}while(!$open -and [DateTime]::UtcNow -lt $deadline)
    if(!$open){throw "Bolt did not become available for negative fixture $Label"}
    $previousFailure=[Environment]::GetEnvironmentVariable('STAGE11_STARTUP_FAILURE','Process')
    try{
        $env:STAGE11_STARTUP_FAILURE=switch($Label){
            'startup-resource-recovery-rejection' {'OCFL payload integrity differs'}
            'startup-listener-bind-rejection' {'Address already in use'}
            'startup-missing-spatial-rejection' {'org.neo4j.gis.spatial.SpatialDatabaseService'}
            'startup-invalid-config-rejection' {'Invalid Deposit configuration keys'}
            'startup-tls-configuration-rejection' {'Download TLS material is unavailable'}
            default {throw "Unknown negative startup fixture: $Label"}
        }
        Selected 'Stage11StartupIT#incompleteInitializationExcludesReadinessAndDownloadListener+failedInitializationRejectsAuthenticatedReadiness' $Label
    }finally{
        [Environment]::SetEnvironmentVariable('STAGE11_STARTUP_FAILURE',$previousFailure,'Process')
    }
    Copy-Item -LiteralPath (Join-Path $run 'neo4j\logs\neo4j.log') -Destination (Join-Path $LogDirectory "$Label.neo4j.log")
    Stop-OwnedService
}
try{
    New-Item -ItemType Directory -Path $tls|Out-Null
    $tlsCreated=$true
    Copy-Item -LiteralPath (Join-Path $TlsMaterialDirectory 'stage11-download-test.p12'),(Join-Path $TlsMaterialDirectory 'stage11-download-test.password') -Destination $tls
    Selected 'Stage10RestartIT#seed' 'listener-resource-seed'
    $session=[IO.File]::ReadAllText((Join-Path $run 'secrets\stage10-browser-session.properties'))
    $urlLine=($session -split "`n"|Where-Object {$_ -match '^url='}) -replace '\\:',':'
    $resource=([regex]::Matches($urlLine,'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')|Select-Object -Last 1).Value
    if(!$resource){throw 'Protected restart resource identity missing'}
    $sha=[Security.Cryptography.SHA256]::Create()
    try{$key=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($resource)))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
    $objectRoot=Join-Path $run ('resources\ocfl\'+$key.Substring(0,3)+'\'+$key.Substring(3,3)+'\'+$key.Substring(6,3)+'\'+$key)
    $inventory=Get-Content -LiteralPath (Join-Path $objectRoot 'inventory.json') -Raw|ConvertFrom-Json
    $entry=$inventory.manifest.PSObject.Properties|Select-Object -First 1
    $contentPath=[IO.Path]::GetFullPath((Join-Path $objectRoot ([string]$entry.Value[0])))
    if(!$contentPath.StartsWith($objectRoot+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'OCFL fixture payload escapes its object root'}
    $contentBytes=[IO.File]::ReadAllBytes($contentPath)
    if(!$contentBytes.Length){throw 'Recovery fixture requires a nonempty committed payload'}
    Stop-OwnedService
    $damaged=[byte[]]$contentBytes.Clone();$damaged[0]=$damaged[0] -bxor 1
    [IO.File]::WriteAllBytes($contentPath,$damaged)
    Start-FailedFixture 'startup-resource-recovery-rejection'
    [IO.File]::WriteAllBytes($contentPath,$contentBytes);$contentBytes=$null
    Start-NormalFixture 'restored resource'
    Selected 'Stage10RestartIT#verify' 'resource-recovery-original-url'
    Stop-OwnedService

    $occupied=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$listenerPort);$occupied.Start()
    Start-FailedFixture 'startup-listener-bind-rejection'
    $occupied.Stop();$occupied=$null

    if(!(Test-Path -LiteralPath $spatial) -or (Test-Path -LiteralPath $spatialBackup)){throw 'Spatial negative-fixture preservation paths are not available'}
    Move-Item -LiteralPath $spatial -Destination $spatialBackup;$spatialMoved=$true
    Start-FailedFixture 'startup-missing-spatial-rejection'
    Move-Item -LiteralPath $spatialBackup -Destination $spatial;$spatialMoved=$false

    [IO.File]::WriteAllText($config,($text+"`r`ninvalid.stage11.key=true`r`n"),(New-Object Text.UTF8Encoding($false)))
    Start-FailedFixture 'startup-invalid-config-rejection'

    $tlsText=[regex]::Replace($text,'(?m)^download\.publicBaseUrl=.*$',"download.publicBaseUrl=https://127.0.0.1:$listenerPort/stage10-proxy")
    $tlsText+="`r`ndownload.tlsKeyStore=$($tls.Replace('\','/'))/stage11-download-test.p12`r`ndownload.tlsKeyStorePasswordFile=$($tls.Replace('\','/'))/stage11-download-test.password`r`n"
    [IO.File]::WriteAllText($config,$tlsText.Replace('/stage11-download-test.p12','/missing-keystore.p12'),(New-Object Text.UTF8Encoding($false)))
    Start-FailedFixture 'startup-tls-configuration-rejection'

    [IO.File]::WriteAllText($config,$tlsText,(New-Object Text.UTF8Encoding($false)))
    Start-NormalFixture 'direct TLS'
    Selected 'Stage10TlsIT' 'direct-tls-final'
}finally{
    Stop-OwnedService
    if($occupied){$occupied.Stop()}
    if($contentBytes){[IO.File]::WriteAllBytes($contentPath,$contentBytes)}
    if($spatialMoved){Move-Item -LiteralPath $spatialBackup -Destination $spatial}
    [IO.File]::WriteAllBytes($config,$original)
    if($tlsCreated){
        foreach($name in @('stage11-download-test.p12','stage11-download-test.password')){
            $file=Join-Path $tls $name
            if(Test-Path -LiteralPath $file){Remove-Item -LiteralPath $file -Force}
        }
        Remove-Item -LiteralPath $tls
    }
    Start-NormalFixture 'original listener restoration'
}
Selected 'DistributionIT,Stage10CoreIT#browserLoginReusesCookieStreamsExactBytesAndSupportsRangesAndCsrfLogout' 'listener-restoration'
Write-Output 'PASS: selected recovery exclusion, bind rejection, TLS configuration and direct TLS scenarios; original configuration restored.'
