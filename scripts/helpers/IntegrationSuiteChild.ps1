function Quote-Suite([string]$Value) { "'"+$Value.Replace("'","''")+"'" }
function Invoke-ContainedSuiteChild([string]$Script,[object[]]$Arguments,[string]$Label,[int]$TimeoutSeconds,[string]$JournalPath,[string]$PrivatePath,[string]$LogsPath,[string]$Invocation,$Watchdog) {
    $identity=[Guid]::NewGuid().ToString('N')
    $gate=Join-Path $PrivatePath ($identity+'.gate')
    $pieces=foreach($arg in $Arguments) {
        if ($arg -is [string[]]) { '@('+ (($arg | ForEach-Object { Quote-Suite $_ }) -join ',') +')' }
        elseif ([string]$arg -match '^-[A-Za-z][A-Za-z0-9]*$') { [string]$arg }
        else { Quote-Suite ([string]$arg) }
    }
    $command='$ErrorActionPreference="Stop"; while (!(Test-Path -LiteralPath '+(Quote-Suite $gate)+')) { Start-Sleep -Milliseconds 100 }; & '+(Quote-Suite $Script)+' '+($pieces -join ' ')+'; if ($null -ne $LASTEXITCODE) { exit $LASTEXITCODE }'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $child=Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $LogsPath ($Label+'.stdout.log')) -RedirectStandardError (Join-Path $LogsPath ($Label+'.stderr.log'))
    try {
        $null=$child.Handle
        $jobName='Local\DepositIT-'+$Invocation+'-'+$identity
        [DepositSuiteJobs]::Create($jobName,$child.Id)
        $entry=[pscustomobject]@{pid=$child.Id;startTicks=$child.StartTime.ToUniversalTime().Ticks;label=$Label;jobName=$jobName;jobAcknowledged=$false}
        Update-SuiteJournal $JournalPath { param($j) $j.children=@($j.children)+@($entry) }
        $ackDeadline=[DateTime]::UtcNow.AddSeconds(30)
        do{
            $ack=@((Read-SuiteJournal $JournalPath).children|Where-Object jobName -CEQ $jobName)
            if($ack.Count -eq 1 -and $ack[0].jobAcknowledged){break}
            if($Watchdog.HasExited -or [DateTime]::UtcNow -gt $ackDeadline){throw 'Watchdog did not hold the child containment job; scenario was not launched'}
            Start-Sleep -Milliseconds 100
        }while($true)
        [IO.File]::WriteAllText($gate,'go')
        Write-Host "Integration harness: $Label"
        if (!$child.WaitForExit($TimeoutSeconds*1000)) { Stop-SuiteChildTree $entry -PreserveRuntime; throw "$Label timed out after $TimeoutSeconds seconds" }
        $child.Refresh()
        if ($child.ExitCode -ne 0) { throw "$Label failed ($($child.ExitCode)); see $LogsPath" }
    } finally {
        if(!$child.HasExited -and !(Test-Path -LiteralPath $gate)){$child.Kill();$null=$child.WaitForExit(5000)}
        if (Test-Path -LiteralPath $gate) { Remove-Item -LiteralPath $gate -Force }
        $child.Dispose()
    }
}