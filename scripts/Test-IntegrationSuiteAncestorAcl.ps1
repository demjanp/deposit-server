$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
. (Join-Path $PSScriptRoot 'IntegrationSuiteWatchdog.ps1') -LibraryOnly

$harnessRoot=Get-HarnessRoot
$testParent=[IO.Path]::GetFullPath((Join-Path $harnessRoot 'logs'))
$fixtureBase=Join-Path $testParent ('ancestor-acl-'+[guid]::NewGuid().ToString('N'))
$fixtureRoot=Join-Path $fixtureBase 'Deposit_v2\test_harness'
$serviceName='TrustedInstaller'
$serviceAccount='NT SERVICE\'+$serviceName
$service=Get-CimInstance Win32_Service -Filter "Name='$serviceName'" -ErrorAction Stop
if(!$service){throw 'Windows TrustedInstaller service is required for the ACL fixture'}
$serviceSid=(New-Object Security.Principal.NTAccount($serviceAccount)).Translate([Security.Principal.SecurityIdentifier])
$rights=[Security.AccessControl.FileSystemRights]::ReadAttributes -bor [Security.AccessControl.FileSystemRights]::Traverse -bor [Security.AccessControl.FileSystemRights]::ListDirectory
$expectedRights=161
if([int]$rights -ne $expectedRights){throw "Unexpected temporary service ACL mask: $([int]$rights)"}
$effectiveRights=$rights -bor [Security.AccessControl.FileSystemRights]::Synchronize
if([int]$effectiveRights -ne 1048737){throw "Unexpected effective temporary service ACL mask: $([int]$effectiveRights)"}
$script:root=$fixtureRoot
$script:journalPath=Join-Path $fixtureRoot 'active.json'
$script:aclTestJournal=$null

function Read-SuiteJournal([string]$Path) { return $script:aclTestJournal }
function Update-SuiteJournal([string]$Path,[scriptblock]$Change) { & $Change $script:aclTestJournal }
function Assert-Rule([string]$Path,[string]$Sid,[bool]$Present) {
    $acl=Get-Acl -LiteralPath $Path
    $rules=@($acl.GetAccessRules($true,$false,[Security.Principal.SecurityIdentifier])|Where-Object {$_.IdentityReference.Value -ceq $Sid})
    if($Present) {
        $exact=@($rules|Where-Object {$_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and [int]$_.FileSystemRights -in @([int]$rights,[int]$effectiveRights) -and $_.InheritanceFlags -eq [Security.AccessControl.InheritanceFlags]::None -and $_.PropagationFlags -eq [Security.AccessControl.PropagationFlags]::None})
        if(!$exact.Count){$details=$rules|ForEach-Object {"$($_.AccessControlType)|$([int]$_.FileSystemRights)|$($_.InheritanceFlags)|$($_.PropagationFlags)"};throw "Expected exact non-inheriting service ACE is absent: $Path; observed=$($details -join ',')"}
    } elseif($rules.Count){throw "Service ACE remains: $Path"}
}
function New-Journal {
    return [pscustomobject]@{serviceName=$serviceName;serviceAccount=$serviceAccount;installationPhase='configured';serviceAncestorAces=@()}
}
function Add-ServiceAce([string]$Path,[Security.AccessControl.FileSystemRights]$Mask=$rights,[Security.Principal.SecurityIdentifier]$Identity=$serviceSid) {
    $acl=Get-Acl -LiteralPath $Path
    $rule=New-Object Security.AccessControl.FileSystemAccessRule($Identity,$Mask,[Security.AccessControl.InheritanceFlags]::None,[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow)
    $acl.AddAccessRule($rule);Set-Acl -LiteralPath $Path -AclObject $acl
}

try {
    [void][IO.Directory]::CreateDirectory((Join-Path $fixtureRoot 'runs\test-host'))
    $runs=Join-Path $fixtureRoot 'runs';$hostDir=Join-Path $runs 'test-host'
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Invoke-IntegrationSuite.ps1'),[ref]$null,[ref]$null)
    $grantAst=$ast.Find({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Grant-TestServiceAncestorAccess'},$true)
    if(!$grantAst){throw 'Could not locate the production service ACL grant helper'}
    Invoke-Expression $grantAst.Extent.Text

    $targets=@($fixtureBase,(Join-Path $fixtureBase 'Deposit_v2'),$fixtureRoot,$runs,$hostDir)
    # A pre-existing service ACE at any allowed target rejects before changing any target.
    foreach($target in $targets){
        Add-ServiceAce $target
        $before=@($targets|ForEach-Object { $acl=Get-Acl $_; "$_|"+(@($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])|ForEach-Object {"$($_.IdentityReference.Value)|$($_.AccessControlType)|$($_.FileSystemRights)|$($_.IsInherited)|$($_.InheritanceFlags)|$($_.PropagationFlags)"}|Sort-Object)-join ';') })
        $script:aclTestJournal=New-Journal
        try {Grant-TestServiceAncestorAccess $serviceName $serviceAccount;throw 'Grant accepted an unowned pre-existing service ACE'}catch {if($_.Exception.Message -eq 'Grant accepted an unowned pre-existing service ACE'){throw}}
        $after=@($targets|ForEach-Object { $acl=Get-Acl $_; "$_|"+(@($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])|ForEach-Object {"$($_.IdentityReference.Value)|$($_.AccessControlType)|$($_.FileSystemRights)|$($_.IsInherited)|$($_.InheritanceFlags)|$($_.PropagationFlags)"}|Sort-Object)-join ';') })
        if(Compare-Object $before $after){throw 'Rejected pre-existing ACE caused an ACL mutation'}
        $acl=Get-Acl $target;$sidObject=$serviceSid;$rule=New-Object Security.AccessControl.FileSystemAccessRule($sidObject,$rights,[Security.AccessControl.InheritanceFlags]::None,[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow);$acl.RemoveAccessRuleSpecific($rule);Set-Acl -LiteralPath $target -AclObject $acl
    }
    $script:aclTestJournal=New-Journal
    Grant-TestServiceAncestorAccess $serviceName $serviceAccount
    if($script:aclTestJournal.serviceAncestorAces.Count -ne 5){throw 'Grant helper did not journal all five owned ACEs'}
    Write-Output "grant-recorded=$($script:aclTestJournal.serviceAncestorAces.Count)"
    Write-Output ('grant-paths='+(($script:aclTestJournal.serviceAncestorAces|ForEach-Object path)-join '|'))
    foreach($target in $targets){Assert-Rule $target $serviceSid.Value $true}
    # Idempotent grant preserves the journal ownership set.
    Grant-TestServiceAncestorAccess $serviceName $serviceAccount
    if($script:aclTestJournal.serviceAncestorAces.Count -ne 5){throw 'Repeated grant duplicated journal ownership'}
    $cleanupName='DepositNeo4j-1234abcd';$cleanupSid=Get-SuiteServiceSid $cleanupName;$cleanupIdentity=New-Object Security.Principal.SecurityIdentifier($cleanupSid)
    foreach($target in @($targets|Sort-Object Length -Descending)){$acl=Get-Acl $target;$rule=New-Object Security.AccessControl.FileSystemAccessRule($serviceSid,$rights,[Security.AccessControl.InheritanceFlags]::None,[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow);$acl.RemoveAccessRuleSpecific($rule);Set-Acl -LiteralPath $target -AclObject $acl}
    $script:aclTestJournal=New-Journal;$script:aclTestJournal.serviceName=$cleanupName;$script:aclTestJournal.serviceAccount='NT SERVICE\'+$cleanupName
    $script:aclTestJournal.serviceAncestorAces=@(foreach($target in $targets){Add-ServiceAce $target $rights $cleanupIdentity;[pscustomobject]@{path=$target;sid=$cleanupSid;rights=[int]$rights;serviceName=$cleanupName}})
    Remove-SuiteServiceAncestorAces $script:aclTestJournal $script:journalPath $fixtureRoot
    Write-Output 'cleanup-complete'
    if($script:aclTestJournal.serviceAncestorAces.Count){throw 'Cleanup did not clear owned journal entries'}
    foreach($target in $targets){Assert-Rule $target $cleanupSid $false}
    Remove-SuiteServiceAncestorAces $script:aclTestJournal $script:journalPath $fixtureRoot

    # Recover intent-only and partially applied grants at each allowed depth.
    for($applied=0;$applied -le $targets.Count;$applied++){
        $script:aclTestJournal=New-Journal;$script:aclTestJournal.serviceName=$cleanupName;$script:aclTestJournal.serviceAccount='NT SERVICE\'+$cleanupName
        $script:aclTestJournal.serviceAncestorAces=@(foreach($target in $targets){[pscustomobject]@{path=$target;sid=$cleanupSid;rights=[int]$rights;serviceName=$cleanupName}})
        for($index=0;$index -lt $applied;$index++){Add-ServiceAce $targets[$index] $rights $cleanupIdentity}
        Remove-SuiteServiceAncestorAces $script:aclTestJournal $script:journalPath $fixtureRoot
        foreach($target in $targets){Assert-Rule $target $cleanupSid $false}
    }

    # A journaled grant that never reached the filesystem is safe to clean repeatedly.
    $script:aclTestJournal=New-Journal;$script:aclTestJournal.serviceName=$cleanupName;$script:aclTestJournal.serviceAccount='NT SERVICE\'+$cleanupName
    $script:aclTestJournal.serviceAncestorAces=@([pscustomobject]@{path=$hostDir;sid=$cleanupSid;rights=[int]$rights;serviceName=$cleanupName})
    Remove-SuiteServiceAncestorAces $script:aclTestJournal $script:journalPath $fixtureRoot
    Remove-SuiteServiceAncestorAces $script:aclTestJournal $script:journalPath $fixtureRoot
    Assert-Rule $hostDir $cleanupSid $false

    foreach($invalid in @(
        [pscustomobject]@{path=(Join-Path $testParent 'outside-fixture');sid=$serviceSid.Value;rights=[int]$rights;serviceName=$serviceName},
        [pscustomobject]@{path=$hostDir;sid='S-1-5-80-1-2-3-4';rights=[int]$rights;serviceName=$serviceName},
        [pscustomobject]@{path=$hostDir;sid=$cleanupSid;rights=([int]$rights -bor [int][Security.AccessControl.FileSystemRights]::WriteData);serviceName=$serviceName})) {
        $script:aclTestJournal=New-Journal;$script:aclTestJournal.serviceName=$cleanupName;$script:aclTestJournal.serviceAccount='NT SERVICE\'+$cleanupName;$invalid.serviceName=$cleanupName;$script:aclTestJournal.serviceAncestorAces=@($invalid)
        try {Remove-SuiteServiceAncestorAces $script:aclTestJournal $script:journalPath $fixtureRoot;throw 'Cleanup accepted an invalid path, SID or rights mask'}catch {if($_.Exception.Message -eq 'Cleanup accepted an invalid path, SID or rights mask'){throw}}
    }

    # An unjournaled same-SID ACE on a child blocks cleanup before a journaled parent is changed.
    $script:aclTestJournal=New-Journal;$script:aclTestJournal.serviceName=$cleanupName;$script:aclTestJournal.serviceAccount='NT SERVICE\'+$cleanupName
    $script:aclTestJournal.serviceAncestorAces=@([pscustomobject]@{path=$fixtureRoot;sid=$cleanupSid;rights=[int]$rights;serviceName=$cleanupName})
    Add-ServiceAce $fixtureRoot $rights $cleanupIdentity;Add-ServiceAce $hostDir $rights $cleanupIdentity
    try {Remove-SuiteServiceAncestorAces $script:aclTestJournal $script:journalPath $fixtureRoot;throw 'Cleanup removed a parent before rejecting an unowned child ACE'}catch {if($_.Exception.Message -eq 'Cleanup removed a parent before rejecting an unowned child ACE'){throw}}
    Assert-Rule $fixtureRoot $cleanupSid $true
    $acl=Get-Acl $hostDir;$rule=New-Object Security.AccessControl.FileSystemAccessRule($cleanupIdentity,$rights,[Security.AccessControl.InheritanceFlags]::None,[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow);$acl.RemoveAccessRuleSpecific($rule);Set-Acl -LiteralPath $hostDir -AclObject $acl
    Remove-SuiteServiceAncestorAces $script:aclTestJournal $script:journalPath $fixtureRoot
    Assert-Rule $fixtureRoot $cleanupSid $false

    # A well-formed SID for another service is rejected without changing the target ACL.
    $script:aclTestJournal=New-Journal;$script:aclTestJournal.serviceName=$cleanupName;$script:aclTestJournal.serviceAccount='NT SERVICE\'+$cleanupName
    $script:aclTestJournal.serviceAncestorAces=@([pscustomobject]@{path=$hostDir;sid=$serviceSid.Value;rights=[int]$rights;serviceName=$cleanupName})
    Add-ServiceAce $hostDir
    $beforeMismatch=Get-Acl $hostDir | ForEach-Object Sddl
    try {Remove-SuiteServiceAncestorAces $script:aclTestJournal $script:journalPath $fixtureRoot;throw 'Cleanup accepted another service SID'}catch {if($_.Exception.Message -eq 'Cleanup accepted another service SID'){throw}}
    if((Get-Acl $hostDir).Sddl -cne $beforeMismatch){throw 'Mismatched service SID rejection changed the ACL'}

    $script:aclTestJournal=New-Journal;$script:aclTestJournal.serviceName=$cleanupName;$script:aclTestJournal.serviceAccount='NT SERVICE\'+$cleanupName
    $script:aclTestJournal.serviceAncestorAces=@([pscustomobject]@{path=$hostDir;sid=$cleanupSid;rights=[int]$rights;serviceName=$cleanupName})
    Add-ServiceAce $hostDir ([Security.AccessControl.FileSystemRights]::ReadAttributes -bor [Security.AccessControl.FileSystemRights]::WriteData) $cleanupIdentity
    $beforeUnexpected= (Get-Acl $hostDir).Sddl
    try {Remove-SuiteServiceAncestorAces $script:aclTestJournal $script:journalPath $fixtureRoot;throw 'Cleanup accepted an unexpected service ACE'}catch {if($_.Exception.Message -eq 'Cleanup accepted an unexpected service ACE'){throw}}
    if((Get-Acl $hostDir).Sddl -cne $beforeUnexpected){throw 'Unexpected service ACE rejection changed the ACL'}
    $acl=Get-Acl $hostDir;$oldRights=[Security.AccessControl.FileSystemRights]::ReadAttributes -bor [Security.AccessControl.FileSystemRights]::WriteData;$rule=New-Object Security.AccessControl.FileSystemAccessRule($cleanupIdentity,$oldRights,[Security.AccessControl.InheritanceFlags]::None,[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow);$acl.RemoveAccessRuleSpecific($rule);Set-Acl -LiteralPath $hostDir -AclObject $acl
    Add-ServiceAce $hostDir ([Security.AccessControl.FileSystemRights]::ReadAttributes -bor [Security.AccessControl.FileSystemRights]::Traverse) $cleanupIdentity
    $beforeNarrowMask=(Get-Acl $hostDir).Sddl
    try {Remove-SuiteServiceAncestorAces $script:aclTestJournal $script:journalPath $fixtureRoot;throw 'Cleanup accepted a service ACE missing ListDirectory'}catch {if($_.Exception.Message -eq 'Cleanup accepted a service ACE missing ListDirectory'){throw}}
    if((Get-Acl $hostDir).Sddl -cne $beforeNarrowMask){throw 'Missing-ListDirectory rejection changed the ACL'}
    Write-Output 'PASS: complete ACL preflight, name-bound SID cleanup, journal intent, idempotent grants, partial recovery, unowned ACE protection, and invalid identity/path/rights rejection'
} finally {
    $absolute=[IO.Path]::GetFullPath($fixtureRoot)
    if(!$absolute.StartsWith($testParent+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'ACL fixture cleanup escaped test_harness\logs'}
    if([IO.Directory]::Exists([IO.Path]::GetFullPath($fixtureBase))){[IO.Directory]::Delete([IO.Path]::GetFullPath($fixtureBase),$true)}
}
