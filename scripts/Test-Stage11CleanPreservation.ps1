[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
. (Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1') -LibraryOnly
$rootPath=Get-HarnessRoot
$fixture=Join-Path $rootPath ('tmp\clean-preservation-'+[Guid]::NewGuid().ToString('N'))
$source=Join-Path $fixture 'original'
$destination=Join-Path $fixture 'preserved'
$LogPath=Join-Path $fixture 'scenario.log'
$Port=17770
$preserved=Join-Path $fixture 'containment'
$run=$source
$freshJobs=New-Object 'System.Collections.Generic.List[object]'
$trackFreshChildren=$false
$serviceName='DepositNeo4j-00000000'
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Invoke-Stage11CleanProvisionScenario.ps1'),[ref]$null,[ref]$null)
foreach($name in @('Assert-Tree','Move-OwnedTree','Get-TreeHashes','Invoke-Logged','Assert-FreshRuntimeStopped','Retire-FreshChildren','Invoke-Phase','Invoke-Selection')){
    $definition=$ast.Find({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
    if(!$definition){throw "Missing scenario function: $name"}
    # Evaluated AST fragments otherwise lose their script-file directory.
    Invoke-Expression ($definition.Extent.Text.Replace('$PSScriptRoot',("'"+$PSScriptRoot.Replace("'","''")+"'")))
}
New-Item -ItemType Directory -Path (Join-Path $source 'logs\Start-fixture') -Force | Out-Null
New-Item -ItemType Directory -Path $preserved -Force | Out-Null
function Write-ScenarioLog([string]$Message){Write-Host $Message}
$log=Join-Path $source 'logs\Start-fixture\release-start.err.log'
if(-not ('DepositPreservationFixture' -as [type])){
    Add-Type @'
using System;
using System.IO;
using System.Threading;
public sealed class DepositPreservationFixture : IDisposable {
    public readonly FileStream Writer;
    private Timer release;
    public DepositPreservationFixture(string path) {
        Writer = File.Open(path, FileMode.OpenOrCreate, FileAccess.Write, FileShare.ReadWrite);
    }
    public void ReleaseAfter(int milliseconds) {
        release = new Timer(delegate(object state) { Writer.Dispose(); }, null, milliseconds, Timeout.Infinite);
    }
    public void Dispose() { if (release != null) release.Dispose(); Writer.Dispose(); }
}
'@
}
$held=[DepositPreservationFixture]::new($log)
$writer=$held.Writer
New-Item -ItemType Directory -Path (Join-Path $source 'neo4j\logs') -Force | Out-Null
$consoleHolds=@('deposit-console.out.log','deposit-console.err.log')|ForEach-Object {[DepositPreservationFixture]::new((Join-Path $source ('neo4j\logs\'+$_)))}
try{
    $contents=[Text.Encoding]::UTF8.GetBytes('retained diagnostic output')
    $writer.Write($contents,0,$contents.Length);$writer.Flush()
    foreach($console in $consoleHolds){$console.Writer.Write($contents,0,$contents.Length);$console.Writer.Flush()}
    [IO.File]::WriteAllText((Join-Path $source 'config.json'),'{}')
    $before=Get-TreeHashes $source
    if($before.Count -ne 4){throw 'Preservation omitted a file'}
    $diagnostic=@($before|Where-Object {$_.path -eq 'logs\Start-fixture\release-start.err.log'})
    $sha=[Security.Cryptography.SHA256]::Create()
    try{$expected=[BitConverter]::ToString($sha.ComputeHash($contents)).Replace('-','')}finally{$sha.Dispose()}
    if($diagnostic.Count -ne 1 -or $diagnostic[0].sha256 -ne $expected -or $diagnostic[0].bytes -ne $contents.Length){throw 'Diagnostic preservation hash is incorrect'}
    $held.ReleaseAfter(300)
    foreach($console in $consoleHolds){$console.ReleaseAfter(300)}
    Move-OwnedTree $source $destination -TimeoutMilliseconds 3000
    Move-OwnedTree $destination $source
    $after=Get-TreeHashes $source
    if((ConvertTo-Json -InputObject $before -Compress) -cne (ConvertTo-Json -InputObject $after -Compress)){throw 'Move/restoration changed preserved contents'}

    $held.Dispose()
    $held=[DepositPreservationFixture]::new($log)
    $rejected=$false
    try{Move-OwnedTree $source $destination -TimeoutMilliseconds 300}catch{
        if($_.Exception.Message -notlike '*Timed out preserving tree*'){throw}
        $rejected=$true
    }
    if(!$rejected){throw 'Persistent diagnostic handle did not prevent the move'}
    if(!(Test-Path -LiteralPath $source) -or (Test-Path -LiteralPath $destination)){throw 'Failed preservation partially moved the tree'}
    $retained=Get-TreeHashes $source
    if((ConvertTo-Json -InputObject $before -Compress) -cne (ConvertTo-Json -InputObject $retained -Compress)){throw 'Failed preservation changed original contents'}
    $held.Dispose()

    # A completed command can leave a descendant holding a diagnostic file. This
    # must be retired inside its nested job while the coordinator stays alive.
    New-Item -ItemType Directory -Path (Join-Path $source 'distribution') -Force|Out-Null
    @{mode='local';neo4jHome=(Join-Path $source 'neo4j');serviceName='DepositNeo4j-11111111'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $source 'distribution\deposit.install.json') -Encoding UTF8
    $workerScript=Join-Path $preserved 'hold-file.ps1'
    $launchScript=Join-Path $preserved 'launch.ps1'
    $signal=Join-Path $preserved 'worker.json'
    @'
param([string]$Path,[string]$Signal)
$stream=[IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Write,[IO.FileShare]::ReadWrite)
try {
    @{pid=$PID;startTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks}|ConvertTo-Json|Set-Content -LiteralPath $Signal -Encoding UTF8
    while($true){Start-Sleep -Milliseconds 100}
} finally {$stream.Dispose()}
'@|Set-Content -LiteralPath $workerScript -Encoding UTF8
    @'
param([string]$Worker,[string]$Path,[string]$Signal)
$child=Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList @('-NoProfile','-File',('"'+$Worker+'"'),'-Path',('"'+$Path+'"'),'-Signal',('"'+$Signal+'"')) -WindowStyle Hidden -PassThru
$child.Dispose()
$deadline=[DateTime]::UtcNow.AddSeconds(10)
while(!(Test-Path -LiteralPath $Signal)){if([DateTime]::UtcNow -gt $deadline){throw 'Fixture worker did not acquire file'};Start-Sleep -Milliseconds 50}
'@|Set-Content -LiteralPath $launchScript -Encoding UTF8
    $trackFreshChildren=$true
    Invoke-Logged (Join-Path $PSHOME 'powershell.exe') @('-NoProfile','-File',$launchScript,'-Worker',$workerScript,'-Path',$log,'-Signal',$signal) 'contained-handle'
    $workerIdentity=Get-Content -LiteralPath $signal -Raw|ConvertFrom-Json
    $workerProcess=Get-SuiteProcess ([long]$workerIdentity.pid) ([long]$workerIdentity.startTicks)
    if(!$workerProcess -or $freshJobs.Count -ne 1 -or ![DepositSuiteJobs]::Contains([string]$freshJobs[0].jobName,$workerProcess)){throw 'Fixture descendant escaped fresh containment'}
    $workerProcess.Dispose()
    $rejected=$false
    try{Move-OwnedTree $source $destination -TimeoutMilliseconds 100}catch{if($_.Exception.Message -notlike '*Timed out preserving tree*'){throw};$rejected=$true}
    if(!$rejected){throw 'Fixture descendant did not retain its diagnostic file'}
    # Validate the entire set before retirement: a later live root prevents any
    # earlier contained descendant from being killed.
    $coordinator=Get-Process -Id $PID
    $liveRoot=[pscustomobject]@{jobName=('Local\DepositClean-'+[Guid]::NewGuid().ToString('N'));pid=$PID;startTicks=$coordinator.StartTime.ToUniversalTime().Ticks;label='live-root'}
    $coordinator.Dispose();$freshJobs.Add($liveRoot)
    $rejected=$false
    try{Retire-FreshChildren}catch{if($_.Exception.Message -notlike '*live command root*'){throw};$rejected=$true}
    if(!$rejected){throw 'Fresh retirement accepted a live command root'}
    $freshJobs.RemoveAt($freshJobs.Count-1)
    $workerProcess=Get-SuiteProcess ([long]$workerIdentity.pid) ([long]$workerIdentity.startTicks)
    if(!$workerProcess){throw 'Retirement killed a descendant before validating all roots'}
    $workerProcess.Dispose()
    @{pid=$workerIdentity.pid;created=([DateTime]::new([long]$workerIdentity.startTicks,[DateTimeKind]::Utc).ToString('o'))}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $source 'process.json') -Encoding UTF8
    $rejected=$false
    try{Retire-FreshChildren}catch{if($_.Exception.Message -notlike '*live recorded runtime*'){throw};$rejected=$true}
    if(!$rejected){throw 'Fresh retirement accepted a live recorded runtime'}
    [IO.File]::Delete((Join-Path $source 'process.json'))
    $outside=[DepositPreservationFixture]::new((Join-Path $fixture 'outside.log'))
    try{
        # A failed Install need not have published its configuration yet. The
        # coordinator's captured service identity still permits safe retirement.
        $fixtureState=Join-Path $source 'distribution\deposit.install.json'
        [IO.File]::Move($fixtureState,($fixtureState+'.unpublished'))
        Retire-FreshChildren
        [IO.File]::Move(($fixtureState+'.unpublished'),$fixtureState)
        if($outside.Writer.SafeFileHandle.IsClosed){throw 'Fresh retirement closed an unrelated writer'}
        $workerProcess=Get-SuiteProcess ([long]$workerIdentity.pid) ([long]$workerIdentity.startTicks)
        if($workerProcess){$workerProcess.Dispose();throw 'Fresh retirement left the file holder alive'}
        $containedBefore=Get-TreeHashes $source
        Move-OwnedTree $source $destination
        Move-OwnedTree $destination $source
        $containedAfter=Get-TreeHashes $source
        if((ConvertTo-Json -InputObject $containedBefore -Compress) -cne (ConvertTo-Json -InputObject $containedAfter -Compress)){throw 'Contained retirement/restoration changed file hashes'}
    }finally{$outside.Dispose()}
    $trackFreshChildren=$false

    function Invoke-Logged([string]$File,[string[]]$Arguments,[string]$Label){
        $rootIndex=[Array]::IndexOf($Arguments,'-Root')
        if($rootIndex -lt 0 -or $Arguments[$rootIndex+1] -cne $rootPath){throw "$Label did not forward the validated root"}
        if($Label -eq 'root-forwarding'){[IO.File]::WriteAllText($LogPath+'.'+$Label+'.tests.log','[INFO] BUILD SUCCESS')}
    }
    Invoke-Phase 'Start'
    Invoke-Selection 'DistributionIT' 'root-forwarding'
    Write-Output 'PASS: diagnostic hashing, atomic preservation, persistent-lock retention, nested-job handle retirement, live-root/runtime guards, unrelated writer retention, exact restoration, and explicit child roots'
}finally{
    $held.Dispose()
    foreach($console in $consoleHolds){$console.Dispose()}
    foreach($identity in $freshJobs){Stop-SuiteChildTree $identity -RejectRuntime}
    [DepositSuiteJobs]::Release()
    Assert-Tree $fixture
    if([IO.Directory]::Exists($fixture)){[IO.Directory]::Delete($fixture,$true)}
}
