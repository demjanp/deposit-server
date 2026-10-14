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
