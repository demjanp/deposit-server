function Set-RuntimeEnvironment($State) {
    $env:JAVA_HOME=$State.javaHome;$env:NEO4J_HOME=$State.neo4jHome;$env:NEO4J_CONF=Join-Path $State.neo4jHome 'conf'
    $env:PATH=(Join-Path $State.javaHome 'bin')+';'+$env:PATH
}
function New-DepositOperationId {
    $time = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString('x12')
    $random = [guid]::NewGuid().ToString('D')
    # The timestamp replaces the first 48 bits. Preserve the random UUID's variant.
    '{0}-{1}-7{2}-{3}' -f $time.Substring(0, 8), $time.Substring(8), $random.Substring(15, 3), $random.Substring(19)
}

function Invoke-DepositShell($State, [string]$Address = 'bolt://127.0.0.1:7687', [string]$Username = 'neo4j', [string]$Database = 'neo4j') {
    $shell = Join-Path $State.neo4jHome 'bin\cypher-shell.bat'
    $java = Join-Path $State.javaHome 'bin\java.exe'
    if (!(Test-Path -LiteralPath $shell -PathType Leaf) -or !(Test-Path -LiteralPath $java -PathType Leaf)) {
        throw 'The recorded Cypher Shell or Java installation is missing its launcher.'
    }
    $previousJava = $env:JAVACMD
    try {
        # The upstream batch launcher expands JAVACMD without adding quotes.
        $env:JAVACMD = '"' + $java + '"'
        & $shell -a $Address -u $Username -d $Database --history disable
    } finally {
        $env:JAVACMD = $previousJava
    }
}
function Start-Local($State,[string]$PackageRoot) {
    Test-NoConflictingRuntime $State.neo4jHome
    if(Get-Service -Name $State.serviceName -ErrorAction SilentlyContinue){Fail 'This installation has a registered Windows service. Use its service mode or remove the service first.'}
    $worker=Join-Path $PackageRoot 'helpers\deposit-worker.ps1';$logs=Join-Path $State.neo4jHome 'logs';New-Item -ItemType Directory -Path $logs -Force|Out-Null
    $out=Join-Path $logs 'deposit-console.out.log';$err=Join-Path $logs 'deposit-console.err.log'
    $log=Join-Path $logs 'neo4j.log';$oldLength=if(Test-Path -LiteralPath $log){(Get-Item -LiteralPath $log).Length}else{0}
    $arguments="-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$worker`" -Neo4jHome `"$($State.neo4jHome)`" -JavaHome `"$($State.javaHome)`""
    $proc=Start-Process powershell.exe -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    $deadline=[DateTime]::UtcNow.AddMinutes(5)
    while([DateTime]::UtcNow -lt $deadline){$proc.Refresh();if($proc.HasExited){Fail "Neo4j exited during startup. Inspect $err"};if(Test-Path -LiteralPath $log){$stream=[IO.File]::Open($log,'Open','Read','ReadWrite');try{[void]$stream.Seek($oldLength,'Begin');$reader=New-Object IO.StreamReader($stream);$newLog=$reader.ReadToEnd()}finally{$stream.Dispose()};if($newLog -match 'Started\.'){$server=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'"|Where-Object {$_.CommandLine -and $_.CommandLine.Contains([IO.Path]::GetFullPath($State.neo4jHome)) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'}|Select-Object -First 1);if($server.Count){@{pid=[int]$server[0].ProcessId;created=$server[0].CreationDate.ToUniversalTime().ToString('o');workerPid=$proc.Id;mode='local'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $State.neo4jHome 'deposit-process.json') -Encoding UTF8;Write-Output 'Neo4j started.';return}}};Start-Sleep -Milliseconds 500}
    Fail "Neo4j startup timed out. Inspect $log and $err"
}
function Stop-Local($State,[string]$PackageRoot,[switch]$Force) {
    $file=Join-Path $State.neo4jHome 'deposit-process.json';if(!(Test-Path -LiteralPath $file)){Write-Output 'Deposit local process is not running.';return}
    $identity=Get-Content -LiteralPath $file -Raw|ConvertFrom-Json
    $proc=Get-LocalNeo4jRuntime $State.neo4jHome | Where-Object ProcessId -eq $identity.pid
    if(!$proc -or !$proc.CreationDate -or $proc.CreationDate.ToUniversalTime().ToString('o') -ne $identity.created){Fail 'The recorded PID does not identify this installation; refusing to stop it'}
    $process=Get-Process -Id $identity.pid
    [void]$process.Handle
    if($Force){Stop-Process -Id $identity.pid -Force}
    else {$drain=Drain-Installation $State;$identityBase64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($drain|ConvertTo-Json -Compress)));& (Join-Path $PackageRoot 'helpers\Stop-Console.ps1') -IdentityBase64 $identityBase64}
    # Neo4j can spend several minutes checkpointing on a busy volume after Deposit drains.
    if(!$process.WaitForExit(300000)){Fail 'Neo4j did not stop cleanly within 5 minutes'}
    Remove-Item -LiteralPath $file -Force
}
function Status($State,[string]$StatePath,[string]$PackageRoot) {
    if($State.mode -eq 'service'){Service 'status' $State $StatePath $PackageRoot @();return}
    $file=Join-Path $State.neo4jHome 'deposit-process.json';if(!(Test-Path -LiteralPath $file)){Write-Output 'Deposit local process is stopped.';exit 3}
    $identity=Get-Content -LiteralPath $file -Raw|ConvertFrom-Json;$process=Get-CimInstance Win32_Process -Filter "ProcessId=$($identity.pid)"
    if(!$process -or !$process.CommandLine -or !$process.CommandLine.Contains([IO.Path]::GetFullPath($State.neo4jHome)) -or $process.CreationDate.ToUniversalTime().ToString('o') -ne $identity.created){Write-Output 'Recorded Deposit local process is not running.';exit 3}
    Write-Output "Deposit local process is running (PID $($identity.pid))."
}
function Diagnose($State,[string]$PackageRoot) {
    $report=[ordered]@{version=$State.neo4jVersion;mode=$State.mode;neo4jHome=$State.neo4jHome;javaHome=$State.javaHome;dataRoot=$State.dataRoot;exportRoot=$State.exportRoot;serviceName=$State.serviceName;runtimeManifest=(Get-FileHash (Join-Path $PackageRoot 'artifacts.json') -Algorithm SHA256).Hash.ToLowerInvariant();pluginCount=@(Get-ChildItem (Join-Path $State.neo4jHome 'plugins') -Filter '*deposit*.jar' -ErrorAction SilentlyContinue).Count;logs=Join-Path $State.neo4jHome 'logs'}
    $report|ConvertTo-Json -Depth 5
}
