param(
    [Parameter(Mandatory=$true)][string]$ConfigPath,
    [Parameter(Mandatory=$true)][string]$ResourceRoot,
    [Parameter(Mandatory=$true)][string]$ExportRoot,
    [string]$ListenAddress='127.0.0.1',
    [Parameter(Mandatory=$true)][string]$SecretRoot
)
$ErrorActionPreference='Stop'
$resolved=[IO.Path]::GetFullPath($ConfigPath)
$directory=Split-Path -Parent $resolved
if(!(Test-Path -LiteralPath $directory -PathType Container)){throw 'Configuration directory does not exist'}
if(!$ResourceRoot -or !$ExportRoot){throw 'Resource and export roots are required'}
$ip=[Net.IPAddress]::Parse($ListenAddress)
if(![Net.IPAddress]::IsLoopback($ip)){throw 'The automatic port allocator only supports loopback listeners'}
$resource=[IO.Path]::GetFullPath($ResourceRoot).Replace('\','/')
$exports=[IO.Path]::GetFullPath($ExportRoot).Replace('\','/')
if([string]::IsNullOrWhiteSpace($SecretRoot)){throw 'Secret root directory is required'}
$secrets=[IO.Path]::GetFullPath($SecretRoot).Replace('\','/')
if(!(Test-Path -LiteralPath $SecretRoot -PathType Container)){throw 'Secret root directory must already exist'}
$uriHost=if($ip.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetworkV6){"[$ListenAddress]"}else{$ListenAddress}
$existing=$null
if(Test-Path -LiteralPath $resolved -PathType Leaf){
    $existing=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach($line in [IO.File]::ReadAllLines($resolved)){
        if($line -match '^\s*([^#!\s][^=\s]*)\s*=\s*(.*?)\s*$'){$existing[$matches[1]]=$matches[2]}
    }
    $exampleConfig=$existing.ContainsKey('resourceRoot') -and $existing.ContainsKey('exportRoot') -and $existing['resourceRoot'] -eq 'C:/path/to/deposit-resources' -and $existing['exportRoot'] -eq 'C:/path/to/deposit-exports'
    if(!$exampleConfig -and $existing.ContainsKey('download.port') -and $existing.ContainsKey('download.publicBaseUrl')){
        if($existing['download.listenAddress'] -ne $ListenAddress -or $existing['resourceRoot'] -ne $resource -or $existing['exportRoot'] -ne $exports -or $existing['secretRoot'] -ne $secrets){throw 'Existing listener, storage or secret configuration differs; preserve the configured endpoint or remove the config deliberately'}
        $port=[int]$existing['download.port'];$public=$existing['download.publicBaseUrl']
        if($port -lt 1 -or $port -gt 65535 -or $public -ne "http://${uriHost}:$port"){throw 'Existing listener endpoint is invalid'}
        $probe=[Net.Sockets.TcpListener]::new($ip,$port)
        try{$probe.Start()}catch{throw "Configured download port $port is unavailable; stable endpoint was preserved"}finally{try{$probe.Stop()}catch{}}
        return
    }
}
$allocator=[Net.Sockets.TcpListener]::new($ip,0)
try{$allocator.Start();$port=([Net.IPEndPoint]$allocator.LocalEndpoint).Port}finally{$allocator.Stop()}
$lines=@("resourceRoot=$resource","exportRoot=$exports","secretRoot=$secrets")
$lines+="download.listenAddress=$ListenAddress","download.port=$port","download.publicBaseUrl=http://${uriHost}:$port"
$partial="$resolved.$([guid]::NewGuid().ToString('N')).partial"
[IO.File]::WriteAllLines($partial,$lines,[Text.UTF8Encoding]::new($false))
try{
    if(Test-Path -LiteralPath $resolved){$backup="$resolved.$([guid]::NewGuid().ToString('N')).backup";try{[IO.File]::Replace($partial,$resolved,$backup)}finally{if(Test-Path -LiteralPath $backup){Remove-Item -LiteralPath $backup -Force}}}else{[IO.File]::Move($partial,$resolved)}
}finally{if(Test-Path -LiteralPath $partial){Remove-Item -LiteralPath $partial -Force}}
Write-Output "Download listener configured at http://${uriHost}:$port"
