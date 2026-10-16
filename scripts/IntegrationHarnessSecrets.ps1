# Harness-only secrets. The distribution's Credential Manager implementation is unchanged.
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')

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
    static FileSecurity OwnerOnlyFileSecurity() {
        var sid = WindowsIdentity.GetCurrent().User;
        var acl = new FileSecurity();
        acl.SetOwner(sid);
        acl.SetAccessRuleProtection(true,false);
        acl.AddAccessRule(new FileSystemAccessRule(sid,FileSystemRights.FullControl,AccessControlType.Allow));
        return acl;
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
    // This rotation is only for a fresh Stage0 setup after the suite has rejected
    // active runtimes and preserved the old generated installation. Never decrypt
    // or adopt the old blob: a same-volume move retains its owner and ACL.
    public static string QuarantineStage0ForFreshSetup(string invocation) {
        Guid id;
        if (invocation == null || invocation.Length != 32 || !Guid.TryParseExact(invocation,"N",out id)) throw new IOException("Invalid quarantine invocation");
        const string target = "Deposit/Stage0/stage0-host";
        // Validate the root through the ordinary path, without validating or
        // changing this one legacy file's owner.
        ValidateRoot();
        string path;
        using (var sha = SHA256.Create())
            path = Path.Combine(Root,BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(target))).Replace("-","").ToLowerInvariant()+".dpapi");
        Plain(path);
        if (Directory.Exists(path)) throw new IOException("Secret target is not a file");
        if (!File.Exists(path)) return null;
        var sid = WindowsIdentity.GetCurrent().User;
        var acl = File.GetAccessControl(path);
        var owner = (SecurityIdentifier)acl.GetOwner(typeof(SecurityIdentifier));
        if (!sid.Equals(owner) && !new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid,null).Equals(owner)) throw new IOException("Unexpected quarantined secret owner");
        var rules = acl.GetAccessRules(true,true,typeof(SecurityIdentifier));
        if (rules.Count == 0) throw new IOException("Quarantined secret grants no user access");
        foreach (FileSystemAccessRule rule in rules)
            if (!sid.Equals(rule.IdentityReference) || rule.AccessControlType != AccessControlType.Allow || rule.FileSystemRights != FileSystemRights.FullControl)
                throw new IOException("Unexpected quarantined secret access rules");
        string originalSecurity = acl.GetSecurityDescriptorSddlForm(AccessControlSections.Owner|AccessControlSections.Group|AccessControlSections.Access);
        string directory = Path.Combine(Root,"quarantine-"+invocation);
        Plain(directory);
        if (File.Exists(directory) || Directory.Exists(directory)) throw new IOException("Secret quarantine destination already exists");
        var directoryAcl = new DirectorySecurity();
        directoryAcl.SetOwner(sid);
        directoryAcl.SetAccessRuleProtection(true,false);
        directoryAcl.AddAccessRule(new FileSystemAccessRule(sid,FileSystemRights.FullControl,InheritanceFlags.ContainerInherit|InheritanceFlags.ObjectInherit,PropagationFlags.None,AccessControlType.Allow));
        Directory.CreateDirectory(directory,directoryAcl);
        var actualDirectoryAcl = Directory.GetAccessControl(directory);
        if (actualDirectoryAcl.GetSecurityDescriptorSddlForm(AccessControlSections.Owner|AccessControlSections.Access) != directoryAcl.GetSecurityDescriptorSddlForm(AccessControlSections.Owner|AccessControlSections.Access)) throw new IOException("Unsafe secret quarantine directory");
        string destination = Path.Combine(directory,Path.GetFileName(path));
        WriteOwnerOnlyFile(destination+".acl.txt",Encoding.UTF8.GetBytes("Target: "+target+"\r\nOriginal security descriptor: "+originalSecurity+"\r\n"));
        Plain(path);
        Plain(destination);
        if (File.GetAccessControl(path).GetSecurityDescriptorSddlForm(AccessControlSections.Owner|AccessControlSections.Group|AccessControlSections.Access) != originalSecurity) throw new IOException("Secret security changed before quarantine");
        File.Move(path,destination);
        Plain(destination);
        if (File.GetAccessControl(destination).GetSecurityDescriptorSddlForm(AccessControlSections.Owner|AccessControlSections.Group|AccessControlSections.Access) != originalSecurity) throw new IOException("Quarantined secret security was not preserved");
        return destination;
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
}
$secretRoot = Join-Path (Get-HarnessRoot) 'private\credentials'
Set-OwnerOnlyDirectory $secretRoot
[DepositHarnessSecretStore]::Root = $secretRoot
