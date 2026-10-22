[CmdletBinding()]
param([ValidateSet('Baseline','Candidate')][string]$Phase='Candidate',[switch]$ChildAclMode)
$ErrorActionPreference='Stop'
$thisScriptPath=$PSCommandPath
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('deposit-scenario-primitives-'+[Guid]::NewGuid().ToString('N'))
if(!$ChildAclMode){New-Item -ItemType Directory -Path $testRoot|Out-Null}
if(-not ('R7ProbeServer' -as [type])){Add-Type -TypeDefinition @'
using System;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
public sealed class R7ProbeServer : IDisposable {
    readonly HttpListener listener=new HttpListener();
    public string Url { get; private set; }
    public R7ProbeServer(int status,int delay,bool closeBeforeHeaders) {
        var reserve=new TcpListener(IPAddress.Loopback,0);reserve.Start();int port=((IPEndPoint)reserve.LocalEndpoint).Port;reserve.Stop();
        Url="http://127.0.0.1:"+port;listener.Prefixes.Add(Url+"/");listener.Start();
        Task.Run(()=>{try{var context=listener.GetContext();if(delay>0)Thread.Sleep(delay);if(closeBeforeHeaders)context.Response.Abort();else{context.Response.StatusCode=status;if(status==302)context.Response.RedirectLocation="http://127.0.0.1:1/redirect-target";context.Response.Close();}}catch{}});
    }
    public void Dispose(){try{listener.Close();}catch{}}
}
'@}
if($Phase -eq 'Candidate' -and !$ChildAclMode){. (Join-Path $PSScriptRoot 'ScenarioPrimitives.ps1')}
try {
    function Get-FunctionSource([string]$Path,[string]$Name) {
        $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$errors)
        if($errors.Count){throw "Parse failure in $Path"}
        $function=$ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name},$true)|Select-Object -First 1
        if(!$function){throw "Missing function $Name in $Path"};return $function.Extent.Text
    }
    function Assert-Equal($Expected,$Actual,[string]$Message) { if($Expected -cne $Actual){throw "$Message`nExpected: [$Expected]`nActual:   [$Actual]"} }
    function Get-Probe([string]$FunctionName,[string]$BaseUrl) { & $FunctionName $BaseUrl }
    function Test-ProbeImplementations {
        if($Phase -eq 'Baseline'){
            $local=Join-Path $PSScriptRoot 'Invoke-LocalShutdownDrainScenario.ps1';$service=Join-Path $PSScriptRoot 'Invoke-ServiceShutdownScenario.ps1'
            Invoke-Expression (Get-FunctionSource $local 'Get-DownloadProbeResult')
            $functionName='Get-DownloadProbeResult'
            $localSource=Get-FunctionSource $local 'Get-DownloadProbeResult';$serviceSource=Get-FunctionSource $service 'Get-DownloadProbeResult'
            if(($localSource -replace '[;\s]','') -cne ($serviceSource -replace '[;\s]','')){throw 'The two baseline download probes differ beyond statement formatting.'}
        } else { $functionName='Get-ScenarioDownloadProbeResult' }
        foreach($case in @(@{Status=200;Rejected=$false;Detail='HTTP 200'},@{Status=302;Rejected=$false;Detail='HTTP 302'},@{Status=503;Rejected=$true;Detail='HTTP 503'},@{Status=500;Rejected=$false;Detail='HTTP 500'})){
            $server=[R7ProbeServer]::new($case.Status,0,$false)
            try{$result=Get-Probe $functionName $server.Url;Assert-Equal $case.Rejected ([bool]$result.Rejected) "Probe HTTP $($case.Status) classification";Assert-Equal $case.Detail $result.Detail "Probe HTTP $($case.Status) detail"}finally{$server.Dispose()}
        }
        $closed=[R7ProbeServer]::new(200,0,$true)
        try{$result=Get-Probe $functionName $closed.Url;if($result.Rejected){throw 'Closed HTTP connection must remain non-rejected.'}}finally{$closed.Dispose()}
        $reserve=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$reserve.Start();$unusedPort=([Net.IPEndPoint]$reserve.LocalEndpoint).Port;$reserve.Stop()
        $result=Get-Probe $functionName "http://127.0.0.1:$unusedPort"
        if(!$result.Rejected -or $result.Detail -notmatch 'ConnectFailure'){throw "Connect failure classification changed: $($result.Detail)"}
        $slow=[R7ProbeServer]::new(200,6000,$false)
        try{$result=Get-Probe $functionName $slow.Url;if($result.Rejected){throw 'HTTP timeout must remain non-rejected.'};if($result.Detail -notmatch 'Timeout|failed'){throw "Unexpected timeout detail: $($result.Detail)"}}finally{$slow.Dispose()}
        $probeSource=if($Phase -eq 'Baseline'){$localSource}else{(Get-FunctionSource (Join-Path $PSScriptRoot 'ScenarioPrimitives.ps1') 'Get-ScenarioDownloadProbeResult')}
        if(([regex]::Matches($probeSource,'\.Dispose\(').Count -lt 2) -or $probeSource -notmatch '\.Abort\(' -or $probeSource -notmatch 'AllowAutoRedirect\s*=\s*\$false' -or $probeSource -notmatch 'KeepAlive\s*=\s*\$false' -or $probeSource -notmatch 'Proxy\s*=\s*\$null' -or $probeSource -notmatch 'Timeout\s*=\s*5000' -or $probeSource -notmatch 'catch\s*\{[^}]*Rejected=\$false'){throw 'Probe response/request cleanup, generic failure classification, or HTTP settings changed.'}
    }
    function Test-ServicePoll {
        $shutdownSource=Get-FunctionSource (Join-Path $PSScriptRoot 'Invoke-ServiceShutdownScenario.ps1') 'Wait-ServiceStatus'
        $crashSource=Get-FunctionSource (Join-Path $PSScriptRoot 'Invoke-ServiceCrashRecoveryScenario.ps1') 'Wait-ServiceStatus'
        Invoke-Expression $shutdownSource
        $script:servicePollCount=0
        function Get-Service {param($Name,$ErrorAction);$script:servicePollCount++;if($script:servicePollCount -lt 2){return $null};[pscustomobject]@{Name=$Name;Status='Running'}}
        function Start-Sleep {param($Milliseconds)}
        $running=Wait-ServiceStatus 'fixture' 'Running' 1
        if($Phase -eq 'Candidate' -and !$running){throw 'Shutdown wait wrapper did not return matching service.'}
        if($script:servicePollCount -ne 2){throw "Expected matching service after two polls; saw $script:servicePollCount"}
        $script:servicePollCount=0
        try{Wait-ServiceStatus 'fixture' 'Stopped' 0;if($Phase -eq 'Baseline'){throw 'Baseline shutdown wrapper must throw on timeout.'}else{throw 'Candidate shutdown wrapper must throw on timeout.'}}catch{if($_.Exception.Message -notmatch 'did not reach Stopped'){throw}}
        Invoke-Expression $crashSource
        $script:servicePollCount=0
        $successfulOutput=@(Wait-ServiceStatus 'fixture' 'Running' 1)
        if($successfulOutput.Count){throw 'Crash recovery service wait must suppress the matching service object on success.'}
        $script:servicePollCount=0
        try{Wait-ServiceStatus 'fixture' 'Stopped' 0;throw 'Crash recovery service wait must throw on timeout.'}catch{if($_.Exception.Message -cne 'Service fixture did not reach Stopped within 0 seconds'){throw}}
        if($Phase -eq 'Candidate'){$poll=Get-ScenarioServiceStatus 'fixture' 'Stopped' 0;if($null -ne $poll){throw 'Shared service poll timeout must return null.'}}
    }
    function Test-OutputCollection {
        $command='cmd.exe /c start /b "" powershell.exe -NoProfile -NonInteractive -Command "Start-Sleep -Seconds 8";[Console]::WriteLine("parent-exit")'
        $child=if($Phase -eq 'Baseline'){Start-PowerShellChild $command}else{Start-ScenarioPowerShellChild $command 'scenario start failed'}
        if(!$child.Process.WaitForExit(10000)){throw 'Output fixture parent did not exit.'}
        if($Phase -eq 'Baseline'){
            $stdout=Join-Path $testRoot 'descendant.stdout';$stderr=Join-Path $testRoot 'descendant.stderr';$utf8=New-Object System.Text.UTF8Encoding -ArgumentList @($false)
            $code=Complete-PowerShellChild $child $stdout $stderr 1000
            if($code -ne 0){throw "Descendant output fixture exited $code"}
            $descendantText=[IO.File]::ReadAllText($stdout);if($descendantText -notmatch 'descendant kept stdout open'){throw "Baseline did not bound descendant-held stdout collection: [$descendantText]"}
            $process=Start-PowerShellChild 'exit 0';[void]$process.Process.WaitForExit(5000)
            $fault=[Threading.Tasks.TaskCompletionSource[string]]::new();$fault.SetException([InvalidOperationException]::new('read fault'))
            $process.StandardOutput=$fault.Task;$process.StandardError=[Threading.Tasks.Task]::FromResult('')
            try{Complete-PowerShellChild $process (Join-Path $testRoot 'fault.out') (Join-Path $testRoot 'fault.err') 1000|Out-Null;throw 'A failed Task.Wait was swallowed.'}catch [AggregateException]{}
        } else {
            $stdout=Get-ScenarioChildOutput $child.StandardOutput '' 'Scenario child exited, but a descendant kept stdout open; remaining output was omitted.'
            $stderr=Get-ScenarioChildOutput $child.StandardError '' 'Scenario child exited, but a descendant kept stderr open; remaining output was omitted.'
            if($stdout -notmatch 'descendant kept stdout open'){throw 'Shared bounded output did not detect descendant-held stdout.'}
            $fault=[Threading.Tasks.TaskCompletionSource[string]]::new();$fault.SetException([InvalidOperationException]::new('read fault'))
            try{Get-ScenarioChildOutput $fault.Task '' 'fallback'|Out-Null;throw 'A failed Task.Wait was swallowed.'}catch [AggregateException]{}
        }
    }
    function Test-OwnerCompletionPolicies {
        if($Phase -ne 'Candidate'){return}
        $utf8=New-Object System.Text.UTF8Encoding -ArgumentList @($false)
        $scenario=Join-Path $PSScriptRoot 'Invoke-TestScenario.ps1'
        Invoke-Expression (Get-FunctionSource $scenario 'Format-PowerShellArguments')
        $unicode=[string][char]0x96ea+'/'+[char]0x0394;$arguments=@('space value',"apostrophe's",'', $unicode)
        $argvPath=Join-Path $testRoot 'candidate-argv.json';$fixture=Join-Path $testRoot 'candidate-argv.ps1'
        $fixtureBody=('[CmdletBinding()]'+[Environment]::NewLine+'param([Parameter(ValueFromRemainingArguments=$true)][string[]]$InputValue)'+[Environment]::NewLine+'[IO.File]::WriteAllText({0},(@($InputValue)|ConvertTo-Json -Compress))' -f (Quote-ScenarioPowerShellString $argvPath))
        [IO.File]::WriteAllText($fixture,$fixtureBody,(New-Object System.Text.UTF8Encoding -ArgumentList @($false)))
        Invoke-Expression (Get-FunctionSource $scenario 'Complete-PowerShellChild')
        $child=Start-ScenarioPowerShellChild ('& '+(Quote-ScenarioPowerShellString $fixture)+' '+(Format-PowerShellArguments $arguments)) 'scenario start failed';$processHandle=$child.Process.SafeHandle
        $code=Complete-PowerShellChild $child (Join-Path $testRoot 'candidate.stdout') (Join-Path $testRoot 'candidate.stderr') 15000
        if($code -ne 0){throw "Candidate argument child exited $code"}
        $argv=[IO.File]::ReadAllText($argvPath,[Text.Encoding]::UTF8)|ConvertFrom-Json
        Assert-Equal ($arguments -join '|') (@($argv) -join '|') 'Candidate actual argv changed.'
        $child=Start-ScenarioPowerShellChild 'exit 7' 'scenario start failed';$code=Complete-PowerShellChild $child (Join-Path $testRoot 'nonzero.stdout') (Join-Path $testRoot 'nonzero.stderr') 15000
        Assert-Equal 7 $code 'Scenario owner exit-code propagation changed.'
        if($processHandle.IsClosed){throw 'TestScenario completion unexpectedly disposed its process.'}
        $child=Start-ScenarioPowerShellChild 'Start-Sleep -Seconds 10' 'scenario start failed'
        try{Complete-PowerShellChild $child (Join-Path $testRoot 'timeout.stdout') (Join-Path $testRoot 'timeout.stderr') 100|Out-Null;throw 'TestScenario timeout was not reported.'}catch{if($_.Exception.Message -notmatch 'subprocess timed out'){throw}}
        if(!$child.Process.HasExited){throw 'TestScenario timeout did not kill the child.'};$null=$child.Process.Handle;$child.Process.Dispose()
        $local=Join-Path $PSScriptRoot 'Invoke-LocalShutdownDrainScenario.ps1';$service=Join-Path $PSScriptRoot 'Invoke-ServiceShutdownScenario.ps1'
        foreach($owner in @(@{Path=$local;Name='local'},@{Path=$service;Name='service'})){
            Invoke-Expression (Get-FunctionSource $owner.Path 'Complete-PowerShellChild')
            if($owner.Name -eq 'service'){$script:completionLog=New-Object 'System.Collections.Generic.List[string]';$utf8=New-Object System.Text.UTF8Encoding -ArgumentList @($false);$LogPath=Join-Path $testRoot 'service.log';function Write-ScenarioLog([string]$Text){$script:completionLog.Add($Text)}}
            $child=Start-ScenarioPowerShellChild 'exit 7' 'scenario start failed';$processHandle=$child.Process.SafeHandle
            if($owner.Name -eq 'local'){$code=Complete-PowerShellChild $child (Join-Path $testRoot 'local.stdout') (Join-Path $testRoot 'local.stderr') 15000}else{$code=Complete-PowerShellChild $child 'service-label' 15000}
            Assert-Equal 7 $code "$($owner.Name) owner exit-code propagation changed."
            if(!$processHandle.IsClosed){throw "$($owner.Name) owner did not dispose its process."}
            $child=Start-ScenarioPowerShellChild 'Start-Sleep -Seconds 10' 'scenario start failed'
            try{if($owner.Name -eq 'local'){Complete-PowerShellChild $child (Join-Path $testRoot 'local-timeout.stdout') (Join-Path $testRoot 'local-timeout.stderr') 100|Out-Null}else{Complete-PowerShellChild $child 'service-timeout' 100|Out-Null};throw "$($owner.Name) timeout was not reported."}catch{if($_.Exception.Message -notmatch 'timed out'){throw}}
            if($child.Process.HasExited){throw "$($owner.Name) timeout unexpectedly killed the child."};$child.Process.Kill();$null=$child.Process.WaitForExit(5000);$child.Process.Dispose()
        }
    }
    if($ChildAclMode){
        $fixtureRoot=$env:DEPOSIT_R7_ACL_FIXTURE;$aclScripts=$env:DEPOSIT_R7_ACL_SCRIPTDIR
        if(!$fixtureRoot){throw 'ACL fixture root missing.'}
        function Get-HarnessRoot { $fixtureRoot }
        function Get-HarnessRunRoot { Join-Path $fixtureRoot 'runs\test-host' }
        function Get-TestHarnessRoot { $fixtureRoot }
        function Get-DepositServerRoot { $PSScriptRoot }
        function Assert-HarnessRoot([string]$Root) { if([IO.Path]::GetFullPath($Root).TrimEnd('\') -ine [IO.Path]::GetFullPath($fixtureRoot).TrimEnd('\')){throw 'Harness root mismatch.'} }
        $watchdog=Join-Path $aclScripts 'IntegrationSuiteWatchdog.ps1';$secrets=Join-Path $aclScripts 'IntegrationHarnessSecrets.ps1'
        if($env:DEPOSIT_R7_ACL_ORDER -eq 'WatchdogFirst'){. $watchdog -LibraryOnly}else{. $secrets}
        . $watchdog -LibraryOnly
        . $secrets
        . $watchdog -LibraryOnly
        if(!('DepositHarnessSecretStore' -as [type]) -or !('DepositSuiteJournalFiles' -as [type])){throw 'ACL owner type was not loaded.'}
        if($Phase -eq 'Candidate' -and !$script:DepositHarnessOwnerOnlyAclMethod){throw 'Shared ACL helper source was not loaded.'}
        $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
        $target='r7-acl-parity-target';[DepositHarnessSecretStore]::Write($target,'first');[DepositHarnessSecretStore]::Write($target,'second')
        if([DepositHarnessSecretStore]::Read($target) -cne 'second'){throw 'Harness secret replacement/read failed.'}
        $secretFiles=@(Get-ChildItem -LiteralPath (Join-Path $fixtureRoot 'private\credentials') -File)
        if($secretFiles.Count -ne 1){throw "Expected one secret backing file, found $($secretFiles.Count)."}
        $secretPath=$secretFiles[0].FullName
        $journal=Join-Path $fixtureRoot 'journal.json';[DepositSuiteJournalFiles]::WriteNew($journal,[Text.Encoding]::UTF8.GetBytes('one'))
        try{[DepositSuiteJournalFiles]::WriteNew($journal,[Text.Encoding]::UTF8.GetBytes('two'));throw 'Journal creation unexpectedly replaced an existing file.'}catch [IO.IOException]{}
        if(!(Test-Path -LiteralPath $secretPath -PathType Leaf)){throw "Expected secret file is missing: $secretPath"}
        $secretAcl=[IO.File]::GetAccessControl($secretPath);$journalAcl=[IO.File]::GetAccessControl($journal)
        foreach($acl in @($secretAcl,$journalAcl)){
            if(!$acl.AreAccessRulesProtected -or !$sid.Equals($acl.GetOwner([Security.Principal.SecurityIdentifier]))){throw 'Harness file ACL owner/protection mismatch.'}
            $rules=$acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]);if($rules.Count -ne 1){throw "Harness file ACL has $($rules.Count) ACEs."}
            $rule=$rules[0];if(!$sid.Equals($rule.IdentityReference) -or $rule.AccessControlType -ne 'Allow' -or $rule.FileSystemRights -ne 'FullControl' -or $rule.IsInherited){throw 'Harness file ACL ACE mismatch.'}
        }
        Write-Output 'PASS: isolated process ACL load order, repeated import, parity, CreateNew and replacement'
    } else {
        if($Phase -eq 'Baseline'){
            $ownerFiles=@('Invoke-TestScenario.ps1','Invoke-LocalShutdownDrainScenario.ps1','Invoke-ServiceShutdownScenario.ps1')|ForEach-Object{Join-Path $PSScriptRoot $_}
            $unicode=[string][char]0x96ea+'/'+[char]0x0394;$hostile=@('space value',"apostrophe's",'', $unicode)
            $quoteSources=@($ownerFiles|ForEach-Object{Get-FunctionSource $_ 'Quote-PowerShell'});if(($quoteSources|Select-Object -Unique).Count -ne 1){throw 'Baseline quote functions differ.'}
            $startSources=@($ownerFiles|ForEach-Object{(Get-FunctionSource $_ 'Start-PowerShellChild') -replace "throw 'Could not start .*? subprocess\.'",'throw <owner-specific-start-error>'});if(($startSources|Select-Object -Unique).Count -ne 1){throw 'Baseline child start mechanics differ.'}
            Invoke-Expression (Get-FunctionSource $ownerFiles[0] 'Quote-PowerShell');Invoke-Expression (Get-FunctionSource $ownerFiles[0] 'Format-PowerShellArguments');Invoke-Expression (Get-FunctionSource $ownerFiles[0] 'Start-PowerShellChild');Invoke-Expression (Get-FunctionSource $ownerFiles[0] 'Complete-PowerShellChild')
            $quotedExpectation="'space value'|'apostrophe''s'|''|'{0}'" -f $unicode
            Assert-Equal $quotedExpectation ((@($hostile|ForEach-Object{Quote-PowerShell $_}) -join '|')) 'Baseline hostile quoting changed.'
            $argvPath=Join-Path $testRoot 'argv.txt';$fixture=Join-Path $testRoot 'argv-fixture.ps1';$quotedOutput=Quote-PowerShell $argvPath
            $fixtureBody=('[CmdletBinding()]'+[Environment]::NewLine+'param([Parameter(ValueFromRemainingArguments=$true)][string[]]$InputValue)'+[Environment]::NewLine+'[IO.File]::WriteAllText({0},(@($InputValue)|ConvertTo-Json -Compress))' -f $quotedOutput)
            [IO.File]::WriteAllText($fixture,$fixtureBody,(New-Object System.Text.UTF8Encoding -ArgumentList @($false)))
            $utf8=New-Object System.Text.UTF8Encoding -ArgumentList @($false);$child=Start-PowerShellChild ('& '+(Quote-PowerShell $fixture)+' '+(Format-PowerShellArguments $hostile));$code=Complete-PowerShellChild $child (Join-Path $testRoot 'out') (Join-Path $testRoot 'err') 15000
            if($code -ne 0){throw "Baseline child exit $code"};$argv=[IO.File]::ReadAllText($argvPath,[Text.Encoding]::UTF8)|ConvertFrom-Json;Assert-Equal ($hostile -join '|') (@($argv) -join '|') 'Baseline actual argv changed.'
            $aclSecrets=Get-Content (Join-Path $PSScriptRoot 'IntegrationHarnessSecrets.ps1') -Raw;$aclWatchdog=Get-Content (Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1') -Raw
            if($aclSecrets -notmatch '(?s)OwnerOnlyFileSecurity\(\).*?SetAccessRuleProtection\(true,false\).*?FileSystemRights\.FullControl' -or $aclWatchdog -notmatch '(?s)OwnerOnlySecurity\(\).*?SetAccessRuleProtection\(true,false\).*?FileSystemRights\.FullControl'){throw 'Baseline ACL construction was not identified.'}
        }
        Test-ProbeImplementations
        Test-ServicePoll
        Test-OutputCollection
        Test-OwnerCompletionPolicies
        if($Phase -in @('Baseline','Candidate')){
            $aclScripts=Join-Path $testRoot 'acl-scripts';New-Item -ItemType Directory -Path $aclScripts|Out-Null
            Copy-Item (Join-Path $PSScriptRoot 'IntegrationHarnessSecrets.ps1'),(Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1') -Destination $aclScripts
            if($Phase -eq 'Candidate'){Copy-Item (Join-Path $PSScriptRoot 'IntegrationHarnessAcl.ps1') -Destination $aclScripts}
            $pathsBody=@'
$script:TestHarnessRoot=[IO.Path]::GetFullPath($env:DEPOSIT_R7_ACL_FIXTURE)
function Get-HarnessRoot { return $script:TestHarnessRoot }
function Get-TestHarnessRoot { return $script:TestHarnessRoot }
function Get-HarnessRunRoot([string]$RunId='test-host') { return (Join-Path (Join-Path $script:TestHarnessRoot 'runs') $RunId) }
function Get-DepositServerRoot { return $PSScriptRoot }
function Assert-HarnessRoot([string]$Root) { if([IO.Path]::GetFullPath($Root).TrimEnd('\') -ine $script:TestHarnessRoot.TrimEnd('\')){throw 'Harness root mismatch.'} }
function Assert-HarnessPlainPath([string]$Path) {
    $full=[IO.Path]::GetFullPath($Path);if($full -ine $script:TestHarnessRoot -and !$full.StartsWith($script:TestHarnessRoot+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Path escaped fixture harness.'}
    for($current=$full;$current;$current=[IO.Path]::GetDirectoryName($current)){if(Test-Path -LiteralPath $current){if((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse path refused.'}}}
}
'@
            [IO.File]::WriteAllText((Join-Path $aclScripts 'HarnessPaths.ps1'),$pathsBody,[Text.Encoding]::UTF8)
            foreach($order in 'SecretsFirst','WatchdogFirst'){$aclRoot=Join-Path $testRoot ('acl-'+$order);New-Item -ItemType Directory -Path $aclRoot|Out-Null;$env:DEPOSIT_R7_ACL_FIXTURE=$aclRoot;$env:DEPOSIT_R7_ACL_SCRIPTDIR=$aclScripts;$env:DEPOSIT_R7_ACL_ORDER=$order;$child=$thisScriptPath
                & (Join-Path $PSHOME 'powershell.exe') -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $child -Phase $Phase -ChildAclMode
                if($LASTEXITCODE -ne 0){throw "$Phase ACL $order process failed ($LASTEXITCODE)."}}
        }
        Write-Output "PASS: $Phase C15/C16/C17 scenario primitive characterization"
    }
} finally { if(!$ChildAclMode){Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue} }
