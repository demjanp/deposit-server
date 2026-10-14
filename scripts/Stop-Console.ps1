param([Parameter(Mandatory=$true)][string]$IdentityBase64)
$ErrorActionPreference='Stop'
$identity=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($IdentityBase64))|ConvertFrom-Json -ErrorAction Stop
. (Join-Path $PSScriptRoot 'Request-DepositStop.ps1')
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
if (![ConsoleSignal]::AttachConsole([uint32]$identity.pid)) {throw 'Cannot attach to the owned server console'}
try {
    if (![ConsoleSignal]::SetConsoleCtrlHandler([IntPtr]::Zero,$true)) {throw 'Cannot suppress Ctrl+C in the signalling helper'}
    [void](Assert-DepositStopIdentity $identity)
    if (![ConsoleSignal]::GenerateConsoleCtrlEvent(0,0)) {throw 'Cannot signal the owned server console'}
    Start-Sleep -Milliseconds 500
} finally {
    [ConsoleSignal]::FreeConsole() | Out-Null
    # The ignore flag is inherited by child processes; do not leak it into a later server start.
    [ConsoleSignal]::SetConsoleCtrlHandler([IntPtr]::Zero,$false) | Out-Null
}
