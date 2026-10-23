# Harness-only secrets. The distribution's Credential Manager implementation is unchanged.
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
. (Join-Path $PSScriptRoot 'IntegrationHarnessAcl.ps1')

function Assert-HarnessPlainPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetFullPath((Get-HarnessRoot)).TrimEnd('\')
    if ($full -ine $root -and !$full.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)) { throw "Path is outside the harness: $full" }
    $current = $full
    while ($current) {
        # Probe once: a journal can be replaced between an existence check and
        # a provider lookup. Missing components are permitted, but their existing
        # ancestors still require validation and all other probe errors propagate.
        $attributes = $null
        try { $attributes = [IO.File]::GetAttributes($current) }
        catch [IO.FileNotFoundException] {}
        catch [IO.DirectoryNotFoundException] {}
        if ($null -ne $attributes -and ($attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Reparse point refused: $current" }
        $current = [IO.Path]::GetDirectoryName($current)
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
    # Set-Acl requests SACL privileges even though this helper only changes the
    # owner and DACL. Persist those same owner/DACL sections through the .NET
    # directory API so standard developer accounts can create the private root.
    [IO.Directory]::SetAccessControl($Path, $acl)
}

if (-not ('DepositHarnessSecretStore' -as [type])) {
    Add-Type -AssemblyName System.Security
    $secretStoreSource=@'
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
    static void ValidateRoot() {
        if (String.IsNullOrWhiteSpace(Root)) throw new InvalidOperationException("Harness secret root is unset");
        Plain(Root);
        if (!Directory.Exists(Root)) throw new IOException("Harness secret directory is missing");
        var sid = WindowsIdentity.GetCurrent().User;
        var acl = Directory.GetAccessControl(Root);
        if (!acl.AreAccessRulesProtected || !sid.Equals(acl.GetOwner(typeof(SecurityIdentifier)))) throw new IOException("Unsafe harness secret directory owner or inheritance");
        foreach (FileSystemAccessRule rule in acl.GetAccessRules(true,true,typeof(SecurityIdentifier)))
            if (rule.AccessControlType == AccessControlType.Allow && !sid.Equals(rule.IdentityReference)) throw new IOException("Harness secret directory grants another identity access");
    }
    static string FileName(string target) {
        ValidateRoot();
        var sid = WindowsIdentity.GetCurrent().User;
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
    /*DEPOSIT_HARNESS_ACL_METHOD*/
    static FileSecurity OwnerOnlyFileSecurity() {
        var sid = WindowsIdentity.GetCurrent().User;
        return OwnerOnlyFileSecurity(sid);
    }
    static void ValidateNewFile(string path) {
        Plain(path);
        var sid = WindowsIdentity.GetCurrent().User;
        var acl = File.GetAccessControl(path);
        if (!acl.AreAccessRulesProtected || !sid.Equals(acl.GetOwner(typeof(SecurityIdentifier)))) throw new IOException("Unsafe new secret file owner or inheritance");
        var rules = acl.GetAccessRules(true,true,typeof(SecurityIdentifier));
        if (rules.Count != 1) throw new IOException("Unexpected new secret file access rules");
        var rule = (FileSystemAccessRule)rules[0];
        if (!sid.Equals(rule.IdentityReference) || rule.AccessControlType != AccessControlType.Allow || rule.FileSystemRights != FileSystemRights.FullControl)
            throw new IOException("Unsafe new secret file access");
    }
    static void WriteOwnerOnlyFile(string path, byte[] bytes) {
        using (var stream = new FileStream(path,FileMode.CreateNew,FileSystemRights.FullControl,FileShare.None,4096,FileOptions.None,OwnerOnlyFileSecurity())) {
            ValidateNewFile(path);
            stream.Write(bytes,0,bytes.Length);
            stream.Flush(true);
        }
        ValidateNewFile(path);
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
            WriteOwnerOnlyFile(temporary,encrypted);
            if (File.Exists(path)) File.Replace(temporary,path,null); else File.Move(temporary,path);
            // ReplaceFile retains the previous destination DACL. The destination
            // has already passed FileName's strict owner/access validation.
            File.SetAccessControl(path,OwnerOnlyFileSecurity());
            ValidateNewFile(path);
        } finally { Array.Clear(clear,0,clear.Length); if (File.Exists(temporary)) File.Delete(temporary); }
    }
    public static void Delete(string target) { string path=FileName(target); if(File.Exists(path)) File.Delete(path); }
}
'@
    $secretStoreSource=$secretStoreSource.Replace('/*DEPOSIT_HARNESS_ACL_METHOD*/',$script:DepositHarnessOwnerOnlyAclMethod)
    Add-Type -ReferencedAssemblies System.Security -TypeDefinition $secretStoreSource
}
$secretRoot = Join-Path (Get-HarnessRoot) 'private\credentials'
Set-OwnerOnlyDirectory $secretRoot
[DepositHarnessSecretStore]::Root = $secretRoot
