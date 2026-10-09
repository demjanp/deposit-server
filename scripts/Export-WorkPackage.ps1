[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Review','RunnerSmoke')][string]$Section,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Name
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$plan=Join-Path $repo '..\deposit-hub\implementation_plan.md'
$heading=if($Section -eq 'Review') {'Stage 0 read-only review work package'} else {'Stage 0 runner interruption fixture'}
$text=Get-Content -LiteralPath $plan -Raw
$match=[regex]::Match($text,'(?ms)^## '+[regex]::Escape($heading)+'\r?\n(?<body>.*?)(?=^## |\z)')
if(!$match.Success) {throw "Implementation plan has no work-package section: $heading"}
$directory=Join-Path 'C:\data_processed\Deposit_v2\stage0\runner\work-packages' $Name
New-Item -ItemType Directory -Force $directory | Out-Null
$path=Join-Path $directory ($Section+'.md')
[IO.File]::WriteAllText($path,('# '+$heading+"`n`n"+$match.Groups['body'].Value.Trim()+"`n"),(New-Object Text.UTF8Encoding($false)))
Write-Output $path
