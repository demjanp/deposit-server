$script:DepositHarnessOwnerOnlyAclMethod=@'
static FileSecurity OwnerOnlyFileSecurity(SecurityIdentifier sid) {
    var acl = new FileSecurity();
    acl.SetOwner(sid);
    acl.SetAccessRuleProtection(true,false);
    acl.AddAccessRule(new FileSystemAccessRule(sid,FileSystemRights.FullControl,AccessControlType.Allow));
    return acl;
}
'@
