[CmdletBinding()]
param(
    [Parameter(Position=0)][string]$Command='help',
    [Parameter(Position=1,ValueFromRemainingArguments=$true)][string[]]$Arguments=@()
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$script:PackageRoot=[IO.Path]::GetFullPath($PSScriptRoot)
$script:StatePath=Join-Path $script:PackageRoot 'deposit.install.json'
$manifestPath=Join-Path $script:PackageRoot 'artifacts.json'
if(!(Test-Path -LiteralPath $manifestPath)){$manifestPath=Join-Path (Split-Path $script:PackageRoot -Parent) 'artifacts.json'}
$script:Manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
. (Join-Path $script:PackageRoot 'helpers\Request-DepositStop.ps1')

function Fail([string]$Message) { throw $Message }
function Drain-Installation($State) {
    $owned=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains([IO.Path]::GetFullPath($State.neo4jHome)) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'})
    if($owned.Count -ne 1){Fail 'Cannot identify the single owned Deposit runtime for cleanup'}
    return (Request-DepositStop -Neo4jHome $State.neo4jHome -TargetProcessId $owned[0].ProcessId)
}
function Get-DepositServiceIncarnation($State) {
    try{$identity=Get-DepositServiceProcessIdentity $State.neo4jHome $State.serviceName $State.serviceAccount}
    catch{Fail "Cannot establish the running Deposit service incarnation: $($_.Exception.Message)"}
    return [pscustomobject]@{name=[string]$identity.name;pid=[long]$identity.pid;startMillis=[long]$identity.startMillis;serviceAccount=[string]$identity.serviceAccount;runtimeSid=[string]$identity.runtimeSid}
}
function Assert-DepositServiceIncarnation($State,$Identity,$Drain) {
    $current=Get-DepositServiceIncarnation $State
    if($current.name -ine $Identity.name -or $current.pid -ne $Identity.pid -or $current.startMillis -ne $Identity.startMillis -or $current.serviceAccount -ine $Identity.serviceAccount -or $current.runtimeSid -ne $Identity.runtimeSid){Fail 'Deposit service restarted during its drain; refusing to stop a replacement service'}
    [void](Assert-DepositStopIdentity $Drain)
}
function Drain-ServiceInstallation($State,$Identity) {
    $drain=Request-DepositStop -Neo4jHome $State.neo4jHome -TargetProcessId ([int]$Identity.pid) -ServiceName $State.serviceName -ServiceAccount $State.serviceAccount
    if([long]$drain.pid -ne [long]$Identity.pid -or [long]$drain.startMillis -ne [long]$Identity.startMillis){Fail 'Deposit drain acknowledgement belongs to another service incarnation'}
    return $drain
}
function Assert-NoInstalledDepositService($State) {
    $existing=Get-CimInstance Win32_Service -Filter "Name='$($State.serviceName)'"
    if($existing){Fail 'A Deposit service is already registered. Run deposit.bat remove-service from an elevated shell before retrying installation.'}
}
function Read-State {
    if(!(Test-Path -LiteralPath $script:StatePath -PathType Leaf)){Fail 'Deposit is not configured. Run deposit.bat setup <neo4j-home> <java-home> <data-root> <export-root>.'}
    $state=Get-Content -LiteralPath $script:StatePath -Raw | ConvertFrom-Json -ErrorAction Stop
    foreach($key in @('neo4jHome','javaHome','dataRoot','exportRoot','serviceName','mode')) {if(!$state.$key){Fail "Install configuration is missing $key"}}
    $state.neo4jHome=[IO.Path]::GetFullPath($state.neo4jHome);$state.javaHome=[IO.Path]::GetFullPath($state.javaHome)
    $state.dataRoot=[IO.Path]::GetFullPath($state.dataRoot);$state.exportRoot=[IO.Path]::GetFullPath($state.exportRoot)
    if(!$state.secretRoot){$state.secretRoot=Join-Path $state.neo4jHome 'deposit-secrets'};$state.secretRoot=[IO.Path]::GetFullPath($state.secretRoot)
    return $state
}
function Save-State($State) {
    $tmp=$script:StatePath+'.'+[guid]::NewGuid().ToString('N')+'.partial'
    $backup=$script:StatePath+'.'+[guid]::NewGuid().ToString('N')+'.backup'
    try {
        [IO.File]::WriteAllText($tmp,($State|ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
        if(Test-Path -LiteralPath $script:StatePath){[IO.File]::Replace($tmp,$script:StatePath,$backup);Remove-Item -LiteralPath $backup -Force}else{[IO.File]::Move($tmp,$script:StatePath)}
    } finally {Remove-Item -LiteralPath $tmp,$backup -Force -ErrorAction SilentlyContinue}
}
function Set-Neo4jSetting([string]$Path,[string]$Name,[string]$Value) {
    $lines=if(Test-Path -LiteralPath $Path){[IO.File]::ReadAllLines($Path)}else{@()}
    $pattern='^\s*'+[regex]::Escape($Name)+'\s*='
    $matches=@(for($i=0;$i -lt $lines.Length;$i++){if($lines[$i] -match $pattern){$i}})
    if($Name -eq 'server.jvm.additional') {
        if(@($matches|Where-Object {$lines[$_] -match ('^\s*'+[regex]::Escape($Name)+'\s*=\s*'+[regex]::Escape($Value)+'\s*$')}).Count){return}
        $lines+= "$Name=$Value"
        [IO.File]::WriteAllLines($Path,$lines,(New-Object Text.UTF8Encoding($false)))
        return
    }
    if($matches.Count -gt 1){Fail "Conflicting duplicate Neo4j setting: $Name"}
    if($matches.Count){$lines[$matches[0]]="$Name=$Value"}else{$lines+= "$Name=$Value"}
    [IO.File]::WriteAllLines($Path,$lines,(New-Object Text.UTF8Encoding($false)))
}
function Set-SecretDirectoryAccess([string]$Path,[string]$ServiceAccount) {
    $resolved=[IO.Path]::GetFullPath($Path)
    if(!(Test-Path -LiteralPath $resolved -PathType Container)){New-Item -ItemType Directory -Path $resolved -Force|Out-Null}
    $item=Get-Item -LiteralPath $resolved -Force;if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){Fail 'Secret directory cannot be a reparse point'}
    $operator=[System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl=New-Object System.Security.AccessControl.DirectorySecurity;$acl.SetAccessRuleProtection($true,$false);$acl.SetOwner($operator)
    $ownerRule=New-Object System.Security.AccessControl.FileSystemAccessRule($operator,'FullControl','ContainerInherit,ObjectInherit','None','Allow');$acl.AddAccessRule($ownerRule)
    $service=New-Object System.Security.Principal.NTAccount($ServiceAccount)
    $serviceRule=New-Object System.Security.AccessControl.FileSystemAccessRule($service,'Modify','ContainerInherit,ObjectInherit','None','Allow');$acl.AddAccessRule($serviceRule)
    Set-Acl -LiteralPath $resolved -AclObject $acl
    foreach($file in Get-ChildItem -LiteralPath $resolved -File -Force){if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){Fail "Secret file cannot be a reparse point: $($file.Name)"};if($file.Name -notmatch '^deposit-(?:bootstrap\.secret|.+-cursor\.key)(?:\.[0-9a-f]{32}\.partial)?$'){Fail "Unexpected file in the Deposit secret directory: $($file.Name)"};$fileAcl=New-Object System.Security.AccessControl.FileSecurity;$fileAcl.SetAccessRuleProtection($true,$false);$fileAcl.SetOwner($operator);$fileAcl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($operator,'FullControl','None','None','Allow')));$fileAcl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($service,'Modify','None','None','Allow')));Set-Acl -LiteralPath $file.FullName -AclObject $fileAcl}
    Set-ControlDirectoryAccess $resolved $operator $service
}
function Set-ControlDirectoryAccess([string]$SecretRoot,$Operator,$Runtime) {
    $control=Join-Path $SecretRoot 'deposit-control';if(!(Test-Path -LiteralPath $control)){return}
    $pending=New-Object System.Collections.Generic.Queue[string];$pending.Enqueue($control)
    $entries=New-Object System.Collections.Generic.List[object]
    $uuid='[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
    while($pending.Count){$directory=$pending.Dequeue();$item=Get-Item -LiteralPath $directory -Force;if(!$item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){Fail 'Unsafe shutdown control directory during identity migration'};$entries.Add($item)
        foreach($child in Get-ChildItem -LiteralPath $directory -Force){
            if($child.Attributes -band [IO.FileAttributes]::ReparsePoint){Fail 'Shutdown control entries cannot be reparse points'}
            if($child.PSIsContainer){if($directory -ne $control -or $child.Name -notmatch "^$uuid`$"){Fail 'Unexpected shutdown control directory'};$pending.Enqueue($child.FullName)}
            else{$pattern=if($directory -eq $control){"^run\.json(?:\.$uuid\.partial)?`$"}else{"^(?:request|ack)-$uuid\.json(?:\.$uuid\.partial)?`$"};if($child.Name -notmatch $pattern){Fail 'Unexpected shutdown control file'};$entries.Add($child)}
        }
    }
    # Validate the complete tree before changing access; paths come only from the protected fixed directory.
    foreach($entry in $entries){
        if($entry.PSIsContainer){$acl=New-Object System.Security.AccessControl.DirectorySecurity;$acl.SetAccessRuleProtection($true,$false);$acl.SetOwner($Operator);$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($Operator,'FullControl','ContainerInherit,ObjectInherit','None','Allow')));$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($Runtime,'Modify','ContainerInherit,ObjectInherit','None','Allow')))}
        else{$acl=New-Object System.Security.AccessControl.FileSecurity;$acl.SetAccessRuleProtection($true,$false);$acl.SetOwner($Operator);$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($Operator,'FullControl','None','None','Allow')));$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($Runtime,'Modify','None','None','Allow')))}
        Set-Acl -LiteralPath $entry.FullName -AclObject $acl
    }
}
function Migrate-DepositSecrets($State,[string]$ServiceAccount) {
    $config=Join-Path $State.neo4jHome 'conf\deposit.properties';$configText=[IO.File]::ReadAllText($config);$matches=[regex]::Matches($configText,'(?m)^\s*secretRoot\s*=\s*(.*?)\s*$')
    if($matches.Count -gt 1){Fail 'Duplicate secretRoot settings in Deposit configuration'}
    $target=if($matches.Count){[IO.Path]::GetFullPath($matches[0].Groups[1].Value.Trim())}else{Join-Path $State.neo4jHome 'deposit-secrets'}
    $configDirectory=[IO.Path]::GetFullPath((Join-Path $State.neo4jHome 'conf')).TrimEnd('\')+'\';if($target.StartsWith($configDirectory,[StringComparison]::OrdinalIgnoreCase)){Fail 'Deposit secretRoot must be outside Neo4j conf'}
    $sourceDirectory=if($matches.Count){$target}else{Join-Path $State.neo4jHome 'conf'}
    if($sourceDirectory -ne $target){$sourceItem=Get-Item -LiteralPath $sourceDirectory -Force;if($sourceItem.Attributes -band [IO.FileAttributes]::ReparsePoint){Fail 'Existing secret directory cannot be a reparse point'}}
    if(Test-Path -LiteralPath $target -PathType Container){foreach($partial in Get-ChildItem -LiteralPath $target -File -Force|Where-Object {$_.Name -match '^deposit-(?:bootstrap\.secret|.+-cursor\.key)\.[0-9a-f]{32}\.partial$'}){Remove-Item -LiteralPath $partial.FullName -Force}}
    Set-SecretDirectoryAccess $target $ServiceAccount
    $sources=@();if($sourceDirectory -ne $target){$sources+=@(Get-ChildItem -LiteralPath $sourceDirectory -File -Force|Where-Object {$_.Name -eq 'deposit-bootstrap.secret' -or $_.Name -match '^deposit-.+-cursor\.key$'})}
    foreach($source in $sources){if($source.Attributes -band [IO.FileAttributes]::ReparsePoint){Fail "Existing Deposit secret cannot be a reparse point: $($source.Name)"};$destination=Join-Path $target $source.Name;$sourceHash=(Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash
        if(Test-Path -LiteralPath $destination){$destinationItem=Get-Item -LiteralPath $destination -Force;if($destinationItem.Attributes -band [IO.FileAttributes]::ReparsePoint){Fail "Secret destination cannot be a reparse point: $($source.Name)"};if((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sourceHash){Fail "Conflicting existing Deposit secret: $($source.Name)"}}
        else{$partial=$destination+'.'+[guid]::NewGuid().ToString('N')+'.partial';try{Copy-Item -LiteralPath $source.FullName -Destination $partial;if((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $sourceHash){Fail "Deposit secret copy verification failed: $($source.Name)"};Move-Item -LiteralPath $partial -Destination $destination}catch{Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue;throw}}
    }
    foreach($source in $sources){$destination=Join-Path $target $source.Name;if((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash){Fail "Deposit secret migration changed bytes: $($source.Name)"}}
    if(!$matches.Count){$text=$configText.TrimEnd()+"`r`nsecretRoot="+$target.Replace('\','/')+"`r`n";$tmp=$config+'.'+[guid]::NewGuid().ToString('N')+'.partial';$backup=$config+'.'+[guid]::NewGuid().ToString('N')+'.backup';try{[IO.File]::WriteAllText($tmp,$text,(New-Object Text.UTF8Encoding($false)));[IO.File]::Replace($tmp,$config,$backup);Remove-Item -LiteralPath $backup -Force}finally{Remove-Item -LiteralPath $tmp,$backup -Force -ErrorAction SilentlyContinue}}
    foreach($source in Get-ChildItem -LiteralPath (Join-Path $State.neo4jHome 'conf') -File -Force|Where-Object {$_.Name -eq 'deposit-bootstrap.secret' -or $_.Name -match '^deposit-.+-cursor\.key$'}){$destination=Join-Path $target $source.Name;if(!(Test-Path -LiteralPath $destination -PathType Leaf) -or (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash){Fail "Unadopted or conflicting legacy Deposit secret: $($source.Name)"};Remove-Item -LiteralPath $source.FullName -Force}
    $State.secretRoot=$target;return $target
}
function Setup {
    . (Join-Path $script:PackageRoot 'helpers\Credentials.ps1')
    $configurator=Join-Path $script:PackageRoot 'helpers\Configure-DepositDownloads.ps1'
    if($Arguments.Count -notin @(4,5)){Fail 'Usage: deposit.bat setup <neo4j-home> <java-home> <data-root> <export-root> [bolt-port]'}
    $neo4j=[IO.Path]::GetFullPath($Arguments[0]);$java=[IO.Path]::GetFullPath($Arguments[1]);$data=[IO.Path]::GetFullPath($Arguments[2]);$exports=[IO.Path]::GetFullPath($Arguments[3])
    $boltPort=7687;if($Arguments.Count -eq 5){if($Arguments[4] -notmatch '^\d{1,5}$'){Fail 'Bolt port must be an integer in 1..65535'};$boltPort=[int]$Arguments[4]};if($boltPort -lt 1 -or $boltPort -gt 65535){Fail 'Bolt port must be in 1..65535'}
    foreach($path in @((Join-Path $neo4j 'bin\neo4j.bat'),(Join-Path $neo4j 'bin\neo4j-admin.bat'),(Join-Path $java 'bin\java.exe'))){if(!(Test-Path -LiteralPath $path -PathType Leaf)){Fail "Pinned runtime file not found: $path"}}
    $env:JAVA_HOME=$java;$env:NEO4J_HOME=$neo4j;$env:NEO4J_CONF=Join-Path $neo4j 'conf'
    $env:PATH=(Join-Path $java 'bin')+';'+$env:PATH
    $versionOutput=& (Join-Path $neo4j 'bin\neo4j-admin.bat') version 2>&1 | Out-String
    if($LASTEXITCODE -ne 0 -or $versionOutput -notmatch [regex]::Escape([string]$script:Manifest.neo4jVersion)){Fail 'Selected Neo4j version does not match the package runtime manifest'}
    if(Test-Path -LiteralPath $script:StatePath){Fail 'This distribution is already configured; use its recorded installation or a fresh package directory'}
    foreach($dir in @($data,$exports)){New-Item -ItemType Directory -Path $dir -Force|Out-Null}
    foreach($dir in @('staging','ocfl','ocfl-work')){New-Item -ItemType Directory -Path (Join-Path $data $dir) -Force|Out-Null}
    $pluginDir=Join-Path $neo4j 'plugins';New-Item -ItemType Directory -Path $pluginDir -Force|Out-Null
    $releaseJars=@(Get-ChildItem -LiteralPath (Join-Path $script:PackageRoot 'plugins') -Filter '*.jar' -File)
    if(!$releaseJars.Count){Fail 'The release package has no plugin artifacts'}
    foreach($jar in $releaseJars){$target=Join-Path $pluginDir $jar.Name;if(Test-Path -LiteralPath $target){Fail "Refusing to replace an existing plugin: $($jar.Name)"}}
    $conf=Join-Path $neo4j 'conf';New-Item -ItemType Directory -Path $conf -Force|Out-Null
    $neo4jConfig=Join-Path $conf 'neo4j.conf';$depositConfig=Join-Path $conf 'deposit.properties'
    if(!(Test-Path -LiteralPath $neo4jConfig)){Copy-Item -LiteralPath (Join-Path $script:PackageRoot 'conf\neo4j.conf') -Destination $neo4jConfig}
    if(Test-Path -LiteralPath $depositConfig){Fail 'Refusing to replace existing conf/deposit.properties'}
    Copy-Item -LiteralPath (Join-Path $script:PackageRoot 'conf\deposit.properties') -Destination $depositConfig
    $secretRoot=Join-Path $neo4j 'deposit-secrets';New-Item -ItemType Directory -Path $secretRoot -Force|Out-Null;Set-OwnerOnlyDirectory $secretRoot
    & $configurator -ConfigPath $depositConfig -ResourceRoot $data -ExportRoot $exports -ListenAddress '127.0.0.1' -SecretRoot $secretRoot | Out-Null
    if($LASTEXITCODE -and $LASTEXITCODE -ne 0){Fail 'Download configuration failed'}
    $depositConfigForward=$depositConfig.Replace('\','/')
    Set-Neo4jSetting $neo4jConfig 'server.default_listen_address' '127.0.0.1'
    Set-Neo4jSetting $neo4jConfig 'server.bolt.enabled' 'true'
    Set-Neo4jSetting $neo4jConfig 'server.bolt.listen_address' "127.0.0.1:$boltPort"
    Set-Neo4jSetting $neo4jConfig 'server.bolt.advertised_address' "127.0.0.1:$boltPort"
    Set-Neo4jSetting $neo4jConfig 'server.http.enabled' 'false'
    Set-Neo4jSetting $neo4jConfig 'server.https.enabled' 'false'
    Set-Neo4jSetting $neo4jConfig 'dbms.security.auth_enabled' 'true'
    Set-Neo4jSetting $neo4jConfig 'db.query.default_language' 'CYPHER_25'
    Set-Neo4jSetting $neo4jConfig 'dbms.security.procedures.allowlist' 'deposit.*,apoc.version'
    Set-Neo4jSetting $neo4jConfig 'server.jvm.additional' "-Ddeposit.config=$depositConfigForward"
    $serviceName='DepositNeo4j-'+([guid]::NewGuid().ToString('N').Substring(0,8))
    Set-Neo4jSetting $neo4jConfig 'server.windows_service_name' $serviceName
    $state=[ordered]@{schemaVersion=1;mode='local';neo4jHome=$neo4j;javaHome=$java;dataRoot=$data;exportRoot=$exports;secretRoot=$secretRoot;serviceName=$serviceName;distributionHome=$script:PackageRoot;neo4jVersion=[string]$script:Manifest.neo4jVersion;createdUtc=[DateTime]::UtcNow.ToString('o')}
    $copied=@()
    try {
        foreach($jar in $releaseJars){Copy-Item -LiteralPath $jar.FullName -Destination $pluginDir; $copied+=Join-Path $pluginDir $jar.Name}
        Save-State $state
    } catch {
        foreach($path in $copied){Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue}
        Remove-Item -LiteralPath $depositConfig -Force -ErrorAction SilentlyContinue
        throw
    }
    Write-Output "Deposit installed into Neo4j $($script:Manifest.neo4jVersion). Start with: deposit.bat start"
}
function Test-NoConflictingRuntime([string]$Neo4jHome) {
    $needle=[IO.Path]::GetFullPath($Neo4jHome).TrimEnd('\')
    $found=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains($needle) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'})
    if($found.Count){Fail 'A Neo4j process already uses this installation. Local and service modes cannot run together.'}
}
function Set-RuntimeEnvironment($State) {
    $env:JAVA_HOME=$State.javaHome;$env:NEO4J_HOME=$State.neo4jHome;$env:NEO4J_CONF=Join-Path $State.neo4jHome 'conf'
    $env:PATH=(Join-Path $State.javaHome 'bin')+';'+$env:PATH
}
function Get-IcaclsPath([string]$Path) {
    if($Path.StartsWith('\\?\',[StringComparison]::OrdinalIgnoreCase)){return $Path}
    $absolute=[IO.Path]::GetFullPath($Path)
    if($absolute.StartsWith('\\')){return '\\?\UNC\'+$absolute.Substring(2)}
    return '\\?\'+$absolute
}
function Service([string]$Operation,$State) {
    Set-RuntimeEnvironment $State
    $neo4j=Join-Path $State.neo4jHome 'bin\neo4j.bat'
    if($Operation -eq 'install'){
        . (Join-Path $script:PackageRoot 'helpers\Credentials.ps1')
        Test-NoConflictingRuntime $State.neo4jHome
        Assert-NoInstalledDepositService $State
        if($Arguments.Count -gt 1){Fail 'Usage: deposit.bat install-service [dedicated-account]'}
        $account=if($Arguments.Count -eq 1){$Arguments[0]}else{"NT SERVICE\$($State.serviceName)"}
        if($account -match '[\r\n]'){Fail 'Invalid service account'}
        $virtualAccount=$account.StartsWith('NT SERVICE\',[StringComparison]::OrdinalIgnoreCase)
        $credential=$null;$ptr=[IntPtr]::Zero
        if(!$virtualAccount){$credential=Read-Host "Password for $account (not echoed or logged)" -AsSecureString;$ptr=[Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($credential)}
        try {
            & $neo4j windows-service install
            if($LASTEXITCODE -ne 0){Fail 'Neo4j Windows service installation failed; check elevated permissions and pinned runtime logs'}
            $password=if($ptr -eq [IntPtr]::Zero){$null}else{[Runtime.InteropServices.Marshal]::PtrToStringUni($ptr)}
            $result=[DepositServiceAccount]::Set($State.serviceName,$account,$password)
            if(!$result){Fail 'Could not assign the dedicated service identity. Remove the partial service, grant Log on as a service, and retry.'}
            $null=Migrate-DepositSecrets $State $account
            foreach($path in @($State.neo4jHome,$State.javaHome)) {& icacls.exe (Get-IcaclsPath $path) '/grant' "$account`:(OI)(CI)RX" '/Q' | Out-Null;if($LASTEXITCODE -ne 0){Fail "Could not grant service read access to $path"}}
            foreach($path in @((Join-Path $State.neo4jHome 'data'),(Join-Path $State.neo4jHome 'logs'),$State.dataRoot,$State.exportRoot)) {New-Item -ItemType Directory -Path $path -Force|Out-Null;& icacls.exe (Get-IcaclsPath $path) '/grant' "$account`:(OI)(CI)M" '/T' '/Q' | Out-Null;if($LASTEXITCODE -ne 0){Fail "Could not grant service access to $path"}}
            Set-DepositServiceState $State $account;Save-State $State
        } finally {if($ptr -ne [IntPtr]::Zero){[Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($ptr)};if($credential){$credential.Dispose()};$password=$null}
        Write-Output "Service $($State.serviceName) installed for $account. Start with: deposit.bat start"
        return
    }
    $service=Get-Service -Name $State.serviceName -ErrorAction SilentlyContinue
    if($Operation -eq 'remove'){
        if($service -and $service.Status -ne 'Stopped'){$serviceIdentity=Get-DepositServiceIncarnation $State;$drain=Drain-ServiceInstallation $State $serviceIdentity;Assert-DepositServiceIncarnation $State $serviceIdentity $drain;Stop-Service -Name $State.serviceName -ErrorAction Stop; $service.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(60))}
        & $neo4j windows-service uninstall
        if($LASTEXITCODE -ne 0){Fail 'Neo4j Windows service removal failed'}
        $null=Migrate-DepositSecrets $State ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name)
        $State.mode='local';$State.PSObject.Properties.Remove('serviceAccount');Save-State $State
        Write-Output 'Deposit Windows service removed.';return
    }
    if($Operation -eq 'start'){
        Test-NoConflictingRuntime $State.neo4jHome
        if(!$service){Fail 'Service is not installed. Run deposit.bat install-service <dedicated-account> from an elevated shell.'}
        Start-Service -Name $State.serviceName -ErrorAction Stop;return
    }
    if($Operation -eq 'stop') {if($service -and $service.Status -ne 'Stopped'){$serviceIdentity=Get-DepositServiceIncarnation $State;$drain=Drain-ServiceInstallation $State $serviceIdentity;Assert-DepositServiceIncarnation $State $serviceIdentity $drain;Stop-Service -Name $State.serviceName -ErrorAction Stop;$service.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(60))};return}
    if($Operation -eq 'status') {if(!$service){Write-Output 'Service is not installed.';exit 3};Write-Output "Service status: $($service.Status)";if($service.Status -ne 'Running'){exit 3};return}
}
function Start-Local($State) {
    Test-NoConflictingRuntime $State.neo4jHome
    if(Get-Service -Name $State.serviceName -ErrorAction SilentlyContinue){Fail 'This installation has a registered Windows service. Use its service mode or remove the service first.'}
    Set-RuntimeEnvironment $State
    $worker=Join-Path $script:PackageRoot 'helpers\deposit-worker.ps1';$logs=Join-Path $State.neo4jHome 'logs';New-Item -ItemType Directory -Path $logs -Force|Out-Null
    $out=Join-Path $logs 'deposit-console.out.log';$err=Join-Path $logs 'deposit-console.err.log'
    $log=Join-Path $logs 'neo4j.log';$oldLength=if(Test-Path -LiteralPath $log){(Get-Item -LiteralPath $log).Length}else{0}
    $arguments="-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$worker`" -Neo4jHome `"$($State.neo4jHome)`" -JavaHome `"$($State.javaHome)`""
    $proc=Start-Process powershell.exe -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    $deadline=[DateTime]::UtcNow.AddSeconds(120)
    while([DateTime]::UtcNow -lt $deadline){$proc.Refresh();if($proc.HasExited){Fail "Neo4j exited during startup. Inspect $err"};if(Test-Path -LiteralPath $log){$stream=[IO.File]::Open($log,'Open','Read','ReadWrite');try{[void]$stream.Seek($oldLength,'Begin');$reader=New-Object IO.StreamReader($stream);$newLog=$reader.ReadToEnd()}finally{$stream.Dispose()};if($newLog -match 'Started\.'){$server=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'"|Where-Object {$_.CommandLine -and $_.CommandLine.Contains([IO.Path]::GetFullPath($State.neo4jHome)) -and $_.CommandLine -match 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'}|Select-Object -First 1);if($server.Count){@{pid=[int]$server[0].ProcessId;created=$server[0].CreationDate.ToUniversalTime().ToString('o');workerPid=$proc.Id;mode='local'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $State.neo4jHome 'deposit-process.json') -Encoding UTF8;Write-Output 'Neo4j started.';return}}};Start-Sleep -Milliseconds 500}
    Fail "Neo4j startup timed out. Inspect $log and $err"
}
function Stop-Local($State,[switch]$Force) {
    $file=Join-Path $State.neo4jHome 'deposit-process.json';if(!(Test-Path -LiteralPath $file)){Write-Output 'Deposit local process is not running.';return}
    $identity=Get-Content -LiteralPath $file -Raw|ConvertFrom-Json;$proc=Get-CimInstance Win32_Process -Filter "ProcessId=$($identity.pid)"
    if(!$proc -or !$proc.CreationDate -or !$proc.CommandLine -or !$proc.CommandLine.Contains([IO.Path]::GetFullPath($State.neo4jHome)) -or $proc.CreationDate.ToUniversalTime().ToString('o') -ne $identity.created){Fail 'The recorded PID does not identify this installation; refusing to stop it'}
    $process=Get-Process -Id $identity.pid
    [void]$process.Handle
    if($Force){Stop-Process -Id $identity.pid -Force}
    else {$drain=Drain-Installation $State;$identityBase64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($drain|ConvertTo-Json -Compress)));& (Join-Path $script:PackageRoot 'helpers\Stop-Console.ps1') -IdentityBase64 $identityBase64}
    if(!$process.WaitForExit(60000)){Fail 'Neo4j did not stop cleanly within 60 seconds'}
    Remove-Item -LiteralPath $file -Force
}
function Status($State) {
    if($State.mode -eq 'service'){Service 'status' $State;return}
    $file=Join-Path $State.neo4jHome 'deposit-process.json';if(!(Test-Path -LiteralPath $file)){Write-Output 'Deposit local process is stopped.';exit 3}
    $identity=Get-Content -LiteralPath $file -Raw|ConvertFrom-Json;$process=Get-CimInstance Win32_Process -Filter "ProcessId=$($identity.pid)"
    if(!$process -or !$process.CommandLine -or !$process.CommandLine.Contains([IO.Path]::GetFullPath($State.neo4jHome)) -or $process.CreationDate.ToUniversalTime().ToString('o') -ne $identity.created){Write-Output 'Recorded Deposit local process is not running.';exit 3}
    Write-Output "Deposit local process is running (PID $($identity.pid))."
}
function Diagnose($State) {
    $report=[ordered]@{version=$State.neo4jVersion;mode=$State.mode;neo4jHome=$State.neo4jHome;javaHome=$State.javaHome;dataRoot=$State.dataRoot;exportRoot=$State.exportRoot;serviceName=$State.serviceName;runtimeManifest=(Get-FileHash (Join-Path $script:PackageRoot 'artifacts.json') -Algorithm SHA256).Hash.ToLowerInvariant();pluginCount=@(Get-ChildItem (Join-Path $State.neo4jHome 'plugins') -Filter '*deposit*.jar' -ErrorAction SilentlyContinue).Count;logs=Join-Path $State.neo4jHome 'logs'}
    $report|ConvertTo-Json -Depth 5
}
function Show-Help { @'
Deposit Windows launcher
  deposit.bat setup <neo4j-home> <java-home> <data-root> <export-root> [bolt-port]
  deposit.bat start | status | stop | diagnose
  deposit.bat install-service [dedicated-account]
  deposit.bat remove-service

Java and Neo4j are separate pinned runtime components. Headless service setup
requires an elevated shell and uses a dedicated virtual service account by default.
See OPERATIONS.md for TLS, firewall, permissions and configuration guidance.
'@ }

try {
    switch($Command.ToLowerInvariant()){
        'help' {Show-Help;exit 0}
        'setup' {Setup;exit 0}
        'start' {$state=Read-State;if($state.mode -eq 'service'){Service 'start' $state}else{Start-Local $state};exit 0}
        'stop' {$state=Read-State;if($state.mode -eq 'service'){Service 'stop' $state}else{Stop-Local $state};exit 0}
        'status' {$state=Read-State;Status $state;exit 0}
        'diagnose' {$state=Read-State;Diagnose $state;exit 0}
        'install-service' {$state=Read-State;Service 'install' $state;exit 0}
        'remove-service' {$state=Read-State;Service 'remove' $state;exit 0}
        default {Show-Help;[Console]::Error.WriteLine("Unknown command: $Command");exit 2}
    }
} catch { [Console]::Error.WriteLine("Deposit launcher: $($_.Exception.Message)");exit 1 }
