[CmdletBinding()]
param(
  [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$RunPrefix=('stage10-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmss')),
  [int]$Port=17770
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Inputs.ps1"
$repo=Split-Path $PSScriptRoot -Parent
$root='C:\data_processed\Deposit_v2\stage0'
$cleanupBarrier=Enter-Stage0CleanupBarrier $root
$gateLock=$null
$evidence=Join-Path $root "evidence\$RunPrefix"
$ownsGateResult=$false
$localDownloadBase=$null
try {
  $stage10SessionFixture=Join-Path $root 'runs\stage0-host\secrets\stage10-browser-session.properties'
  if(Test-Path -LiteralPath $stage10SessionFixture){Remove-Item -LiteralPath $stage10SessionFixture -Force}
  $stage9Prefix=$RunPrefix+'-stage9'
  & "$PSScriptRoot\Invoke-Stage9Gate.ps1" -RunPrefix $stage9Prefix -Port $Port
  $stage9=Get-Content "$root\stage9-checkpoint.json" -Raw|ConvertFrom-Json
  if($stage9.status -ne 'accepted' -or $stage9.testCases.Count -ne 209){throw 'Stage 9 cumulative prerequisite is incomplete'}
  $gateLock=[IO.File]::Open("$root\gate.lock",'OpenOrCreate','ReadWrite','None')
  if(Test-Path -LiteralPath $evidence){throw 'Use a fresh Stage 10 run prefix'}
  Start-Stage0Result $root $evidence $RunPrefix 'gate' $null | Out-Null
  $ownsGateResult=$true
  $inputs=Get-Stage0Inputs $repo
  $inputsFile=Join-Path $evidence 'source-inputs.json'
  $inputs|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $inputsFile -Encoding UTF8
  $snapshot=Save-Stage0SourceSnapshot $repo $root $inputs
  $checkpointPath="$root\stage10-checkpoint.json"
  $run=@{stage=10;gate=$RunPrefix;rerun='scripts/Invoke-Stage10Gate.ps1 -RunPrefix <fresh-id> -Port '+$Port;status='running';accepted=$false;sourceIdentity=$inputs.sha256;sourceSnapshot=$snapshot;startedUtc=[DateTime]::UtcNow.ToString('o');phases=@();pending=@();acceptance='Accepted Stage 9 cumulative gate plus all Stage 10 installed HTTP/browser transport scenarios.'}
  function Save-Checkpoint {
    $run.updated=[DateTime]::UtcNow.ToString('o');$temporary=$checkpointPath+'.partial'
    [IO.File]::WriteAllText($temporary,($run|ConvertTo-Json -Depth 14),(New-Object Text.UTF8Encoding($false)))
    if(Test-Path $checkpointPath){[IO.File]::Replace($temporary,$checkpointPath,$checkpointPath+'.previous')}else{[IO.File]::Move($temporary,$checkpointPath)}
    Copy-Item -LiteralPath $checkpointPath -Destination (Join-Path $evidence 'checkpoint.json') -Force
  }
  function Phase([string]$Name,[hashtable]$Options=@{}) {
    Save-Checkpoint
    try {& "$PSScriptRoot\Stage0.ps1" -Phase $Name -RunId stage0-host -Port $Port -EvidenceGroup $RunPrefix -InputsFile $inputsFile @Options}
    finally {$phase=Get-Content "$root\runs\stage0-host\checkpoint.json" -Raw|ConvertFrom-Json;$run.phases+=@($phase);Save-Checkpoint}
    if($phase.phase -ne $Name -or $phase.status -ne 'passed'){throw "Stage 0 phase failed: $Name"}
  }
  function Test-CasesFromLastPhase {
    $phase=$run.phases[-1];$reports=@(Get-ChildItem (Join-Path $phase.evidence 'junit') -Filter 'TEST-*.xml');$found=@()
    foreach($report in $reports){[xml]$xml=Get-Content $report.FullName -Raw;if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped -or [int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.tests -ne @($xml.testsuite.testcase).Count){throw 'Failed, skipped, or incomplete Stage 10 tests'};foreach($case in $xml.testsuite.testcase){$found+=@{class=$case.classname;name=$case.name;report=$report.FullName}}}
    if(!$found.Count){throw 'Missing Stage 10 JUnit evidence'};return $found
  }
  function Assert-TestCase([array]$Found,[string]$Class,[string]$Name) {if(@($Found|Where-Object {$_.class -eq $Class -and $_.name -eq $Name}).Count -ne 1){throw "Missing Stage 10 acceptance case: $Class#$Name"}}
  function Clear-TestSessions {foreach($name in @('stage1-session.txt','stage55-session.txt','stage10-browser-session.properties','stage10-download-test.p12','stage10-download-test.password')){$credential=Join-Path $root ('runs\stage0-host\secrets\'+$name);if(Test-Path -LiteralPath $credential){Remove-Item -LiteralPath $credential}}}
  Save-Checkpoint
  if($stage9.sourceIdentity -ne $inputs.sha256){throw 'Stage 9 cumulative prerequisite is stale'}
  Phase Build
  $buildEvidence=$run.phases[-1].evidence
  $unitReports=@(Get-ChildItem (Join-Path $buildEvidence 'unit-junit') -Filter 'TEST-*.xml')
  $unitCases=@();$unitSuites=@()
  foreach($report in $unitReports){[xml]$xml=Get-Content $report.FullName -Raw;if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Stage 10 unit evidence contains failures or skips'};$unitSuites+=@($xml.testsuite.name);foreach($case in $xml.testsuite.testcase){$unitCases+=@{suite=$xml.testsuite.name;name=$case.name}}}
  foreach($suite in @('deposit.common.ServerContextBrowserSessionTest','deposit.common.ServerContextReaderPinTest','deposit.common.ResourceLinkEncoderTest','deposit.service.BrowserDownloadServiceTest')){if($unitSuites -notcontains $suite){throw "Missing Stage 10 unit suite: $suite"}}
  foreach($caseName in @('browserIdleExpiryIsMeasuredFromLastSuccessfulTouch','browserAbsoluteExpiryCannotBeExtendedBySuccessfulTouches','readerPinsAreReferenceCountedAndIdempotentlyReleased','concurrentReaderPinReleaseCannotLoseAnotherPin','resolvesOpenEndedAndSuffixRanges')){if(@($unitCases|Where-Object name -eq $caseName).Count -ne 1){throw "Missing Stage 10 unit scenario: $caseName"}}
  $run.unitTests=$unitCases.Count
  $run.stage9Checkpoint=(Get-FileHash "$root\stage9-checkpoint.json" -Algorithm SHA256).Hash
  $run.priorIntegrationCases=[int]$stage9.testCases.Count
  Phase Stop;Clear-TestSessions;Phase Reset;Phase Install
  $configPath=Join-Path $root 'runs\stage0-host\neo4j\conf\deposit.properties'
  $configText=[IO.File]::ReadAllText($configPath)
  $baseMatch=[regex]::Match($configText,'(?m)^download\.publicBaseUrl=(.+)$')
  if(!$baseMatch.Success){throw 'Installed download public URL is missing'}
  $localDownloadBase=$baseMatch.Groups[1].Value.Trim()
  $downloadBase=$localDownloadBase.TrimEnd('/')+'/stage10-proxy'
  $configText=[regex]::Replace($configText,'(?m)^download\.publicBaseUrl=.*$',('download.publicBaseUrl='+$downloadBase))
  [IO.File]::WriteAllText($configPath,$configText,(New-Object Text.UTF8Encoding($false)))
  $run.proxyPrefix='/stage10-proxy'
  Phase Start
  Phase Test @{TestSelection='Stage10CoreIT'}
  $phase=Get-Content "$root\runs\stage0-host\checkpoint.json" -Raw|ConvertFrom-Json
  $reports=@(Get-ChildItem (Join-Path $phase.evidence 'junit') -Filter 'TEST-*.xml')
  if(!$reports.Count){throw 'Missing Stage 10 JUnit evidence'}
  $cases=@()
  foreach($report in $reports){[xml]$xml=Get-Content $report.FullName -Raw;if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped -or [int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.tests -ne @($xml.testsuite.testcase).Count){throw 'Failed, skipped, or incomplete Stage 10 tests'};foreach($case in $xml.testsuite.testcase){$cases+=@{class=$case.classname;name=$case.name;report=$report.FullName}}}
  $expected=@('browserLoginReusesCookieStreamsExactBytesAndSupportsRangesAndCsrfLogout','browserAndBoltTokensAreTransportBoundAndAccountDisableRevokesBoth','browserAdministrativeModeIsExplicitSessionLocalAndClearedOnDemotion','creatorBoundExportGrantDoesNotBecomeBrowserAccessForAnotherNamespaceMember','namespaceRevocationStopsAnAlreadyStreamingDownloadBetweenChunks','browserHistoryAccessHonorsClosedEpochRetentionAndTransientExpiry','csvXlsxAndGeoPackageRenderLinksWithoutGrantingTheReferencedResource')
  if($cases.Count -ne $expected.Count){throw "Stage 10 integration case count differs: $($cases.Count)"}
  foreach($name in $expected){if(@($cases|Where-Object name -eq $name).Count -ne 1){throw "Missing Stage 10 scenario: $name"}}
  $run.stage10Cases=$cases
  $cases|ConvertTo-Json -Depth 6|Set-Content (Join-Path $evidence 'stage10-junit-results.json') -Encoding UTF8
  $restartCases=@()
  Phase Test @{TestSelection='Stage10RestartIT#seed'};$seedCases=@(Test-CasesFromLastPhase);Assert-TestCase $seedCases 'org.deposit.it.Stage10RestartIT' 'seed';$restartCases+=@($seedCases)
  Phase Stop;Phase Start;Phase Test @{TestSelection='Stage10RestartIT#verify'};$verifyCases=@(Test-CasesFromLastPhase);Assert-TestCase $verifyCases 'org.deposit.it.Stage10RestartIT' 'verify';$restartCases+=@($verifyCases)
  Phase Stop
  $downloadConfigText=[IO.File]::ReadAllText($configPath)
  $downloadPortMatches=[regex]::Matches($downloadConfigText,'(?m)^[ \t]*download\.port[ \t]*=[ \t]*(\d+)[ \t]*\r?$')
  if($downloadPortMatches.Count -ne 1){throw 'Installed download configuration must contain exactly one valid port'}
  $downloadPort=[int]$downloadPortMatches[0].Groups[1].Value
  if($downloadPort -lt 1 -or $downloadPort -gt 65535){throw 'Installed download port is outside 1..65535'}
  $occupiedDownloadPort=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,$downloadPort)
  try {
    $occupiedDownloadPort.ExclusiveAddressUse=$true
    $occupiedDownloadPort.Start()
    $occupiedEndpoint=[Net.IPEndPoint]$occupiedDownloadPort.LocalEndpoint
    if($occupiedEndpoint.Port -ne $downloadPort){throw 'Occupied-port fixture did not bind the configured download port'}
    $run.downloadPortFixture=@{configuredPort=$downloadPort;address=$occupiedEndpoint.Address.ToString();boundPort=$occupiedEndpoint.Port;exclusive=$occupiedDownloadPort.ExclusiveAddressUse}
    try {Phase Start;throw 'Download listener started despite an occupied configured port'}
    catch {
      if($_.Exception.Message -eq 'Download listener started despite an occupied configured port'){throw}
      $failedStart=$run.phases[-1];if($failedStart.phase -ne 'Start' -or $failedStart.status -ne 'failed'){throw 'Occupied download port did not fail startup readiness'}
      $readinessLog=Join-Path $failedStart.evidence 'readiness.err.log'
      $logText=if(Test-Path -LiteralPath $readinessLog){[IO.File]::ReadAllText($readinessLog)}else{''}
      if($logText -notmatch '(?i)(BindException|Address already in use)'){throw 'Occupied download listener failure lacked bind-error evidence'}
      $run.downloadListenerOccupiedPortRejected=$true;$run.downloadBindFailureLog=$readinessLog
    }
  } finally {$occupiedDownloadPort.Stop()}
  Phase Stop
  $bindAddress=Get-NetIPAddress -AddressFamily IPv4 -AddressState Preferred | Where-Object {$_.IPAddress -notmatch '^(127\.|169\.254\.)'} | Select-Object -First 1 -ExpandProperty IPAddress
  if(!$bindAddress){throw 'No non-loopback IPv4 address is available for isolated HTTPS binding'}
  $keyTool=$null
  foreach($directory in Get-ChildItem (Join-Path $root 'tools') -Directory) {$candidate=Join-Path $directory.FullName 'bin\keytool.exe';if(Test-Path -LiteralPath $candidate){$keyTool=$candidate;break}}
  if(!$keyTool){throw 'Pinned JDK keytool is unavailable for direct-TLS acceptance'}
  $secretDirectory=Join-Path $root 'runs\stage0-host\secrets'
  $keyStore=Join-Path $secretDirectory 'stage10-download-test.p12';$passwordFile=Join-Path $secretDirectory 'stage10-download-test.password';$keyPassword='Stage10-Test-Key-Store-Only-2026'
  [IO.File]::WriteAllText($passwordFile,$keyPassword,(New-Object Text.UTF8Encoding($false)))
  & $keyTool -genkeypair -alias stage10-download -keyalg RSA -keysize 2048 -validity 3 -dname "CN=$bindAddress" -ext "SAN=ip:$bindAddress" -storetype PKCS12 -keystore $keyStore -storepass $keyPassword -keypass $keyPassword -noprompt | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Could not create the isolated direct-TLS test certificate'}
  $keyStoreConfigPath=$keyStore.Replace('\','/');$passwordConfigPath=$passwordFile.Replace('\','/')
  $settings=@{'download.listenAddress'=$bindAddress;'download.publicBaseUrl'="https://${bindAddress}:$downloadPort/stage10-proxy";'download.tlsKeyStore'=$keyStoreConfigPath;'download.tlsKeyStorePasswordFile'=$passwordConfigPath}
  $configText=[IO.File]::ReadAllText($configPath)
  foreach($key in $settings.Keys){$pattern='(?m)^'+[regex]::Escape($key)+'=.*$';$replacement=$key+'='+$settings[$key];if([regex]::IsMatch($configText,$pattern)){$configText=[regex]::Replace($configText,$pattern,$replacement)}else{$configText=$configText.TrimEnd()+"`r`n"+$replacement+"`r`n"}}
  [IO.File]::WriteAllText($configPath,$configText,(New-Object Text.UTF8Encoding($false)))
  Phase Start;Phase Test @{TestSelection='Stage10TlsIT'};$tlsCases=@(Test-CasesFromLastPhase);Assert-TestCase $tlsCases 'org.deposit.it.Stage10TlsIT' 'directHttpsSetsSecureScopedCookieAndReusesTheSession'
  $allStage10Cases=@($cases)+@($restartCases)+@($tlsCases);$run.stage10Cases=$allStage10Cases
  $allStage10Cases|ConvertTo-Json -Depth 6|Set-Content (Join-Path $evidence 'stage10-all-junit-results.json') -Encoding UTF8
  $run.cumulativeIntegrationCases=$run.priorIntegrationCases+$allStage10Cases.Count
  if($run.cumulativeIntegrationCases -ne 219){throw 'Incomplete cumulative Stage 1-10 integration evidence'}
  if((Get-Stage0Inputs $repo).sha256 -ne $inputs.sha256){throw 'Source identity changed during gate'}
  $run.status='verified'
} catch {$run.status='incomplete';$run.cause=$_.Exception.Message}
finally {
  try {
    if((Get-Content "$root\runs\stage0-host\checkpoint.json" -Raw|ConvertFrom-Json).status -eq 'passed'){Phase Stop}else{& "$PSScriptRoot\Stage0.ps1" -Phase Stop -RunId stage0-host -Port $Port -EvidenceGroup $RunPrefix -InputsFile $inputsFile}
    $configPath=Join-Path $root 'runs\stage0-host\neo4j\conf\deposit.properties'
    if($localDownloadBase -and (Test-Path -LiteralPath $configPath)){$configText=[IO.File]::ReadAllText($configPath);$configText=[regex]::Replace($configText,'(?m)^download\.listenAddress=.*$','download.listenAddress=127.0.0.1');$configText=[regex]::Replace($configText,'(?m)^download\.publicBaseUrl=.*$',('download.publicBaseUrl='+$localDownloadBase));$configText=[regex]::Replace($configText,'(?m)^download\.tlsKeyStore(?:PasswordFile)?=.*(?:\r?\n|$)','');[IO.File]::WriteAllText($configPath,$configText,(New-Object Text.UTF8Encoding($false)))}
    Clear-TestSessions
  }
  catch {$run.status='incomplete';$run.accepted=$false;$run.cause='Final stop failed: '+$_.Exception.Message}
  if($run.status -eq 'verified'){$run.status='accepted';$run.accepted=$true}
  Save-Checkpoint
  try {if($ownsGateResult){Complete-Stage0GateResult $root $evidence $RunPrefix}} finally {if($gateLock){$gateLock.Dispose()};$cleanupBarrier.Dispose()}
}
if($run.status -ne 'accepted'){Write-Error "Stage 10 acceptance is incomplete; evidence: $evidence";exit 1}
