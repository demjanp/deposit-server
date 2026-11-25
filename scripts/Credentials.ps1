Set-StrictMode -Version Latest
if (-not ('DepositCredentialStore' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class DepositCredentialStore {
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    private struct Credential {
        public uint Flags, Type; public string TargetName, Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public uint CredentialBlobSize; public IntPtr CredentialBlob;
        public uint Persist, AttributeCount; public IntPtr Attributes;
        public string TargetAlias, UserName;
    }
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern bool CredWrite(ref Credential c, uint flags);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern bool CredRead(string target, uint type, uint flags, out IntPtr result);
    [DllImport("advapi32.dll")] private static extern void CredFree(IntPtr buffer);
    public static void Save(string target, string password) {
        var ptr=Marshal.StringToCoTaskMemUni(password);
        try {
            var c=new Credential {Type=1, TargetName=target, UserName="neo4j", Persist=2,
                CredentialBlobSize=(uint)(password.Length*2), CredentialBlob=ptr};
            if(!CredWrite(ref c,0)) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        } finally {Marshal.ZeroFreeCoTaskMemUnicode(ptr);}
    }
    public static string Read(string target) {
        IntPtr ptr;
        if(!CredRead(target,1,0,out ptr)) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        try {var c=(Credential)Marshal.PtrToStructure(ptr,typeof(Credential));
             return Marshal.PtrToStringUni(c.CredentialBlob,(int)c.CredentialBlobSize/2);}
        finally {CredFree(ptr);}
    }
}
'@
}

function Set-OwnerOnlyDirectory([string]$Path) {
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($sid)
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Set-InitialNeo4jPassword([string]$Neo4jHome, [string]$JavaHome, [securestring]$Password, [string]$PristineNeo4jHome) {
    $neo4jAdmin = Join-Path $Neo4jHome 'bin\neo4j-admin.bat'
    $java = Join-Path $JavaHome 'bin\java.exe'
    $runtime = if ($PristineNeo4jHome) { $PristineNeo4jHome } else { $Neo4jHome }
    if (!(Test-Path -LiteralPath (Join-Path $runtime 'bin\neo4j-admin.bat') -PathType Leaf) -or
        !(Test-Path -LiteralPath $java -PathType Leaf)) {
        throw 'The selected Neo4j or Java installation is missing its launcher.'
    }

    $previousJava = $env:JAVA_HOME
    $previousNeo4j = $env:NEO4J_HOME
    $previousConfig = $env:NEO4J_CONF
    $pointer = [IntPtr]::Zero
    $privateDirectory = Join-Path ([IO.Path]::GetTempPath()) ('deposit-init-' + [guid]::NewGuid().ToString('N'))
    $argumentFile = Join-Path $privateDirectory 'password.args'
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
        $value = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        # Picocli interprets whitespace, quotes and comments in argument files.
        if ($value.Length -lt 8 -or $value -match '[\s"''#\\]') {
            throw 'Use at least 8 characters without whitespace, quotes, # or backslash.'
        }
        New-Item -ItemType Directory -Path $privateDirectory | Out-Null
        Set-OwnerOnlyDirectory $privateDirectory
        [IO.File]::WriteAllLines($argumentFile, @('dbms', 'set-initial-password', $value), [Text.UTF8Encoding]::new($false))
        if ($PristineNeo4jHome) {
            Copy-Item -LiteralPath $PristineNeo4jHome -Destination $Neo4jHome -Recurse
        }
        $env:JAVA_HOME = $JavaHome
        $env:NEO4J_HOME = $Neo4jHome
        $env:NEO4J_CONF = Join-Path $Neo4jHome 'conf'
        & $neo4jAdmin "@$argumentFile"
        if ($LASTEXITCODE -ne 0) { throw 'Setting the initial Neo4j administrator password failed.' }
    } finally {
        Remove-Item -LiteralPath $argumentFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $privateDirectory -ErrorAction SilentlyContinue
        $value = $null
        if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        $env:JAVA_HOME = $previousJava
        $env:NEO4J_HOME = $previousNeo4j
        $env:NEO4J_CONF = $previousConfig
    }
}

function Set-DepositServiceState($State,[string]$ServiceAccount) {
    $State.mode='service'
    $State | Add-Member -MemberType NoteProperty -Name serviceAccount -Value $ServiceAccount -Force
}

if(-not('DepositServiceAccount' -as [type])){
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class DepositServiceAccount {
    [DllImport("advapi32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr OpenSCManager(string machine,string database,uint access);
    [DllImport("advapi32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr OpenService(IntPtr manager,string name,uint access);
    [DllImport("advapi32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool ChangeServiceConfig(IntPtr service,uint serviceType,uint startType,uint errorControl,string binaryPath,string loadOrderGroup,IntPtr tag,string dependencies,string account,IntPtr password,string displayName);
    [DllImport("advapi32.dll",SetLastError=true)] static extern bool CloseServiceHandle(IntPtr handle);
    public static bool Set(string serviceName,string account,string password) {
        IntPtr manager=OpenSCManager(null,null,0x0001); if(manager==IntPtr.Zero)return false;
        try { IntPtr service=OpenService(manager,serviceName,0x0002); if(service==IntPtr.Zero)return false;
            try { IntPtr secret=String.IsNullOrEmpty(password)?IntPtr.Zero:Marshal.StringToHGlobalUni(password); try { return ChangeServiceConfig(service,0xffffffff,0xffffffff,0xffffffff,null,null,IntPtr.Zero,null,account,secret,null); } finally { if(secret!=IntPtr.Zero)Marshal.ZeroFreeGlobalAllocUnicode(secret); } }
            finally { CloseServiceHandle(service); }
        } finally { CloseServiceHandle(manager); }
    }
}
'@
}
