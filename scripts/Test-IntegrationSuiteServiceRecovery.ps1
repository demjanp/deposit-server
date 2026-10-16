[CmdletBinding()]
param()

$ErrorActionPreference='Stop'
$principal=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if(!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'Run this focused service-recovery check from an elevated PowerShell window.'}

$sc=Join-Path $env:SystemRoot 'System32\sc.exe'
$name='DepositRecoveryProbe-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$created=$false
try {
    $create=& $sc create $name binPath= "$env:SystemRoot\System32\svchost.exe -k LocalServiceNetworkRestricted" start= demand 2>&1
    if($LASTEXITCODE -ne 0){throw "Could not create disposable service probe: $($create -join ' ')"}
    $created=$true

    $configure=& $sc failure $name reset= 0 actions= restart/60000 2>&1
    if($LASTEXITCODE -ne 0){throw "Could not configure initial service recovery action: $($configure -join ' ')"}
    $initial=& $sc qfailure $name 2>&1 | Out-String
    if($LASTEXITCODE -ne 0 -or $initial -notmatch '(?i)RESTART'){throw "Initial recovery action was not visible in SCM query: $initial"}

    $disable=& $sc failure $name reset= 0 actions= '""' 2>&1
    if($LASTEXITCODE -ne 0){throw "Could not disable service recovery: $($disable -join ' ')"}
    $disabled=& $sc qfailure $name 2>&1 | Out-String
    if($LASTEXITCODE -ne 0 -or $disabled -match '(?im)^\s*(?:RESTART|REBOOT|RUN COMMAND)\b'){throw "SCM still reports a recovery action: $disabled"}

    $again=& $sc failure $name reset= 0 actions= '""' 2>&1
    if($LASTEXITCODE -ne 0){throw "Disabling recovery a second time failed: $($again -join ' ')"}
    $confirmed=& $sc qfailure $name 2>&1 | Out-String
    if($LASTEXITCODE -ne 0 -or $confirmed -match '(?im)^\s*(?:RESTART|REBOOT|RUN COMMAND)\b'){throw "Repeated update restored a recovery action: $confirmed"}
    Write-Output 'PASS: Windows PowerShell 5.1 passes an empty actions value to sc.exe and SCM stores no recovery actions.'
} finally {
    if($created){
        $remove=& $sc delete $name 2>&1
        if($LASTEXITCODE -ne 0){Write-Warning "Could not remove disposable service probe ${name}: $($remove -join ' ')"}
        $deadline=[DateTime]::UtcNow.AddSeconds(15)
        do {$remaining=Get-Service -Name $name -ErrorAction SilentlyContinue;if($remaining){Start-Sleep -Milliseconds 200}} while($remaining -and [DateTime]::UtcNow -lt $deadline)
        if($remaining){throw "Disposable service probe remains registered: $name"}
    }
}
