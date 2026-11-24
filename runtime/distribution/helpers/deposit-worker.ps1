param([Parameter(Mandatory=$true)][string]$Neo4jHome,[Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference='Stop'
$env:JAVA_HOME=[IO.Path]::GetFullPath($JavaHome)
$env:NEO4J_HOME=[IO.Path]::GetFullPath($Neo4jHome)
$env:NEO4J_CONF=Join-Path $env:NEO4J_HOME 'conf'
$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH

# A hidden worker can inherit a setting that ignores Ctrl+C. Clear it before starting Neo4j.
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class LocalConsole {
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
}
'@
if (![LocalConsole]::SetConsoleCtrlHandler([IntPtr]::Zero, $false)) {
    throw 'Could not enable Ctrl+C for the local Neo4j process'
}

& (Join-Path $env:NEO4J_HOME 'bin\neo4j.bat') console
exit $LASTEXITCODE
