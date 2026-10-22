function Quote-ScenarioPowerShellString([string]$Value) { "'" + $Value.Replace("'", "''") + "'" }

function Start-ScenarioPowerShellChild([string]$Command,[string]$StartFailureMessage) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Command))
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = Join-Path $PSHOME 'powershell.exe'
    $startInfo.Arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    if (!$process.Start()) { throw $StartFailureMessage }
    return [pscustomobject]@{ Process=$process; StandardOutput=$process.StandardOutput.ReadToEndAsync(); StandardError=$process.StandardError.ReadToEndAsync() }
}

function Get-ScenarioChildOutput([System.Threading.Tasks.Task[string]]$OutputTask,[string]$FailurePrefix,[string]$DescendantMessage,[int]$WaitMilliseconds=5000) {
    $completed=$OutputTask.Wait($WaitMilliseconds)
    if($completed){try{return $OutputTask.Result}catch{return "$FailurePrefix$($_.Exception.Message)"}}
    return $DescendantMessage
}

function Get-ScenarioDownloadProbeResult([string]$BaseUrl) {
    $request=[System.Net.HttpWebRequest]::Create([Uri]::new($BaseUrl.TrimEnd('/')+'/login'))
    $request.Method='GET';$request.AllowAutoRedirect=$false;$request.KeepAlive=$false;$request.Proxy=$null;$request.Timeout=5000
    try {
        $response=$request.GetResponse();$status=[int]$response.StatusCode;$response.Dispose()
        return [pscustomobject]@{Rejected=($status -eq 503);Detail="HTTP $status"}
    } catch [System.Net.WebException] {
        if($_.Exception.Response){$status=[int]$_.Exception.Response.StatusCode;$_.Exception.Response.Dispose();return [pscustomobject]@{Rejected=($status -eq 503);Detail="HTTP $status"}}
        $rejected=$_.Exception.Status -in @([System.Net.WebExceptionStatus]::ConnectFailure,[System.Net.WebExceptionStatus]::ConnectionClosed)
        return [pscustomobject]@{Rejected=$rejected;Detail="Request rejected: $($_.Exception.Status)"}
    } catch {
        return [pscustomobject]@{Rejected=$false;Detail="Probe failed: $($_.Exception.GetType().Name)"}
    } finally {$request.Abort()}
}

function Get-ScenarioServiceStatus([string]$Name,[string]$Status,[int]$Seconds) {
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    do {$service=Get-Service -Name $Name -ErrorAction SilentlyContinue;if($service -and [string]$service.Status -eq $Status){return $service};Start-Sleep -Milliseconds 250} while([DateTime]::UtcNow -lt $deadline)
    return $null
}
