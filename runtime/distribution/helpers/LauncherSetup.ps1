function Setup([string[]]$Arguments,[string]$PackageRoot,[string]$StatePath,$Manifest) {
    . (Join-Path $PackageRoot 'helpers\Credentials.ps1')
    $configurator=Join-Path $PackageRoot 'helpers\Configure-DepositDownloads.ps1'
    if($Arguments.Count -notin @(4,5)){throw 'Usage: deposit.bat setup <neo4j-home> <java-home> <data-root> <export-root> [bolt-port]'}
    $neo4j=[IO.Path]::GetFullPath($Arguments[0]);$java=[IO.Path]::GetFullPath($Arguments[1]);$data=[IO.Path]::GetFullPath($Arguments[2]);$exports=[IO.Path]::GetFullPath($Arguments[3])
    $boltPort=7687;if($Arguments.Count -eq 5){if($Arguments[4] -notmatch '^\d{1,5}$'){throw 'Bolt port must be an integer in 1..65535'};$boltPort=[int]$Arguments[4]};if($boltPort -lt 1 -or $boltPort -gt 65535){throw 'Bolt port must be in 1..65535'}
    foreach($path in @((Join-Path $neo4j 'bin\neo4j.bat'),(Join-Path $neo4j 'bin\neo4j-admin.bat'),(Join-Path $java 'bin\java.exe'))){if(!(Test-Path -LiteralPath $path -PathType Leaf)){throw "Pinned runtime file not found: $path"}}
    $env:JAVA_HOME=$java;$env:NEO4J_HOME=$neo4j;$env:NEO4J_CONF=Join-Path $neo4j 'conf'
    $env:PATH=(Join-Path $java 'bin')+';'+$env:PATH
    $versionOutput=& (Join-Path $neo4j 'bin\neo4j-admin.bat') version 2>&1 | Out-String
    if($LASTEXITCODE -ne 0 -or $versionOutput -notmatch [regex]::Escape([string]$Manifest.neo4jVersion)){throw 'Selected Neo4j version does not match the package runtime manifest'}
    if(Test-Path -LiteralPath $StatePath){throw 'This distribution is already configured; use its recorded installation or a fresh package directory'}
    foreach($dir in @($data,$exports)){New-Item -ItemType Directory -Path $dir -Force|Out-Null}
    foreach($dir in @('staging','ocfl','ocfl-work')){New-Item -ItemType Directory -Path (Join-Path $data $dir) -Force|Out-Null}
    $pluginDir=Join-Path $neo4j 'plugins';New-Item -ItemType Directory -Path $pluginDir -Force|Out-Null
    $releaseJars=@(Get-ChildItem -LiteralPath (Join-Path $PackageRoot 'plugins') -Filter '*.jar' -File)
    if(!$releaseJars.Count){throw 'The release package has no plugin artifacts'}
    foreach($jar in $releaseJars){$target=Join-Path $pluginDir $jar.Name;if(Test-Path -LiteralPath $target){throw "Refusing to replace an existing plugin: $($jar.Name)"}}
    $conf=Join-Path $neo4j 'conf';New-Item -ItemType Directory -Path $conf -Force|Out-Null
    $neo4jConfig=Join-Path $conf 'neo4j.conf';$depositConfig=Join-Path $conf 'deposit.properties'
    if(!(Test-Path -LiteralPath $neo4jConfig)){Copy-Item -LiteralPath (Join-Path $PackageRoot 'conf\neo4j.conf') -Destination $neo4jConfig}
    if(Test-Path -LiteralPath $depositConfig){throw 'Refusing to replace existing conf/deposit.properties'}
    Copy-Item -LiteralPath (Join-Path $PackageRoot 'conf\deposit.properties') -Destination $depositConfig
    $secretRoot=Join-Path $neo4j 'deposit-secrets';New-Item -ItemType Directory -Path $secretRoot -Force|Out-Null;Set-OwnerOnlyDirectory $secretRoot
    & $configurator -ConfigPath $depositConfig -ResourceRoot $data -ExportRoot $exports -ListenAddress '127.0.0.1' -SecretRoot $secretRoot | Out-Null
    if($LASTEXITCODE -and $LASTEXITCODE -ne 0){throw 'Download configuration failed'}
    $depositConfigForward=$depositConfig.Replace('\','/')
    Set-Neo4jSetting $neo4jConfig 'server.default_listen_address' '127.0.0.1'
    Set-Neo4jSetting $neo4jConfig 'server.bolt.enabled' 'true'
    Set-Neo4jSetting $neo4jConfig 'server.bolt.listen_address' "127.0.0.1:$boltPort"
    Set-Neo4jSetting $neo4jConfig 'server.bolt.advertised_address' "127.0.0.1:$boltPort"
    Set-Neo4jSetting $neo4jConfig 'server.http.enabled' 'false'
    Set-Neo4jSetting $neo4jConfig 'server.https.enabled' 'false'
    Set-Neo4jSetting $neo4jConfig 'dbms.security.auth_enabled' 'true'
    Set-Neo4jSetting $neo4jConfig 'db.query.default_language' 'CYPHER_25'
    Set-Neo4jSetting $neo4jConfig 'dbms.security.procedures.allowlist' 'deposit.*,apoc.version'
    Set-Neo4jSetting $neo4jConfig 'server.jvm.additional' "-Ddeposit.config=$depositConfigForward"
    $serviceName='DepositNeo4j-'+([guid]::NewGuid().ToString('N').Substring(0,8))
    Set-Neo4jSetting $neo4jConfig 'server.windows_service_name' $serviceName
    $state=[ordered]@{schemaVersion=1;mode='local';neo4jHome=$neo4j;javaHome=$java;dataRoot=$data;exportRoot=$exports;secretRoot=$secretRoot;serviceName=$serviceName;distributionHome=$PackageRoot;neo4jVersion=[string]$Manifest.neo4jVersion;createdUtc=[DateTime]::UtcNow.ToString('o')}
    $copied=@()
    try {
        foreach($jar in $releaseJars){Copy-Item -LiteralPath $jar.FullName -Destination $pluginDir; $copied+=Join-Path $pluginDir $jar.Name}
        Save-State $StatePath $state
    } catch {
        foreach($path in $copied){Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue}
        Remove-Item -LiteralPath $depositConfig -Force -ErrorAction SilentlyContinue
        throw
    }
    Write-Output "Deposit installed into Neo4j $($Manifest.neo4jVersion). Start with: deposit.bat start"
}
