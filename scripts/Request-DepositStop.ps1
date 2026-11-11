# Shared by test harness and the packaged launcher. No process signal is sent here.
function Assert-DepositControlPath([string]$Path,[string[]]$AllowedSids,[switch]$File) {
    $item=Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    # Windows can return attributes=-1 when an atomic replacement removes this FileInfo between calls.
    if([int]$item.Attributes -eq -1){throw (New-Object IO.FileNotFoundException('Control record was replaced during inspection'))}
    if(($File -and $item.PSIsContainer) -or (!$File -and !$item.PSIsContainer)){throw 'Invalid Deposit control path type'}
    if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Deposit control paths must not be reparse points'}
    $directory=if($item.PSIsContainer){$item}else{$item.Directory}
    for($current=$directory;$null -ne $current;$current=$current.Parent){if($current.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Deposit control paths must not traverse reparse points'}}
    $acl=Get-Acl -LiteralPath $Path
    $owner=$acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value
    if($owner -notin $AllowedSids){throw 'Unexpected Deposit control directory owner'}
    foreach($rule in $acl.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier])) {
        if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin $AllowedSids){throw 'Deposit control ACL grants another identity access'}
    }
    if($File -and $item.Length -gt 4096){throw 'Oversized Deposit control record'}
}
function Read-DepositControlRecord([string]$Path,[string[]]$AllowedSids) {
    for($attempt=0;$attempt -lt 10;$attempt++){
        try{
            Assert-DepositControlPath $Path $AllowedSids -File
            $stream=[IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
            try{$reader=New-Object IO.StreamReader($stream);return $reader.ReadToEnd() | ConvertFrom-Json -ErrorAction Stop}finally{$stream.Dispose()}
        }catch{$cause=$_.Exception.GetBaseException();if(($cause -isnot [IO.IOException] -and $cause -isnot [System.Management.Automation.ItemNotFoundException]) -or $attempt -eq 9){throw};Start-Sleep -Milliseconds 50}
    }
}
function Get-DepositSecretRoot([string]$Installation) {
    $config=Join-Path $Installation 'conf\deposit.properties'
    $matches=[regex]::Matches([IO.File]::ReadAllText($config),'(?m)^\s*secretRoot\s*=\s*(.*?)\s*$')
    if($matches.Count -ne 1 -or [string]::IsNullOrWhiteSpace($matches[0].Groups[1].Value)){throw 'Deposit configuration must contain exactly one secretRoot'}
    $secrets=[IO.Path]::GetFullPath($matches[0].Groups[1].Value)
    if(!(Test-Path -LiteralPath $secrets -PathType Container)){throw 'Configured Deposit secretRoot is unavailable'}
    return $secrets
}
function Assert-DepositServiceBinding($Service,$Process,[string]$ProcessSid,[string]$Neo4jHome,[string]$ServiceName,[string]$ServiceAccount,[long]$ExpectedPid=0,[long]$ExpectedStartMillis=0) {
    if(!$Service -or [string]$Service.Name -ine $ServiceName -or [string]$Service.State -ne 'Running' -or [string]$Service.StartName -ine $ServiceAccount -or [long]$Service.ProcessId -le 0){throw 'Deposit service identity is not running as configured'}
    if(!$Process -or [string]$Process.Name -ine 'prunsrv-amd64.exe' -or [long]$Process.ProcessId -ne [long]$Service.ProcessId){throw 'Deposit service process is not the configured Procrun instance'}
    $expectedExe=[IO.Path]::GetFullPath((Join-Path $Neo4jHome 'bin\tools\prunsrv-amd64.exe'))
    $command=[string]$Service.PathName
    if([string]::IsNullOrWhiteSpace($command)){throw 'Deposit service executable path is missing'}
    $command=$command.Trim()
    if($command.StartsWith('"')){
        $endQuote=$command.IndexOf('"',1)
        if($endQuote -le 1){throw 'Deposit service executable path is malformed'}
        $executable=$command.Substring(1,$endQuote-1)
        $arguments=$command.Substring($endQuote+1).Trim()
    }else{
        if(!$command.StartsWith($expectedExe,[StringComparison]::OrdinalIgnoreCase)){throw 'Deposit service executable path does not match the pinned runtime'}
        $executable=$command.Substring(0,$expectedExe.Length)
        $arguments=$command.Substring($expectedExe.Length).Trim()
    }
    if([IO.Path]::GetFullPath($executable) -ine $expectedExe -or $arguments -cne "//RS//$ServiceName"){throw 'Deposit service command does not match the pinned runtime and service name'}
    if(!$Process.CreationDate){throw 'Cannot establish Deposit service process creation time'}
    $startMillis=([DateTimeOffset]$Process.CreationDate.ToUniversalTime()).ToUnixTimeMilliseconds()
    if(($ExpectedPid -gt 0 -and [long]$Service.ProcessId -ne $ExpectedPid) -or ($ExpectedStartMillis -gt 0 -and $startMillis -ne $ExpectedStartMillis)){throw 'Deposit service process incarnation changed'}
    $accountSid=(New-Object System.Security.Principal.NTAccount($ServiceAccount)).Translate([System.Security.Principal.SecurityIdentifier]).Value
    if(!$ProcessSid -or [string]$ProcessSid -ne [string]$accountSid){throw 'Deposit service process is running under a different identity'}
    return [pscustomobject]@{name=$ServiceName;serviceAccount=$ServiceAccount;pid=[long]$Service.ProcessId;startMillis=$startMillis;runtimeSid=[string]$ProcessSid}
}
function Get-DepositServiceProcessIdentity([string]$Neo4jHome,[string]$ServiceName,[string]$ServiceAccount,[long]$ExpectedPid=0,[long]$ExpectedStartMillis=0) {
    $service=Get-CimInstance Win32_Service -Filter "Name='$ServiceName'"
    if(!$service -or [long]$service.ProcessId -le 0){throw 'Deposit service has no running process'}
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($service.ProcessId)"
    if(!$process){throw 'Cannot inspect Deposit service process'}
    $owner=Invoke-CimMethod -InputObject $process -MethodName GetOwnerSid
    if($owner.ReturnValue -ne 0 -or !$owner.Sid){throw 'Cannot establish Deposit service process identity'}
    return Assert-DepositServiceBinding $service $process ([string]$owner.Sid) $Neo4jHome $ServiceName $ServiceAccount $ExpectedPid $ExpectedStartMillis
}
function Assert-DepositStopIdentity($Identity) {
    $installation=[IO.Path]::GetFullPath([string]$Identity.installation).TrimEnd('\')
    if($Identity.PSObject.Properties['serviceName'] -and $Identity.serviceName){
        $serviceIdentity=Get-DepositServiceProcessIdentity $installation ([string]$Identity.serviceName) ([string]$Identity.serviceAccount) ([long]$Identity.pid) ([long]$Identity.startMillis)
        if([string]$serviceIdentity.runtimeSid -ne [string]$Identity.runtimeSid){throw 'Acknowledged Deposit service identity changed'}
    }else{
        $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($Identity.pid)"
        if(!$process -or !$process.CommandLine -or !$process.CommandLine.Contains($installation) -or
           ([DateTimeOffset]$process.CreationDate.ToUniversalTime()).ToUnixTimeMilliseconds() -ne [long]$Identity.startMillis) {throw 'Acknowledged Deposit process incarnation is no longer running'}
        $owner=Invoke-CimMethod -InputObject $process -MethodName GetOwnerSid
        if($owner.ReturnValue -ne 0 -or [string]$owner.Sid -ne [string]$Identity.runtimeSid){throw 'Acknowledged Deposit runtime identity changed'}
    }
    $allowed=@([string]$Identity.operatorSid,[string]$Identity.runtimeSid)
    $secrets=Get-DepositSecretRoot $installation
    Assert-DepositControlPath $secrets $allowed
    $control=Join-Path $secrets 'deposit-control';Assert-DepositControlPath $control $allowed
    $runDirectory=Join-Path $control ([string]$Identity.incarnation);Assert-DepositControlPath $runDirectory $allowed
    $descriptor=Read-DepositControlRecord (Join-Path $control 'run.json') $allowed
    foreach($key in @('installation','databaseId','pid','startMillis','incarnation')){if([string]$descriptor.$key -cne [string]$Identity.$key){throw 'Deposit runtime incarnation changed after drain acknowledgement'}}
    $requestPath=Join-Path $runDirectory "request-$($Identity.requestId).json"
    $request=Read-DepositControlRecord $requestPath $allowed
    $ack=Read-DepositControlRecord (Join-Path $runDirectory "ack-$($Identity.requestId).json") $allowed
    foreach($key in @('installation','databaseId','pid','startMillis','incarnation','requestId')){
        if([string]$request.$key -cne [string]$Identity.$key -or [string]$ack.$key -cne [string]$Identity.$key){throw 'Deposit request or acknowledgement no longer matches the drained runtime'}
    }
    if($ack.state -ne 'complete' -or $ack.transactions -ne 0 -or $ack.readers -ne 0 -or $ack.handlers -ne 0 -or $ack.storageClosed -ne $true){throw 'Deposit runtime no longer has a complete drain acknowledgement'}
    return $true
}
function Request-DepositStop {
    param([Parameter(Mandatory=$true)][string]$Neo4jHome,[Parameter(Mandatory=$true)][int]$TargetProcessId,[string]$ServiceName,[string]$ServiceAccount,[ValidateRange(1,300)][int]$TimeoutSeconds=240)
    $installation=[IO.Path]::GetFullPath($Neo4jHome).TrimEnd('\')
    if([bool]$ServiceName -ne [bool]$ServiceAccount){throw 'Both service name and service account are required for a service drain'}
    if($ServiceName){
        $serviceIdentity=Get-DepositServiceProcessIdentity $installation $ServiceName $ServiceAccount $TargetProcessId
        $process=Get-CimInstance Win32_Process -Filter "ProcessId=$TargetProcessId";$startMillis=[long]$serviceIdentity.startMillis;$runtimeSid=[string]$serviceIdentity.runtimeSid
    }else{
        $process=Get-CimInstance Win32_Process -Filter "ProcessId=$TargetProcessId"
        if(!$process -or !$process.CommandLine -or !$process.CommandLine.Contains($installation)){throw 'Deposit stop target is not the owned installation'}
        $startMillis=([DateTimeOffset]$process.CreationDate.ToUniversalTime()).ToUnixTimeMilliseconds()
        $owner=Invoke-CimMethod -InputObject $process -MethodName GetOwnerSid
        if($owner.ReturnValue -ne 0 -or !$owner.Sid){throw 'Cannot establish Deposit runtime OS identity'}
        $runtimeSid=[string]$owner.Sid
    }
    $operatorSid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $allowed=@($operatorSid,$runtimeSid)
    $secrets=Get-DepositSecretRoot $installation
    Assert-DepositControlPath $secrets $allowed
    $control=Join-Path $secrets 'deposit-control';Assert-DepositControlPath $control $allowed
    $descriptor=Read-DepositControlRecord (Join-Path $control 'run.json') $allowed
    if($descriptor.pid -ne $TargetProcessId -or $descriptor.startMillis -ne $startMillis -or
       ![string]::Equals([string]$descriptor.installation,$installation,[StringComparison]::OrdinalIgnoreCase) -or
       [string]$descriptor.incarnation -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -or
       [string]$descriptor.databaseId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {throw 'Stale or unsupported Deposit control descriptor'}
    $runDirectory=Join-Path $control $descriptor.incarnation;Assert-DepositControlPath $runDirectory $allowed
    $requestId=[guid]::NewGuid().ToString()
    $request=[ordered]@{installation=$descriptor.installation;databaseId=$descriptor.databaseId;pid=$TargetProcessId;startMillis=$startMillis;incarnation=$descriptor.incarnation;requestId=$requestId}
    $path=Join-Path $runDirectory "request-$requestId.json";$partial=$path+'.'+[guid]::NewGuid().ToString('N')+'.partial'
    try{
        [IO.File]::WriteAllText($partial,($request|ConvertTo-Json -Compress),(New-Object Text.UTF8Encoding($false)))
        $partialAcl=Get-Acl -LiteralPath $partial;$partialAcl.SetOwner([System.Security.Principal.SecurityIdentifier]$operatorSid);Set-Acl -LiteralPath $partial -AclObject $partialAcl
        [IO.File]::Move($partial,$path)
    }finally{if(Test-Path -LiteralPath $partial){Remove-Item -LiteralPath $partial}}
    $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $ackPath=Join-Path $runDirectory "ack-$requestId.json"
    while([DateTime]::UtcNow -lt $deadline){
        if(Test-Path -LiteralPath $ackPath){
            $ack=Read-DepositControlRecord $ackPath $allowed
            foreach($key in $request.Keys){if([string]$ack.$key -cne [string]$request[$key]){throw 'Stale or mismatched Deposit drain acknowledgement'}}
            if($ack.state -eq 'failed'){throw 'Deposit cleanup failed; the runtime was not signalled'}
            if($ack.state -eq 'complete'){
                if($ack.transactions -ne 0 -or $ack.readers -ne 0 -or $ack.handlers -ne 0 -or $ack.storageClosed -ne $true){throw 'Incomplete Deposit cleanup acknowledgement'}
                $result=[pscustomobject]@{installation=$installation;databaseId=[string]$descriptor.databaseId;pid=$TargetProcessId;startMillis=$startMillis;incarnation=[string]$descriptor.incarnation;requestId=$requestId;runtimeSid=$runtimeSid;operatorSid=$operatorSid}
                if($ServiceName){$result|Add-Member -NotePropertyName serviceName -NotePropertyValue $ServiceName;$result|Add-Member -NotePropertyName serviceAccount -NotePropertyValue $ServiceAccount}
                [void](Assert-DepositStopIdentity $result)
                Write-Verbose "Deposit cleanup complete for request $requestId."
                return $result
            }
            if($ack.state -ne 'admissionClosed'){throw 'Unsupported Deposit drain acknowledgement'}
        }
        $current=Get-CimInstance Win32_Process -Filter "ProcessId=$TargetProcessId"
        if(!$current -or ([DateTimeOffset]$current.CreationDate.ToUniversalTime()).ToUnixTimeMilliseconds() -ne $startMillis){throw 'Owned Deposit process exited during its drain'}
        Start-Sleep -Milliseconds 100
    }
    throw 'Deposit cleanup timed out; the runtime was not signalled and admission remains closed'
}
