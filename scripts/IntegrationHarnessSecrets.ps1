# Harness-only secrets. The distribution's Credential Manager implementation is unchanged.
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')

function Assert-HarnessPlainPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetFullPath((Get-HarnessRoot)).TrimEnd('\')
    if ($full -ine $root -and !$full.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)) { throw "Path is outside the harness: $full" }
    $current = $full
    while ($current) {
        if ((Test-Path -LiteralPath $current) -and ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Reparse point refused: $current" }
        $current = Split-Path $current -Parent
    }
}
function Set-OwnerOnlyDirectory([string]$Path) {
    Assert-HarnessPlainPath $Path
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetOwner($owner)
    $acl.SetAccessRuleProtection($true,$false)
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($owner,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))
    Set-Acl -LiteralPath $Path -AclObject $acl
}

if (-not ('DepositHarnessSecretStore' -as [type])) {
    Add-Type -AssemblyName System.Security
    Add-Type -ReferencedAssemblies System.Security -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Security.AccessControl;
public static class DepositHarnessSecretStore {
    public static string Root;
    static void Plain(string path) {
        for (string p = Path.GetFullPath(path); !String.IsNullOrEmpty(p); p = Path.GetDirectoryName(p))
            if ((File.Exists(p) || Directory.Exists(p)) && (File.GetAttributes(p) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("Secret path contains a reparse point");
    }
    static string FileName(string target) {
        if (String.IsNullOrWhiteSpace(Root)) throw new InvalidOperationException("Harness secret root is unset");
        Plain(Root);
        if (!Directory.Exists(Root)) throw new IOException("Harness secret directory is missing");
        var sid = WindowsIdentity.GetCurrent().User;
        var acl = Directory.GetAccessControl(Root);
        if (!acl.AreAccessRulesProtected || !sid.Equals(acl.GetOwner(typeof(SecurityIdentifier)))) throw new IOException("Unsafe harness secret directory owner or inheritance");
        foreach (FileSystemAccessRule rule in acl.GetAccessRules(true,true,typeof(SecurityIdentifier)))
            if (rule.AccessControlType == AccessControlType.Allow && !sid.Equals(rule.IdentityReference)) throw new IOException("Harness secret directory grants another identity access");
        using (var sha = SHA256.Create()) {
            var path = Path.Combine(Root,BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(target))).Replace("-","").ToLowerInvariant()+".dpapi");
            Plain(path);
            if (File.Exists(path)) {
                var fileAcl = File.GetAccessControl(path);
                foreach (FileSystemAccessRule rule in fileAcl.GetAccessRules(true,true,typeof(SecurityIdentifier)))
                    if (rule.AccessControlType == AccessControlType.Allow && !sid.Equals(rule.IdentityReference)) throw new IOException("Harness secret file grants another identity access");
                if (!sid.Equals(fileAcl.GetOwner(typeof(SecurityIdentifier)))) throw new IOException("Unsafe secret file owner");
            }
            return path;
        }
    }
    public static string Read(string target) {
        string path = FileName(target);
        if (!File.Exists(path)) throw new IOException("Harness secret is missing for " + target);
        byte[] clear = ProtectedData.Unprotect(File.ReadAllBytes(path),Encoding.UTF8.GetBytes(target),DataProtectionScope.CurrentUser);
        try { return Encoding.UTF8.GetString(clear); } finally { Array.Clear(clear,0,clear.Length); }
    }
    public static void Write(string target,string value) {
        string path = FileName(target), temporary = path + "." + Guid.NewGuid().ToString("N") + ".partial";
        byte[] clear = Encoding.UTF8.GetBytes(value);
        try {
            byte[] encrypted = ProtectedData.Protect(clear,Encoding.UTF8.GetBytes(target),DataProtectionScope.CurrentUser);
            using (var stream = new FileStream(temporary,FileMode.CreateNew,FileAccess.Write,FileShare.None)) { stream.Write(encrypted,0,encrypted.Length); stream.Flush(true); }
            if (File.Exists(path)) File.Replace(temporary,path,null); else File.Move(temporary,path);
        } finally { Array.Clear(clear,0,clear.Length); if (File.Exists(temporary)) File.Delete(temporary); }
    }
    public static void Delete(string target) { string path=FileName(target); if(File.Exists(path)) File.Delete(path); }
}
'@
}
$secretRoot = Join-Path (Get-HarnessRoot) 'private\credentials'
Set-OwnerOnlyDirectory $secretRoot
[DepositHarnessSecretStore]::Root = $secretRoot
