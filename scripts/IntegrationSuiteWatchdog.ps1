[CmdletBinding()]
param([string]$JournalPath,[switch]$CleanupOnly,[switch]$LibraryOnly)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'IntegrationHarnessSecrets.ps1')

if (-not ('DepositSuiteJobs' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Principal;
public static class DepositSuiteJobs {
    [StructLayout(LayoutKind.Sequential)] struct SecurityAttributes { public int Length; public IntPtr Descriptor; [MarshalAs(UnmanagedType.Bool)] public bool Inherit; }
    [DllImport("advapi32.dll",SetLastError=true,CharSet=CharSet.Unicode)] static extern bool ConvertStringSecurityDescriptorToSecurityDescriptor(string text,uint revision,out IntPtr descriptor,out uint size);
    [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr memory);
    [DllImport("kernel32.dll",SetLastError=true,CharSet=CharSet.Unicode)] static extern IntPtr CreateJobObject(ref SecurityAttributes attributes,string name);
    [DllImport("kernel32.dll",SetLastError=true,CharSet=CharSet.Unicode)] static extern IntPtr OpenJobObject(uint access,bool inherit,string name);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool IsProcessInJob(IntPtr process,IntPtr job,out bool member);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr job,int information,IntPtr buffer,uint length,out uint returned);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    static readonly Dictionary<string,IntPtr> jobs = new Dictionary<string,IntPtr>();
    public static void Create(string name,int pid) {
        if(jobs.ContainsKey(name)) throw new InvalidOperationException("Job name already owned");
        IntPtr descriptor; uint size;
        string sid=WindowsIdentity.GetCurrent().User.Value;
        if(!ConvertStringSecurityDescriptorToSecurityDescriptor("D:P(A;;GA;;;"+sid+")",1,out descriptor,out size)) throw new Win32Exception();
        IntPtr job=IntPtr.Zero;
        try {
            var attributes=new SecurityAttributes {Length=Marshal.SizeOf(typeof(SecurityAttributes)),Descriptor=descriptor,Inherit=false};
            job=CreateJobObject(ref attributes,name);
            int error=Marshal.GetLastWin32Error();
            if(job==IntPtr.Zero) throw new Win32Exception(error);
            if(error==183) throw new InvalidOperationException("Job name already exists");
            // New jobs have no limits: neither breakaway flag nor kill-on-close is set.
            using(var process=Process.GetProcessById(pid)) if(!AssignProcessToJobObject(job,process.Handle)) throw new Win32Exception();
            jobs.Add(name,job); job=IntPtr.Zero;
        } finally {if(job!=IntPtr.Zero)CloseHandle(job);LocalFree(descriptor);}
    }
    public static void Hold(string name) {
        if(jobs.ContainsKey(name)) return;
        IntPtr job=OpenJobObject(4,false,name); // JOB_OBJECT_QUERY; retain until cleanup completes.
        if(job==IntPtr.Zero) throw new Win32Exception();
        jobs.Add(name,job);
    }
    public static Process[] Members(string name) {
        try {Hold(name);} catch(Win32Exception error) {
            // A named job cannot be destroyed until both its handles and its
            // associated processes are gone. This occurs on a later recovery
            // invocation after the original cleanup processes have exited.
            if(error.NativeErrorCode==2) return new Process[0];
            throw;
        }
        IntPtr job=jobs[name];
        int capacity=128;
        for(;;) {
            int bytes=8+capacity*IntPtr.Size; IntPtr memory=Marshal.AllocHGlobal(bytes);
            try {
                uint returned;
                if(!QueryInformationJobObject(job,3,memory,(uint)bytes,out returned)) {
                    int error=Marshal.GetLastWin32Error();
                    if(error==234 && capacity<131072) {capacity*=2;continue;}
                    throw new Win32Exception(error);
                }
                int count=Marshal.ReadInt32(memory,4);
                var result=new List<Process>();
                for(int i=0;i<count;i++) {
                    long pid=Marshal.ReadIntPtr(memory,8+i*IntPtr.Size).ToInt64();
                    Process process=null;
                    try {
                        process=Process.GetProcessById((int)pid);
                        IntPtr handle=process.Handle; // Hold this incarnation, not only its PID.
                        bool member;
                        if(!IsProcessInJob(handle,job,out member)) throw new Win32Exception();
                        if(member && !process.HasExited) {var created=process.StartTime;result.Add(process);process=null;}
                    } catch(ArgumentException) {} catch(InvalidOperationException) {}
                    finally {if(process!=null)process.Dispose();}
                }
                return result.ToArray();
            } finally {Marshal.FreeHGlobal(memory);}
        }
    }
    public static bool Contains(string name,Process process) {
        Hold(name); bool member;
        if(!IsProcessInJob(process.Handle,jobs[name],out member)) throw new Win32Exception();
        return member && !process.HasExited;
    }
    public static void Release() {foreach(var handle in jobs.Values)CloseHandle(handle);jobs.Clear();}
}
'@
}

if (-not ('DepositSuiteJournalFiles' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;
public static class DepositSuiteJournalFiles {
    static SecurityIdentifier CurrentSid() { return WindowsIdentity.GetCurrent().User; }
    static FileSecurity OwnerOnlySecurity() {
        var sid=CurrentSid();
        var security=new FileSecurity();
        security.SetOwner(sid);
        security.SetAccessRuleProtection(true,false);
        security.AddAccessRule(new FileSystemAccessRule(sid,FileSystemRights.FullControl,AccessControlType.Allow));
        return security;
    }
    public static void WriteNew(string path,byte[] contents) {
        using(var stream=new FileStream(path,FileMode.CreateNew,FileSystemRights.FullControl,FileShare.None,4096,FileOptions.None,OwnerOnlySecurity())) {
            Validate(path);
            stream.Write(contents,0,contents.Length);
            stream.Flush(true);
        }
        Validate(path);
    }
    public static void SetOwnerOnly(string path) {
        File.SetAccessControl(path,OwnerOnlySecurity());
        Validate(path);
    }
    public static void Validate(string path) {
        var sid=CurrentSid();
        var security=File.GetAccessControl(path);
        if(!security.AreAccessRulesProtected || !sid.Equals(security.GetOwner(typeof(SecurityIdentifier))))
            throw new IOException("Integration journal must have a protected ACL owned by the invoking user");
        var rules=security.GetAccessRules(true,true,typeof(SecurityIdentifier));
        if(rules.Count!=1) throw new IOException("Integration journal ACL must contain only the invoking user's FullControl rule");
        var rule=(FileSystemAccessRule)rules[0];
        if(!sid.Equals(rule.IdentityReference) || rule.AccessControlType!=AccessControlType.Allow || rule.FileSystemRights!=FileSystemRights.FullControl || rule.IsInherited)
            throw new IOException("Integration journal ACL must contain only the invoking user's FullControl rule");
    }
}
'@
}

function Write-SuiteJournal([string]$Path,[string]$Content) {
    Assert-HarnessPlainPath $Path
    $encoding=New-Object Text.UTF8Encoding($false)
    [DepositSuiteJournalFiles]::WriteNew($Path,$encoding.GetBytes($Content))
}

function Test-SuiteSharingViolation([Exception]$Exception) {
    while ($Exception) {
        if (($Exception.HResult -band 0xffff) -in @(32,33)) { return $true }
        $Exception = $Exception.InnerException
    }
    return $false
}
function Test-SuiteJournalOpenRace([Exception]$Exception) {
    while ($Exception) {
        if ($Exception -is [IO.FileNotFoundException]) { return $true }
        if (($Exception.HResult -band 0xffff) -in @(32,33)) { return $true }
        $Exception = $Exception.InnerException
    }
    return $false
}
function Read-SuiteJournal([string]$Path) {
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    $stream = $null
    do {
        try {
            Assert-HarnessPlainPath $Path
            $share = [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
            $stream = [IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,$share)
            break
        } catch {
            if (!(Test-SuiteJournalOpenRace $_.Exception) -or [DateTime]::UtcNow -ge $deadline) { throw }
            Start-Sleep -Milliseconds 50
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    if (!$stream) { throw 'Could not open the integration suite journal for reading' }
    try {
        $reader = New-Object IO.StreamReader($stream,[Text.Encoding]::UTF8,$true)
        try { $json = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { if ($stream) { $stream.Dispose() } }
    return $json | ConvertFrom-Json
}
function Update-SuiteJournal([string]$Path,[scriptblock]$Change) {
    Assert-HarnessPlainPath $Path
    $lockPath = $Path+'.write.lock'
    Assert-HarnessPlainPath $lockPath
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    $journalLock = $null
    do { try { $journalLock = [IO.File]::Open($lockPath,'OpenOrCreate','ReadWrite','None') } catch [IO.IOException] { Start-Sleep -Milliseconds 100 } } until ($journalLock -or [DateTime]::UtcNow -gt $deadline)
    if (!$journalLock) { throw 'Could not lock the integration suite journal' }
    try {
        # A journal is authorization for process and service cleanup. Never
        # let an update turn an untrusted or inherited-ACL file into authority.
        [DepositSuiteJournalFiles]::Validate($Path)
        $journal = Read-SuiteJournal $Path
        & $Change $journal
        $temporary = $Path+'.'+[Guid]::NewGuid().ToString('N')+'.partial'
        try {
            Write-SuiteJournal $temporary ($journal | ConvertTo-Json -Depth 12)
            $replaceDeadline = [DateTime]::UtcNow.AddSeconds(5)
            do {
                try {
                    [IO.File]::Replace($temporary,$Path,[System.Management.Automation.Language.NullString]::Value)
                    break
                } catch {
                    if (!(Test-SuiteSharingViolation $_.Exception) -or [DateTime]::UtcNow -ge $replaceDeadline) { throw }
                    Start-Sleep -Milliseconds 50
                }
            } while ([DateTime]::UtcNow -lt $replaceDeadline)
            [DepositSuiteJournalFiles]::SetOwnerOnly($Path)
        } finally {
            if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
        }
    } finally { $journalLock.Dispose() }
}
function Get-SuiteProcess([long]$Id,[long]$StartTicks) {
    try {
        $process = Get-Process -Id $Id -ErrorAction Stop
        if ($process.StartTime.ToUniversalTime().Ticks -eq $StartTicks) { return $process }
    } catch {}
    return $null
}
function Stop-SuiteChildTree($Identity,[switch]$PreserveRuntime,[DateTime]$Deadline=[DateTime]::MinValue,[switch]$RejectRuntime) {
    if($Identity.PSObject.Properties['jobName'] -and $Identity.jobName){
        if($Deadline -eq [DateTime]::MinValue){$Deadline=[DateTime]::UtcNow.AddSeconds(30)}
        do{
            $members=@([DepositSuiteJobs]::Members([string]$Identity.jobName))
            $remaining=0
            try{
            foreach($process in $members){
                try{
                    if($process.HasExited){continue}
                    $created=$process.StartTime.ToUniversalTime().Ticks
                    if($RejectRuntime){
                        if($process.ProcessName -in @('java','prunsrv-amd64')){throw 'Local retirement refuses a contained runtime process'}
                        $metadata=Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)" -ErrorAction Stop
                        if((!$metadata -or !$metadata.CommandLine -or !$metadata.CreationDate) -and !$process.HasExited){throw 'Local retirement cannot verify a contained process'}
                    }
                    if($PreserveRuntime){
                        $record=Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
                        $runtimeHome=Join-Path (Get-HarnessRoot) 'runs\test-host\neo4j'
                        if($record -and $record.CommandLine -and $record.CommandLine.Contains($runtimeHome) -and $record.Name -in @('java.exe','prunsrv-amd64.exe')){continue}
                    }
                    if([DepositSuiteJobs]::Contains([string]$Identity.jobName,$process) -and $process.StartTime.ToUniversalTime().Ticks -eq $created){
                        $wait=[Math]::Min(5000,[int]($Deadline-[DateTime]::UtcNow).TotalMilliseconds)
                        if($wait -le 0){throw 'Contained scenario cleanup deadline expired'}
                        $remaining++;$process.Kill();if(!$process.WaitForExit($wait)){throw 'Contained scenario process did not exit'}
                    }
                }catch [InvalidOperationException]{
                    # A held incarnation can exit while its metadata is read.
                    # Only proven exit is benign; unknown live state still fails.
                    if(!$process.HasExited){throw}
                }catch [ComponentModel.Win32Exception]{
                    if(!$process.HasExited){throw}
                }
            }
            }finally{foreach($process in $members){$process.Dispose()}}
        }while($remaining -and [DateTime]::UtcNow -lt $Deadline)
        if($remaining){throw 'Contained scenario processes remain after bounded cleanup'}
        return
    }
    $parentProcess = Get-SuiteProcess ([long]$Identity.pid) ([long]$Identity.startTicks)
    if (!$parentProcess) { return }
    # Snapshot each incarnation before terminating descendants. Recheck creation time
    # at termination, so an operating-system PID reuse cannot target another process.
    $all = @(Get-CimInstance Win32_Process)
    $tree = New-Object 'System.Collections.Generic.List[object]'
    $tree.Add([pscustomobject]@{pid=[long]$Identity.pid;startTicks=[long]$Identity.startTicks})
    for ($index=0; $index -lt $tree.Count; $index++) {
        foreach ($child in @($all | Where-Object ParentProcessId -eq $tree[$index].pid)) {
            if ($child.CreationDate.ToUniversalTime().Ticks -ge $tree[$index].startTicks) {
                $incarnation=Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
                if($incarnation){$tree.Add([pscustomobject]@{pid=[long]$child.ProcessId;startTicks=[long]$incarnation.StartTime.ToUniversalTime().Ticks})}
            }
        }
    }
    for ($index=$tree.Count-1; $index -ge 0; $index--) {
        $process = Get-SuiteProcess $tree[$index].pid $tree[$index].startTicks
        if($process -and $PreserveRuntime){
            $record=Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
            $runtimeHome=Join-Path (Get-HarnessRoot) 'runs\test-host\neo4j'
            if($record.CommandLine -and $record.CommandLine.Contains($runtimeHome) -and $record.Name -in @('java.exe','prunsrv-amd64.exe')){continue}
        }
        if ($process) { $process.Kill(); if (!$process.WaitForExit(30000)) { throw "Owned suite child $($process.Id) did not exit" } }
    }
}
function Invoke-SuiteLocalRetirement([string]$Path,[string]$ExpectedInvocation) {
    # The local JVM has stopped, but exited scenario roots may leave launchers
    # holding diagnostic files. Retire their exact jobs before any service work.
    Assert-HarnessPlainPath $Path
    [DepositSuiteJournalFiles]::Validate($Path)
    $journal=Read-SuiteJournal $Path
    $runtimeHome=[IO.Path]::GetFullPath((Join-Path (Get-HarnessRoot) 'runs\test-host\neo4j')).TrimEnd('\')
    $runner=Get-Process -Id $PID -ErrorAction Stop
    try {
        if($ExpectedInvocation -cnotmatch '^[a-f0-9]{32}$' -or $journal.invocationId -cne $ExpectedInvocation -or
            $journal.schemaVersion -ne 1 -or $journal.ownerSid -cne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value -or
            [long]$journal.runnerPid -ne $PID -or [long]$journal.runnerStartTicks -ne $runner.StartTime.ToUniversalTime().Ticks -or
            [IO.Path]::GetFullPath([string]$journal.neo4jHome).TrimEnd('\') -ine $runtimeHome -or
            $journal.state -cne 'running' -or $journal.terminalCleanup -isnot [bool] -or $journal.terminalCleanup -or
            $journal.runtimeOwned -isnot [bool] -or !$journal.runtimeOwned -or !$journal.watchdogAcknowledged -or
            $journal.installationPhase -cne 'none' -or $journal.serviceName -or $journal.serviceAccount) {
            throw 'Local retirement requires the active nonterminal invocation owned by this runner'
        }
    } finally {$runner.Dispose()}
    Assert-HarnessPlainPath $runtimeHome
    foreach($runtime in @($journal.runtimeJobs)){
        if($runtime.state -cne 'retired' -or !$runtime.jobAcknowledged -or $runtime.jobName -notmatch ('^Local\\DepositRuntime-'+[regex]::Escape($ExpectedInvocation)+'-[a-f0-9]{32}$')){throw 'Local retirement requires every runtime generation to be retired'}
        $members=@([DepositSuiteJobs]::Members([string]$runtime.jobName))
        try{if($members.Count){throw 'Local retirement found a live runtime containment generation'}}finally{foreach($member in $members){$member.Dispose()}}
    }
    $installationPath=Join-Path (Split-Path $runtimeHome -Parent) 'distribution\deposit.install.json'
    Assert-HarnessPlainPath $installationPath
    $installation=Get-Content -LiteralPath $installationPath -Raw | ConvertFrom-Json
    if($installation.mode -cne 'local' -or [IO.Path]::GetFullPath([string]$installation.neo4jHome).TrimEnd('\') -ine $runtimeHome -or
        $installation.serviceName -cnotmatch '^DepositNeo4j-[a-f0-9]{8}$') {throw 'Local retirement installation identity is invalid'}
    if(Get-CimInstance Win32_Service -Filter "Name='$($installation.serviceName)'" -ErrorAction Stop){throw 'Local retirement refuses a registered service'}

    $children=@($journal.children)
    if(!$children.Count){throw 'Local retirement has no completed scenario jobs'}
    $names=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    # Validate the entire set before terminating any process. A reused root PID
    # is harmless; only a still-live root of the recorded incarnation blocks us.
    foreach($child in $children){
        if(!$child.PSObject.Properties['jobName'] -or !$child.PSObject.Properties['jobAcknowledged'] -or
            $child.jobName -cnotmatch ('^Local\\DepositIT-'+[regex]::Escape($ExpectedInvocation)+'-[a-f0-9]{32}$') -or
            $child.jobAcknowledged -isnot [bool] -or !$child.jobAcknowledged -or !$names.Add([string]$child.jobName) -or
            [long]$child.pid -le 0 -or [long]$child.startTicks -lt [long]$journal.runnerStartTicks){throw 'Local retirement found an invalid scenario job identity'}
        [DepositSuiteJobs]::Hold([string]$child.jobName)
        $rootProcess=$null
        try {
            try {$rootProcess=[Diagnostics.Process]::GetProcessById([int]$child.pid)} catch [ArgumentException] {}
            if($rootProcess -and !$rootProcess.HasExited -and $rootProcess.StartTime.ToUniversalTime().Ticks -eq [long]$child.startTicks){throw 'Local retirement refuses a live scenario root'}
        } catch [InvalidOperationException] {
            if(!$rootProcess -or !$rootProcess.HasExited){throw}
        } finally {if($rootProcess){$rootProcess.Dispose()}}
    }
    foreach($recordPath in @((Join-Path (Split-Path $runtimeHome -Parent) 'process.json'),(Join-Path $runtimeHome 'deposit-process.json'))){
        Assert-HarnessPlainPath $recordPath
        if(Test-Path -LiteralPath $recordPath){
            $record=Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
            if([long]$record.pid -le 0){throw 'Local runtime record has an invalid PID'}
            $recordStart=if($record.PSObject.Properties['startMillis']){[DateTimeOffset]::FromUnixTimeMilliseconds([long]$record.startMillis).UtcDateTime.Ticks}else{[DateTimeOffset]::Parse([string]$record.created,[Globalization.CultureInfo]::InvariantCulture).UtcDateTime.Ticks}
            $recordProcess=$null
            try {
                try {$recordProcess=[Diagnostics.Process]::GetProcessById([int]$record.pid)} catch [ArgumentException] {}
                if($recordProcess -and !$recordProcess.HasExited -and [Math]::Abs($recordProcess.StartTime.ToUniversalTime().Ticks-$recordStart) -lt [TimeSpan]::TicksPerMillisecond){throw 'Local retirement refuses a live recorded runtime'}
            } catch [InvalidOperationException] {
                if(!$recordProcess -or !$recordProcess.HasExited){throw}
            } finally {if($recordProcess){$recordProcess.Dispose()}}
        }
    }
    $runtimeProcesses=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='prunsrv-amd64.exe'" -ErrorAction Stop | Where-Object {$_.CommandLine -and $_.CommandLine.IndexOf($runtimeHome,[StringComparison]::OrdinalIgnoreCase) -ge 0})
    if($runtimeProcesses.Count){throw 'Local retirement requires the local runtime to be stopped'}
    foreach($child in $children){
        $members=@([DepositSuiteJobs]::Members([string]$child.jobName))
        try {
            foreach($member in $members){
                try {
                if($member.HasExited){continue}
                if($member.ProcessName -in @('java','prunsrv-amd64')){throw 'Local retirement refuses a contained runtime process'}
                $metadata=Get-CimInstance Win32_Process -Filter "ProcessId=$($member.Id)" -ErrorAction Stop
                if((!$metadata -or !$metadata.CommandLine -or !$metadata.CreationDate) -and !$member.HasExited){throw 'Local retirement cannot verify a contained process'}
                } catch [InvalidOperationException] {if(!$member.HasExited){throw}}
            }
        } finally {foreach($member in $members){$member.Dispose()}}
    }
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    Start-Sleep -Milliseconds 1000
    foreach($child in $children){Stop-SuiteChildTree $child -PreserveRuntime -Deadline $deadline -RejectRuntime}
    foreach($child in $children){
        $members=@([DepositSuiteJobs]::Members([string]$child.jobName))
        try {if($members.Count){throw 'Local retirement left contained processes; service installation is forbidden'}}finally{foreach($member in $members){$member.Dispose()}}
    }
    # Keep journal entries and both runner/watchdog job handles for recovery.
}
function Hold-SuiteJobs([string]$Path,[string]$Invocation){
    $journal=Read-SuiteJournal $Path
    if($journal.invocationId -cne $Invocation){return}
    foreach($entry in @($journal.children)){
        if($entry.PSObject.Properties['jobName'] -and $entry.jobName){
            if($entry.jobName -notmatch ('^Local\\DepositIT-'+[regex]::Escape($Invocation)+'-[a-f0-9]{32}$')){throw 'Containment job does not belong to this invocation'}
            [DepositSuiteJobs]::Hold([string]$entry.jobName)
            if(!$entry.jobAcknowledged){
                $jobName=[string]$entry.jobName
                Update-SuiteJournal $Path {param($j) if($j.invocationId -cne $Invocation){throw 'Suite invocation changed during job acknowledgement'};foreach($child in @($j.children)){if($child.jobName -ceq $jobName){$child.jobAcknowledged=$true}}}
            }
        }
    }
    foreach($entry in @($journal.runtimeJobs)){
        if($entry.PSObject.Properties['jobName'] -and $entry.jobName){
            if($entry.jobName -notmatch ('^Local\\DepositRuntime-'+[regex]::Escape($Invocation)+'-[a-f0-9]{32}$')){throw 'Runtime containment job does not belong to this invocation'}
            [DepositSuiteJobs]::Hold([string]$entry.jobName)
            if(!$entry.jobAcknowledged){
                $jobName=[string]$entry.jobName
                Update-SuiteJournal $Path {param($j) if($j.invocationId -cne $Invocation){throw 'Suite invocation changed during runtime job acknowledgement'};foreach($runtime in @($j.runtimeJobs)){if($runtime.jobName -ceq $jobName){$runtime.jobAcknowledged=$true}}}
            }
        }
    }
}
function Request-SuiteScStop($Journal,[string]$Name){
    $child=Start-Process -FilePath "$env:SystemRoot\System32\sc.exe" -ArgumentList @('stop',$Name) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $Journal.logs 'cleanup-scm-stop.stdout.log') -RedirectStandardError (Join-Path $Journal.logs 'cleanup-scm-stop.stderr.log')
    try{
        # Retain this instance's handle so Windows PowerShell 5.1 preserves ExitCode.
        $null=$child.Handle
        if(!$child.WaitForExit(10000)){Stop-SuiteChildTree ([pscustomobject]@{pid=$child.Id;startTicks=$child.StartTime.ToUniversalTime().Ticks}) -PreserveRuntime;throw 'SCM stop request timed out'}
        $child.Refresh();if($child.ExitCode -ne 0){throw "SCM stop request failed ($($child.ExitCode))"}
    }finally{$child.Dispose()}
}
function Assert-SuiteService($Journal,$Service) {
    $runtimeHome = [IO.Path]::GetFullPath([string]$Journal.neo4jHome).TrimEnd('\')
    $expected = [IO.Path]::GetFullPath((Join-Path (Get-HarnessRoot) 'runs\test-host\neo4j')).TrimEnd('\')
    Assert-HarnessPlainPath $runtimeHome
    if ($runtimeHome -ine $expected -or $Journal.ownerSid -cne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) { throw 'Suite ownership identity mismatch' }
    if($Journal.serviceName -notmatch '^DepositNeo4j-[a-f0-9]{8}$'){throw 'Unexpected service name in suite journal'}
    $partial=$Journal.installationPhase -eq 'installing' -and $Service.State -eq 'Stopped' -and $Service.StartName -in @('LocalSystem','NT AUTHORITY\SYSTEM') -and $Journal.allowedInitialServiceAccount -eq 'LocalSystem'
    if ($Service.Name -cne $Journal.serviceName -or (!$partial -and $Service.StartName -ine $Journal.serviceAccount) -or $Journal.serviceAccount -ine ('NT SERVICE\'+$Journal.serviceName)) { throw 'Service account or name differs from the owned suite journal' }
    $binary = [string]$Service.PathName
    $match = [regex]::Match($binary,'^\s*(?:"([^"]+)"|(\S+))\s+(.+?)\s*$')
    $executable = if ($match.Groups[1].Success) { $match.Groups[1].Value } else { $match.Groups[2].Value }
    $executable = [IO.Path]::GetFullPath($executable)
    if ($executable -ine (Join-Path $runtimeHome 'bin\tools\prunsrv-amd64.exe') -or $match.Groups[3].Value -cne ('//RS//'+$Journal.serviceName)) { throw 'Service command differs from the pinned owned test installation' }
    Assert-HarnessPlainPath $executable
}
function Assert-SuiteEnvironmentQuiescent($Journal) {
    $root=[IO.Path]::GetFullPath((Get-HarnessRoot)).TrimEnd('\')
    $runtimeHome=[IO.Path]::GetFullPath([string]$Journal.neo4jHome).TrimEnd('\')
    $services=@(Get-CimInstance Win32_Service -ErrorAction Stop|Where-Object {$_.PathName -and ([string]$_.PathName).IndexOf($root,[StringComparison]::OrdinalIgnoreCase) -ge 0})
    if($services.Count){throw ('A service remains registered under the harness: '+(($services|ForEach-Object Name)-join ', '))}
    $processes=@(Get-CimInstance Win32_Process -ErrorAction Stop|Where-Object {$_.CommandLine -and ([string]$_.CommandLine).IndexOf($runtimeHome,[StringComparison]::OrdinalIgnoreCase) -ge 0 -and $_.Name -in @('java.exe','prunsrv-amd64.exe','neo4j.exe')})
    if($processes.Count){throw ('A harness runtime process remains active: '+(($processes|ForEach-Object ProcessId)-join ', '))}
}
function Write-SuiteCleanupDiagnostic([string]$Path,[string]$Message){
    Update-SuiteJournal $Path {param($j) $j | Add-Member -NotePropertyName cleanupDiagnostics -NotePropertyValue (@($j.cleanupDiagnostics)+@([DateTime]::UtcNow.ToString('o')+' '+$Message)) -Force}
}
function Get-SuiteServiceSid([string]$Name){
    if($Name -cnotmatch '^DepositNeo4j-[a-f0-9]{8}$'){throw 'Unexpected service name while deriving its SID'}
    $sha=[Security.Cryptography.SHA1]::Create()
    try{$hash=$sha.ComputeHash([Text.Encoding]::Unicode.GetBytes($Name.ToUpperInvariant()));$parts=@(0,4,8,12,16|ForEach-Object {[BitConverter]::ToUInt32($hash,[int]$_)});return 'S-1-5-80-'+($parts -join '-')}finally{$sha.Dispose()}
}
function Assert-SuiteServiceAncestorPath([string]$Path,[string]$HarnessRoot){
    $harnessRoot=[IO.Path]::GetFullPath($HarnessRoot).TrimEnd('\')
    $depositRoot=[IO.Path]::GetFullPath((Split-Path $harnessRoot -Parent)).TrimEnd('\')
    $documentsRoot=[IO.Path]::GetFullPath((Split-Path $depositRoot -Parent)).TrimEnd('\')
    $expected=@($documentsRoot,$depositRoot,$harnessRoot,[IO.Path]::GetFullPath((Join-Path $harnessRoot 'runs')).TrimEnd('\'),[IO.Path]::GetFullPath((Join-Path $harnessRoot 'runs\test-host')).TrimEnd('\'))
    $full=[IO.Path]::GetFullPath($Path).TrimEnd('\')
    if($full -cnotin $expected){throw 'Test-service traversal ACL target is outside the approved ancestor set'}
    $current=$full
    while($current){
        $attributes=$null
        try{$attributes=[IO.File]::GetAttributes($current)}catch [IO.FileNotFoundException]{}catch [IO.DirectoryNotFoundException]{}
        if($null -ne $attributes -and ($attributes -band [IO.FileAttributes]::ReparsePoint)){throw "Reparse point refused: $current"}
        $current=[IO.Path]::GetDirectoryName($current)
    }
}
function Remove-SuiteServiceAncestorAces($Journal,[string]$JournalPath,[string]$HarnessRoot=(Get-HarnessRoot)) {
    if(!$Journal.PSObject.Properties['serviceAncestorAces']){return}
    if(!@($Journal.serviceAncestorAces).Count){return}
    $harnessRoot=[IO.Path]::GetFullPath($HarnessRoot).TrimEnd('\')
    $depositRoot=[IO.Path]::GetFullPath((Split-Path $harnessRoot -Parent)).TrimEnd('\')
    $documentsRoot=[IO.Path]::GetFullPath((Split-Path $depositRoot -Parent)).TrimEnd('\')
    $expected=@($documentsRoot,$depositRoot,$harnessRoot,[IO.Path]::GetFullPath((Join-Path $harnessRoot 'runs')).TrimEnd('\'),[IO.Path]::GetFullPath((Join-Path $harnessRoot 'runs\test-host')).TrimEnd('\'))
    $rights=[Security.AccessControl.FileSystemRights]::ReadAttributes -bor [Security.AccessControl.FileSystemRights]::Traverse -bor [Security.AccessControl.FileSystemRights]::ListDirectory
    $effectiveRights=$rights -bor [Security.AccessControl.FileSystemRights]::Synchronize
    $allowedMasks=@([int]$rights,[int]$effectiveRights)
    if($Journal.serviceName -cnotmatch '^DepositNeo4j-[a-f0-9]{8}$' -or $Journal.serviceAccount -cne ('NT SERVICE\'+[string]$Journal.serviceName)){throw 'Service-ancestor ACL cleanup identity is invalid'}
    $records=@{}
    foreach($entry in @($Journal.serviceAncestorAces)) {
        if(!$entry.path -or !$entry.sid -or !$entry.serviceName -or $null -eq $entry.rights){throw 'Service-ancestor ACL journal has an incomplete ownership record'}
        $target=[IO.Path]::GetFullPath([string]$entry.path).TrimEnd('\')
        if($target -cnotin $expected -or $entry.serviceName -cne $Journal.serviceName -or $entry.sid -cnotmatch '^S-1-5-80-(\d+-){4}\d+$' -or [int]$entry.rights -ne [int]$rights -or $records.ContainsKey($target)){throw 'Service-ancestor ACL cleanup identity is invalid'}
        $records[$target]=[string]$entry.sid
    }
    if(!$records.Count){return}
    $recordSids=@($records.Values|Select-Object -Unique)
    if($recordSids.Count -ne 1){throw 'Service-ancestor ACL journal contains multiple service identities'}
    $serviceSid=Get-SuiteServiceSid ([string]$Journal.serviceName)
    if($serviceSid -cne $recordSids[0]){throw 'Service-ancestor ACL journal SID differs from the named service'}
    $registeredService=Get-CimInstance Win32_Service -Filter "Name='$($Journal.serviceName)'" -ErrorAction Stop
    if($registeredService){$resolvedSid=(New-Object Security.Principal.NTAccount('NT SERVICE\'+[string]$Journal.serviceName)).Translate([Security.Principal.SecurityIdentifier]).Value;if($resolvedSid -cne $serviceSid){throw 'Registered test service SID differs from its derived SID'}}
    foreach($target in $expected){
        Assert-SuiteServiceAncestorPath $target $harnessRoot
        if(!(Test-Path -LiteralPath $target -PathType Container)){throw 'Service-ancestor ACL cleanup target is missing'}
        $rules=@((Get-Acl -LiteralPath $target).GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])|Where-Object {$_.IdentityReference.Value -ceq [string]$recordSids[0]})
        foreach($ace in $rules){
            $sid=[string]$ace.IdentityReference.Value
            if(!$records.ContainsKey($target) -or $records[$target] -cne $sid -or $ace.IsInherited -or
                $ace.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or $ace.InheritanceFlags -ne [Security.AccessControl.InheritanceFlags]::None -or
                $ace.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None -or [int]$ace.FileSystemRights -notin $allowedMasks){throw 'Unowned or unexpected service ACE blocks traversal ACL cleanup'}
        }
    }
    # Validate the complete set before mutating any ACL. Remove leaf-to-root
    # because Windows may normalize matching child entries during parent writes.
    foreach($target in @($expected | Sort-Object Length -Descending)){
        if(!$records.ContainsKey($target)){continue}
        $sid=[string]$records[$target]
        $acl=Get-Acl -LiteralPath $target
        $sidObject=New-Object Security.Principal.SecurityIdentifier($sid)
        $rules=@($acl.GetAccessRules($true,$false,[Security.Principal.SecurityIdentifier])|Where-Object {$_.IdentityReference.Value -ceq $sid})
        foreach($ace in $rules){
            $rule=New-Object Security.AccessControl.FileSystemAccessRule($sidObject,$ace.FileSystemRights,[Security.AccessControl.InheritanceFlags]::None,[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow)
            $acl.RemoveAccessRuleSpecific($rule)
        }
        if($rules.Count){Set-Acl -LiteralPath $target -AclObject $acl}
    }
    foreach($target in $expected){
        $remaining=@((Get-Acl -LiteralPath $target).GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])|Where-Object {$_.IdentityReference.Value -ceq [string]$recordSids[0]})
        if($remaining.Count){throw 'Temporary test-service traversal ACL remains after cleanup'}
    }
    if(@($Journal.serviceAncestorAces).Count) { Update-SuiteJournal $JournalPath {param($j) $j.serviceAncestorAces=@()} }
}
function Invoke-SuiteCleanupLauncher($Journal,[string]$Operation){
    $launcher=Join-Path (Split-Path $Journal.neo4jHome -Parent) 'distribution\deposit.bat'
    Assert-HarnessPlainPath $launcher
    if(!(Test-Path -LiteralPath $launcher)){throw 'Owned cleanup launcher is unavailable'}
    # Cleanup already owns the lifecycle fence and must bypass normal active
    # run admission after terminalCleanup has closed child operations.
    $command='$env:DEPOSIT_SUITE_JOURNAL=$null; & '''+$launcher.Replace("'","''")+''' '+$Operation+'; exit $LASTEXITCODE'
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $child=Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand',$encoded) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $Journal.logs ('cleanup-'+$Operation+'.stdout.log')) -RedirectStandardError (Join-Path $Journal.logs ('cleanup-'+$Operation+'.stderr.log'))
    try{$null=$child.Handle;if(!$child.WaitForExit(330000)){Stop-SuiteChildTree ([pscustomobject]@{pid=$child.Id;startTicks=$child.StartTime.ToUniversalTime().Ticks});throw "Graceful $Operation timed out"};$child.Refresh();if($child.ExitCode -ne 0){throw "Graceful $Operation failed ($($child.ExitCode))"}}finally{$child.Dispose()}
}
function Invoke-SuiteCleanup([string]$Path,[string]$ExpectedInvocation) {
    Assert-HarnessPlainPath ($Path+'.cleanup.lock')
    $cleanupLock = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    do { try { $cleanupLock = [IO.File]::Open($Path+'.cleanup.lock','OpenOrCreate','ReadWrite','None') } catch [IO.IOException] { Start-Sleep -Milliseconds 200 } } until ($cleanupLock -or [DateTime]::UtcNow -gt $deadline)
    if (!$cleanupLock) { throw 'Suite cleanup is still locked by another process' }
    try {
        [DepositSuiteJournalFiles]::Validate($Path)
        $journal = Read-SuiteJournal $Path
        if($ExpectedInvocation -and $journal.invocationId -cne $ExpectedInvocation){return}
        if ($journal.state -eq 'cleaned') {
            try {Assert-SuiteEnvironmentQuiescent $journal;return}
            catch {$message=$_.Exception.Message;Update-SuiteJournal $Path {param($j)$j.state='cleanup-failed';$j.cleanupError=$message};throw}
        }
        # Installation and service start hold this same lock through their SCM
        # mutation. Terminal cleanup waits for them, closes admission, then
        # quiesces their contained children before service cleanup begins.
        Assert-HarnessPlainPath ($Path+'.lifecycle.lock')
        $lifecycleLock=[IO.File]::Open($Path+'.lifecycle.lock','OpenOrCreate','ReadWrite','None')
        try {
            [DepositSuiteJournalFiles]::Validate($Path)
            $journal=Read-SuiteJournal $Path
            if($ExpectedInvocation -and $journal.invocationId -cne $ExpectedInvocation){return}
            if($journal.state -eq 'cleaned'){
                try {Assert-SuiteEnvironmentQuiescent $journal;return}
                catch {$message=$_.Exception.Message;Update-SuiteJournal $Path {param($j)$j.state='cleanup-failed';$j.cleanupError=$message};throw}
            }
            Update-SuiteJournal $Path { param($j) $j.terminalCleanup=$true; $j.state='cleaning' }
        } finally {$lifecycleLock.Dispose()}
        try {
            foreach ($child in @($journal.children)) { Stop-SuiteChildTree $child -PreserveRuntime }
            if ($journal.serviceName) {
                $service = Get-CimInstance Win32_Service -Filter "Name='$($journal.serviceName)'"
                if ($service) {
                    Assert-SuiteService $journal $service
                    if ($service.State -ne 'Stopped') {
                        try{Invoke-SuiteCleanupLauncher $journal 'stop'}catch{Write-SuiteCleanupDiagnostic $Path ('Graceful service drain failed: '+$_.Exception.Message)}
                        $service=Get-CimInstance Win32_Service -Filter "Name='$($journal.serviceName)'"
                        Assert-SuiteService $journal $service
                        if($service.State -ne 'Stopped'){
                            Write-SuiteCleanupDiagnostic $Path 'Using forced fallback after bounded graceful service drain.'
                            $incarnation=Get-Process -Id $service.ProcessId -ErrorAction Stop
                            $identity=[pscustomobject]@{pid=$incarnation.Id;startTicks=$incarnation.StartTime.ToUniversalTime().Ticks}
                            try{Request-SuiteScStop $journal $service.Name}catch{Write-SuiteCleanupDiagnostic $Path $_.Exception.Message}
                            try{(Get-Service -Name $service.Name).WaitForStatus('Stopped',[TimeSpan]::FromSeconds(30))}catch{
                                $current=Get-CimInstance Win32_Service -Filter "Name='$($journal.serviceName)'"
                                Assert-SuiteService $journal $current
                                if($current.ProcessId -ne $identity.pid){throw 'Service incarnation changed during forced cleanup'}
                                Stop-SuiteChildTree $identity
                            }
                        }
                    }
                    $service=Get-CimInstance Win32_Service -Filter "Name='$($journal.serviceName)'"
                    if($service){Assert-SuiteService $journal $service;if($service.State -ne 'Stopped'){throw 'Service remained active before traversal ACL cleanup'}}
                    Remove-SuiteServiceAncestorAces $journal $Path
                    $service = Get-CimInstance Win32_Service -Filter "Name='$($journal.serviceName)'"
                    if ($service) {
                        Assert-SuiteService $journal $service
                        try{Invoke-SuiteCleanupLauncher $journal 'remove-service'}catch{Write-SuiteCleanupDiagnostic $Path ('Packaged uninstall failed: '+$_.Exception.Message)}
                        $service=Get-CimInstance Win32_Service -Filter "Name='$($journal.serviceName)'"
                        if($service){Assert-SuiteService $journal $service;& "$env:SystemRoot\System32\sc.exe" delete $service.Name | Out-Null;if($LASTEXITCODE -ne 0){throw 'Could not uninstall the owned test service'}}
                    }
                    $deadline = [DateTime]::UtcNow.AddSeconds(30)
                    do { $remaining = Get-Service -Name $journal.serviceName -ErrorAction SilentlyContinue; if ($remaining) { Start-Sleep -Milliseconds 200 } } while ($remaining -and [DateTime]::UtcNow -lt $deadline)
                    if ($remaining) { throw 'Test service remains registered after cleanup' }
                }
            }
            # The local launcher writes its own PID and creation identity.
            $record = Join-Path (Split-Path $journal.neo4jHome -Parent) 'process.json'
            if ($journal.runtimeOwned -and (Test-Path -LiteralPath $record)) {
                Assert-HarnessPlainPath $record
                $owned = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
                $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($owned.pid)"
                $expectedStart=if($owned.PSObject.Properties['startMillis']){[long]$owned.startMillis}elseif($owned.PSObject.Properties['created']){([DateTimeOffset]::Parse([string]$owned.created)).ToUnixTimeMilliseconds()}else{throw 'Owned local process record has no creation identity'}
                if ($process -and $process.CommandLine -and $process.CommandLine.Contains([string]$journal.neo4jHome) -and ([DateTimeOffset]$process.CreationDate).ToUnixTimeMilliseconds() -eq $expectedStart) {
                    $incarnation=Get-Process -Id $owned.pid -ErrorAction Stop
                    $identity=[pscustomobject]@{pid=$owned.pid;startTicks=$incarnation.StartTime.ToUniversalTime().Ticks}
                    try{Invoke-SuiteCleanupLauncher $journal 'stop'}catch{Write-SuiteCleanupDiagnostic $Path ('Graceful local stop failed: '+$_.Exception.Message)}
                    if(Get-SuiteProcess $identity.pid $identity.startTicks){Write-SuiteCleanupDiagnostic $Path 'Using exact-process forced local cleanup after graceful stop.';Stop-SuiteChildTree $identity}
                }
            }
            foreach($runtime in @($journal.runtimeJobs)){
                if(!$runtime.jobName -or $runtime.jobName -notmatch ('^Local\\DepositRuntime-'+[regex]::Escape([string]$journal.invocationId)+'-[a-f0-9]{32}$') -or
                    $runtime.jobAcknowledged -isnot [bool] -or !$runtime.jobAcknowledged -or
                    [long]$runtime.pid -le 0 -or [long]$runtime.startTicks -le 0 -or
                    $runtime.generation -cnotmatch '^[a-f0-9]{32}$') {throw 'Local runtime cleanup has an invalid containment identity'}
                if($runtime.state -cne 'retired'){
                    Write-SuiteCleanupDiagnostic $Path ('Retiring local runtime containment generation '+$runtime.generation)
                    Stop-SuiteChildTree $runtime -Deadline ([DateTime]::UtcNow.AddSeconds(30))
                    Update-SuiteJournal $Path {param($j)if($j.invocationId -cne $journal.invocationId){throw 'Run owner changed during local runtime cleanup'};foreach($entry in @($j.runtimeJobs)){if($entry.generation -ceq $runtime.generation){$entry.state='retired';$entry|Add-Member -NotePropertyName retiredUtc -NotePropertyValue ([DateTime]::UtcNow.ToString('o')) -Force}}}
                }
                $runtimeMembers=@([DepositSuiteJobs]::Members([string]$runtime.jobName))
                try{if($runtimeMembers.Count){throw 'Local runtime containment generation remains active after cleanup'}}finally{foreach($member in $runtimeMembers){$member.Dispose()}}
            }
            $left = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('java.exe','prunsrv-amd64.exe') -and $_.CommandLine -and $_.CommandLine.Contains([string]$journal.neo4jHome) })
            if($journal.runtimeOwned -and $left.Count){
                # Startup can fail before its process.json is published. This
                # invocation created the fresh runtime; bind fallback to its
                # pinned JVM, current owner SID and post-invocation creation time.
                $manifest=Get-Content -LiteralPath (Join-Path (Get-DepositServerRoot) 'runtime\artifacts.json') -Raw|ConvertFrom-Json
                $java=[IO.Path]::GetFullPath((Join-Path (Join-Path (Get-HarnessRoot) $manifest.javaHome) 'bin\java.exe'))
                foreach($candidate in $left){
                    if($candidate.Name -ine 'java.exe' -or $candidate.ExecutablePath -ine $java -or $candidate.CommandLine -notmatch 'org\.neo4j\.server\.(Neo4jCommunity|CommunityEntryPoint)|Neo4jBoot'){continue}
                    $owner=Invoke-CimMethod -InputObject $candidate -MethodName GetOwnerSid
                    if($owner.ReturnValue -ne 0 -or $owner.Sid -cne $journal.ownerSid -or $candidate.CreationDate.ToUniversalTime().Ticks -lt [long]$journal.runnerStartTicks){continue}
                    $incarnation=Get-Process -Id $candidate.ProcessId -ErrorAction Stop
                    $identity=[pscustomobject]@{pid=$candidate.ProcessId;startTicks=$incarnation.StartTime.ToUniversalTime().Ticks}
                    try{Invoke-SuiteCleanupLauncher $journal 'stop'}catch{Write-SuiteCleanupDiagnostic $Path ('Early startup graceful stop failed: '+$_.Exception.Message)}
                    if(Get-SuiteProcess $identity.pid $identity.startTicks){Write-SuiteCleanupDiagnostic $Path 'Force-stopping verified owned JVM whose startup did not publish a process record.';Stop-SuiteChildTree $identity}
                }
                $left=@(Get-CimInstance Win32_Process|Where-Object{$_.Name -in @('java.exe','prunsrv-amd64.exe') -and $_.CommandLine -and $_.CommandLine.Contains([string]$journal.neo4jHome)})
            }
            if ($journal.runtimeOwned -and $left.Count) { throw 'An owned runtime process remains; retaining recovery journal' }
            foreach($child in @($journal.children)){
                if($child.PSObject.Properties['jobName'] -and $child.jobName){
                    Stop-SuiteChildTree $child -PreserveRuntime
                    $members=@([DepositSuiteJobs]::Members([string]$child.jobName))
                    try{if($members.Count){throw 'A contained process remains after runtime cleanup'}}finally{foreach($member in $members){$member.Dispose()}}
                }
            }
            Remove-SuiteServiceAncestorAces $journal $Path
            Assert-SuiteEnvironmentQuiescent $journal
            Update-SuiteJournal $Path { param($j) $j.state='cleaned'; $j.cleanupError=$null; $j.cleanedUtc=[DateTime]::UtcNow.ToString('o') }
        } catch {
            $message = $_.Exception.Message
            Update-SuiteJournal $Path { param($j) $j.state='cleanup-failed'; $j.cleanupError=$message }
            throw
        }
    } finally { $cleanupLock.Dispose() }
}

if ($LibraryOnly) { return }
if (!$JournalPath) { throw '-JournalPath is required' }
if ($CleanupOnly) { Invoke-SuiteCleanup $JournalPath; exit 0 }
$initial = Read-SuiteJournal $JournalPath
Update-SuiteJournal $JournalPath { param($j) $j.watchdogAcknowledged=$true; $j.watchdogPid=$PID; $j.watchdogStartTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks }
while (Get-SuiteProcess ([long]$initial.runnerPid) ([long]$initial.runnerStartTicks)) {
    Hold-SuiteJobs $JournalPath ([string]$initial.invocationId)
    $journal = Read-SuiteJournal $JournalPath
    if($journal.invocationId -cne $initial.invocationId){exit 0}
    if ($journal.state -eq 'cleaned') { exit 0 }
    Start-Sleep -Milliseconds 500
}
try { Invoke-SuiteCleanup $JournalPath ([string]$initial.invocationId) } catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 } finally {[DepositSuiteJobs]::Release()}
