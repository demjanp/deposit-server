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
function Assert-ActiveSuiteInstallation($State,[string]$PackageRoot) {
    $journalPath=[Environment]::GetEnvironmentVariable('DEPOSIT_SUITE_JOURNAL','Process')
    if(!$journalPath){return $null}
    $expected=[IO.Path]::GetFullPath((Join-Path $PackageRoot '..\..\..\private\suite\active.json'))
    if([IO.Path]::GetFullPath($journalPath) -ine $expected){Fail 'Integration service installation requires the active harness journal'}
    $harness=[IO.Path]::GetFullPath((Join-Path $PackageRoot '..\..\..'))
    $current=$expected
    while($current){if((Test-Path -LiteralPath $current) -and ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){Fail 'Integration journal path cannot traverse reparse points'};if($current.TrimEnd('\') -ieq $harness.TrimEnd('\')){break};$current=Split-Path $current -Parent}
    $journalFile=Get-Item -LiteralPath $expected -Force -ErrorAction Stop
    $journalAcl=Get-Acl -LiteralPath $expected
    $ownerSid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    if(!$journalAcl.AreAccessRulesProtected -or !$ownerSid.Equals($journalAcl.GetOwner([Security.Principal.SecurityIdentifier]))){Fail 'Integration journal must have a protected ACL owned by the invoking user'}
    foreach($rule in $journalAcl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
        if($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and !$ownerSid.Equals($rule.IdentityReference)){Fail 'Integration journal grants another identity access'}
    }
    $journal=Get-Content -LiteralPath $expected -Raw -ErrorAction Stop|ConvertFrom-Json -ErrorAction Stop
    $sid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $runner=$null;$watchdogProcess=$null
    try {$runner=Get-Process -Id ([int]$journal.runnerPid) -ErrorAction Stop;$watchdogProcess=Get-Process -Id ([int]$journal.watchdogPid) -ErrorAction Stop} catch {Fail 'Integration runner or cleanup watchdog is no longer active'}
    if($runner.StartTime.ToUniversalTime().Ticks -ne [long]$journal.runnerStartTicks -or !$journal.watchdogStartTicks -or $watchdogProcess.StartTime.ToUniversalTime().Ticks -ne [long]$journal.watchdogStartTicks){Fail 'Integration runner or watchdog process identity changed'}
    $expectedHome=[IO.Path]::GetFullPath((Join-Path $harness 'runs\test-host\neo4j')).TrimEnd('\')
    if(!$journal.watchdogAcknowledged -or $journal.terminalCleanup -or $journal.ownerSid -cne $sid -or
       [IO.Path]::GetFullPath([string]$journal.neo4jHome).TrimEnd('\') -ine $expectedHome -or
       [IO.Path]::GetFullPath($State.neo4jHome).TrimEnd('\') -ine $expectedHome -or
       [string]$journal.serviceName -cne [string]$State.serviceName -or
       [string]$journal.serviceAccount -ine ('NT SERVICE\'+[string]$State.serviceName) -or
       [string]$journal.installationPhase -notin @('installing','configured','running')) { Fail 'Active suite journal does not authorize this exact service installation' }
    return $expected
}
function Set-ActiveSuiteInstallationPhase([string]$JournalPath,[string]$Phase,[string]$PackageRoot) {
    if(!$JournalPath){return}
    $harness=[IO.Path]::GetFullPath((Join-Path $PackageRoot '..\..\..'))
    $watchdog=Join-Path $harness '..\deposit-server\scripts\IntegrationSuiteWatchdog.ps1'
    if(!(Test-Path -LiteralPath $watchdog -PathType Leaf)){Fail 'The integration suite journal writer is missing'}
    . $watchdog -JournalPath $JournalPath -LibraryOnly
    Update-SuiteJournal $JournalPath {param($j) $j.installationPhase=$Phase}
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
function Service([string]$Operation,$State,[string]$StatePath,[string]$PackageRoot,[string[]]$Arguments) {
    Set-RuntimeEnvironment $State
    $neo4j=Join-Path $State.neo4jHome 'bin\neo4j.bat'
    if($Operation -eq 'install'){
        . (Join-Path $PackageRoot 'helpers\Credentials.ps1')
        Test-NoConflictingRuntime $State.neo4jHome
        Assert-NoInstalledDepositService $State
        $suiteJournal=Assert-ActiveSuiteInstallation $State $PackageRoot
        if($Arguments.Count -gt 1){Fail 'Usage: deposit.bat install-service [dedicated-account]'}
        $account=if($Arguments.Count -eq 1){$Arguments[0]}else{"NT SERVICE\$($State.serviceName)"}
        if($account -match '[\r\n]'){Fail 'Invalid service account'}
        $virtualAccount=$account.StartsWith('NT SERVICE\',[StringComparison]::OrdinalIgnoreCase)
        $credential=$null;$ptr=[IntPtr]::Zero
        if(!$virtualAccount){$credential=Read-Host "Password for $account (not echoed or logged)" -AsSecureString;$ptr=[Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($credential)}
        try {
            Set-ActiveSuiteInstallationPhase $suiteJournal 'installing' $PackageRoot
            & $neo4j windows-service install
            if($LASTEXITCODE -ne 0){Fail 'Neo4j Windows service installation failed; check elevated permissions and pinned runtime logs'}
            if($suiteJournal){
                # Set manual start and disable SCM recovery immediately after
                # registration. Neo4j registers first, so a short default-start
                # interval remains while the watchdog supervises installation.
                $serviceConfig=& sc.exe config $State.serviceName start= demand 2>&1
                if($LASTEXITCODE -ne 0){Fail "Could not set the integration service to manual start: $($serviceConfig -join ' ')"}
                $serviceRecovery=& sc.exe failure $State.serviceName reset= 0 actions= '""' 2>&1
                if($LASTEXITCODE -ne 0){Fail "Could not disable integration service recovery: $($serviceRecovery -join ' ')"}
            }
            $password=if($ptr -eq [IntPtr]::Zero){$null}else{[Runtime.InteropServices.Marshal]::PtrToStringUni($ptr)}
            $result=[DepositServiceAccount]::Set($State.serviceName,$account,$password)
            if(!$result){Fail 'Could not assign the dedicated service identity. Remove the partial service, grant Log on as a service, and retry.'}
            Set-ActiveSuiteInstallationPhase $suiteJournal 'configured' $PackageRoot
            $null=Migrate-DepositSecrets $State $account
            foreach($path in @($State.neo4jHome,$State.javaHome)) {& icacls.exe (Get-IcaclsPath $path) '/grant' "$account`:(OI)(CI)RX" '/Q' | Out-Null;if($LASTEXITCODE -ne 0){Fail "Could not grant service read access to $path"}}
            foreach($path in @((Join-Path $State.neo4jHome 'data'),(Join-Path $State.neo4jHome 'logs'),$State.dataRoot,$State.exportRoot)) {New-Item -ItemType Directory -Path $path -Force|Out-Null;& icacls.exe (Get-IcaclsPath $path) '/grant' "$account`:(OI)(CI)M" '/T' '/Q' | Out-Null;if($LASTEXITCODE -ne 0){Fail "Could not grant service access to $path"}}
            Set-DepositServiceState $State $account;Save-State $StatePath $State
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
        $State.mode='local';$State.PSObject.Properties.Remove('serviceAccount');Save-State $StatePath $State
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
