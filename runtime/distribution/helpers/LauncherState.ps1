function Read-State([string]$StatePath) {
    if(!(Test-Path -LiteralPath $StatePath -PathType Leaf)){throw 'Deposit is not configured. Run deposit.bat setup <neo4j-home> <java-home> <data-root> <export-root>.'}
    $state=Get-Content -LiteralPath $StatePath -Raw | ConvertFrom-Json -ErrorAction Stop
    foreach($key in @('neo4jHome','javaHome','dataRoot','exportRoot','serviceName','mode')) {if(!$state.$key){throw "Install configuration is missing $key"}}
    $state.neo4jHome=[IO.Path]::GetFullPath($state.neo4jHome);$state.javaHome=[IO.Path]::GetFullPath($state.javaHome)
    $state.dataRoot=[IO.Path]::GetFullPath($state.dataRoot);$state.exportRoot=[IO.Path]::GetFullPath($state.exportRoot)
    if(!$state.secretRoot){$state.secretRoot=Join-Path $state.neo4jHome 'deposit-secrets'};$state.secretRoot=[IO.Path]::GetFullPath($state.secretRoot)
    return $state
}
function Save-State([string]$StatePath,$State) {
    $tmp=$StatePath+'.'+[guid]::NewGuid().ToString('N')+'.partial'
    $backup=$StatePath+'.'+[guid]::NewGuid().ToString('N')+'.backup'
    try {
        [IO.File]::WriteAllText($tmp,($State|ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
        if(Test-Path -LiteralPath $StatePath){[IO.File]::Replace($tmp,$StatePath,$backup);Remove-Item -LiteralPath $backup -Force}else{[IO.File]::Move($tmp,$StatePath)}
    } finally {Remove-Item -LiteralPath $tmp,$backup -Force -ErrorAction SilentlyContinue}
}
