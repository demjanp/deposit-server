param([Parameter(Mandatory=$true)][int]$TargetProcessId)
$ErrorActionPreference='Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class DepositConsoleSignal {
 [DllImport("kernel32.dll", SetLastError=true)] public static extern bool FreeConsole();
 [DllImport("kernel32.dll", SetLastError=true)] public static extern bool AttachConsole(uint processId);
 [DllImport("kernel32.dll", SetLastError=true)] public static extern bool SetConsoleCtrlHandler(IntPtr handler,bool add);
 [DllImport("kernel32.dll", SetLastError=true)] public static extern bool GenerateConsoleCtrlEvent(uint signal,uint group);
}
'@
[DepositConsoleSignal]::FreeConsole()|Out-Null
if(![DepositConsoleSignal]::AttachConsole([uint32]$TargetProcessId)){throw 'Cannot attach to the owned Neo4j console'}
try {[DepositConsoleSignal]::SetConsoleCtrlHandler([IntPtr]::Zero,$true)|Out-Null;if(![DepositConsoleSignal]::GenerateConsoleCtrlEvent(0,0)){throw 'Cannot signal the owned Neo4j console'}}finally{[DepositConsoleSignal]::FreeConsole()|Out-Null}
