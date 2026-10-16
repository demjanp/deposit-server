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

function Test-SuiteSharingViolation([Exception]$Exception) {
    while ($Exception) {
        if (($Exception.HResult -band 0xffff) -in @(32,33)) { return $true }
        $Exception = $Exception.InnerException
    }
    return $false
}
function Read-SuiteJournal([string]$Path) {
    Assert-HarnessPlainPath $Path
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    $stream = $null
    do {
        try {
            $share = [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
            $stream = [IO.File]::Open($Path,[IO.FileMode]::Open,[IO.FileAccess]::Read,$share)
            break
        } catch {
            if (!(Test-SuiteSharingViolation $_.Exception) -or [DateTime]::UtcNow -ge $deadline) { throw }
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
        $journal = Read-SuiteJournal $Path
        & $Change $journal
        $temporary = $Path+'.'+[Guid]::NewGuid().ToString('N')+'.partial'
        [IO.File]::WriteAllText($temporary,($journal | ConvertTo-Json -Depth 12),(New-Object Text.UTF8Encoding($false)))
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
    } finally { $journalLock.Dispose() }
}
function Get-SuiteProcess([long]$Id,[long]$StartTicks) {
    try {
        $process = Get-Process -Id $Id -ErrorAction Stop
        if ($process.StartTime.ToUniversalTime().Ticks -eq $StartTicks) { return $process }
    } catch {}
    return $null
}
function Stop-SuiteChildTree($Identity,[switch]$PreserveRuntime) {
    if($Identity.PSObject.Properties['jobName'] -and $Identity.jobName){
        $deadline=[DateTime]::UtcNow.AddSeconds(30)
        do{
            $members=@([DepositSuiteJobs]::Members([string]$Identity.jobName))
            $remaining=0
            foreach($process in $members){
                try{
                    $created=$process.StartTime.ToUniversalTime().Ticks
                    if($PreserveRuntime){
                        $record=Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
                        $runtimeHome=Join-Path (Get-HarnessRoot) 'runs\stage0-host\neo4j'
                        if($record -and $record.CommandLine -and $record.CommandLine.Contains($runtimeHome) -and $record.Name -in @('java.exe','prunsrv-amd64.exe')){continue}
                    }
                    if([DepositSuiteJobs]::Contains([string]$Identity.jobName,$process) -and $process.StartTime.ToUniversalTime().Ticks -eq $created){
                        $remaining++;$process.Kill();if(!$process.WaitForExit(5000)){throw 'Contained scenario process did not exit'}
                    }
                }finally{$process.Dispose()}
            }
        }while($remaining -and [DateTime]::UtcNow -lt $deadline)
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
            $runtimeHome=Join-Path (Get-HarnessRoot) 'runs\stage0-host\neo4j'
            if($record.CommandLine -and $record.CommandLine.Contains($runtimeHome) -and $record.Name -in @('java.exe','prunsrv-amd64.exe')){continue}
        }
        if ($process) { $process.Kill(); if (!$process.WaitForExit(30000)) { throw "Owned suite child $($process.Id) did not exit" } }
    }
}
function Hold-SuiteJobs([string]$Path,[string]$Invocation){
    $journal=Read-SuiteJournal $Path
    if($journal.invocationId -cne $Invocation){return}
    foreach($child in @($journal.children)){
        if($child.PSObject.Properties['jobName'] -and $child.jobName){
            if($child.jobName -notmatch ('^Local\\DepositIT-'+[regex]::Escape($Invocation)+'-[a-f0-9]{32}$')){throw 'Containment job does not belong to this invocation'}
            [DepositSuiteJobs]::Hold([string]$child.jobName)
            if(!$child.jobAcknowledged){
                $jobName=[string]$child.jobName
                Update-SuiteJournal $Path {param($j) if($j.invocationId -cne $Invocation){throw 'Suite invocation changed during job acknowledgement'};foreach($entry in @($j.children)){if($entry.jobName -ceq $jobName){$entry.jobAcknowledged=$true}}}
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
    $expected = [IO.Path]::GetFullPath((Join-Path (Get-HarnessRoot) 'runs\stage0-host\neo4j')).TrimEnd('\')
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
function Write-SuiteCleanupDiagnostic([string]$Path,[string]$Message){
    Update-SuiteJournal $Path {param($j) $j | Add-Member -NotePropertyName cleanupDiagnostics -NotePropertyValue (@($j.cleanupDiagnostics)+@([DateTime]::UtcNow.ToString('o')+' '+$Message)) -Force}
}
function Invoke-SuiteCleanupLauncher($Journal,[string]$Operation){
    $launcher=Join-Path (Split-Path $Journal.neo4jHome -Parent) 'distribution\deposit.bat'
    Assert-HarnessPlainPath $launcher
    if(!(Test-Path -LiteralPath $launcher)){throw 'Owned cleanup launcher is unavailable'}
    $command='& '''+$launcher.Replace("'","''")+''' '+$Operation+'; exit $LASTEXITCODE'
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
        $journal = Read-SuiteJournal $Path
        if($ExpectedInvocation -and $journal.invocationId -cne $ExpectedInvocation){return}
        if ($journal.state -eq 'cleaned') { return }
        Update-SuiteJournal $Path { param($j) $j.terminalCleanup=$true; $j.state='cleaning' }
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
