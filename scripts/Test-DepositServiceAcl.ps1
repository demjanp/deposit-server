$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'HarnessPaths.ps1')
$harnessRoot=Get-HarnessRoot
$env:TEMP=Join-Path $harnessRoot 'tmp';$env:TMP=$env:TEMP
$launcherHelper=Join-Path (Split-Path $PSScriptRoot -Parent) 'runtime\distribution\helpers\LauncherPathHelpers.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($launcherHelper,[ref]$null,[ref]$null)
$definition=$ast.Find({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-IcaclsPath'},$true)
Invoke-Expression $definition.Extent.Text
if((Get-IcaclsPath 'D:\test\data') -cne '\\?\D:\test\data'){throw 'Drive path conversion failed'}
if((Get-IcaclsPath '\\server\share\data') -cne '\\?\UNC\server\share\data'){throw 'UNC path conversion failed'}
if((Get-IcaclsPath '\\?\D:\test\data') -cne '\\?\D:\test\data'){throw 'Extended path was changed'}
$testRoot=[IO.Path]::GetFullPath((Join-Path $harnessRoot 'logs'))
$fixture=Join-Path $testRoot ('service-acl-'+[guid]::NewGuid().ToString('N'))
$directory=Join-Path (Join-Path $fixture ('a'*90)) ('b'*90)
$file=Join-Path $directory (('x'*120)+'-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
$account=[Security.Principal.WindowsIdentity]::GetCurrent().Name
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
try{
    [void][IO.Directory]::CreateDirectory((Get-IcaclsPath $directory))
    [IO.File]::WriteAllText((Get-IcaclsPath $file),'long-path ACL fixture')
    if($file.Length -le 260){throw 'Fixture does not exercise the Windows legacy path limit'}
    $ErrorActionPreference='Continue'
    & icacls.exe (Get-IcaclsPath $fixture) '/grant' "$account`:(OI)(CI)M" '/T' '/Q' | Out-Null
    $code=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    if($code -ne 0){throw "Recursive ACL application failed ($code)"}
    $acl=[IO.File]::GetAccessControl((Get-IcaclsPath $file))
    $rules=$acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])
    $grant=@($rules|Where-Object {$_.IdentityReference -eq $sid -and $_.AccessControlType -eq 'Allow' -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Modify) -eq [Security.AccessControl.FileSystemRights]::Modify})
    if(!$grant.Count){throw 'Long-path payload lacks the intended Modify grant'}
    Write-Output "PASS: drive/UNC/extended paths and recursive Modify grant on a $($file.Length)-character payload path"
}finally{
    $absolute=[IO.Path]::GetFullPath($fixture)
    if(!$absolute.StartsWith($testRoot+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Fixture cleanup escaped its test root'}
    if([IO.Directory]::Exists((Get-IcaclsPath $absolute))){[IO.Directory]::Delete((Get-IcaclsPath $absolute),$true)}
}
