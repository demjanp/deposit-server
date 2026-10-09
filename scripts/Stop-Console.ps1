param([Parameter(Mandatory=$true)][int]$TargetProcessId)
$ErrorActionPreference='Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class ConsoleSignal {
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool FreeConsole();
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool AttachConsole(uint processId);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool GenerateConsoleCtrlEvent(uint signal, uint group);
}
'@
[ConsoleSignal]::FreeConsole() | Out-Null
if (![ConsoleSignal]::AttachConsole($TargetProcessId)) {throw 'Cannot attach to the owned server console'}
try {
    [ConsoleSignal]::SetConsoleCtrlHandler([IntPtr]::Zero,$true) | Out-Null
    if (![ConsoleSignal]::GenerateConsoleCtrlEvent(0,0)) {throw 'Cannot signal the owned server console'}
    Start-Sleep -Milliseconds 500
} finally {[ConsoleSignal]::FreeConsole() | Out-Null}
