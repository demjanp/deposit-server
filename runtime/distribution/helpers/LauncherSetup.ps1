function Read-SetupSetting([string]$Path,[string]$Name) {
    $pattern='(?m)^[ \t]*'+[regex]::Escape($Name)+'[ \t]*=[ \t]*([^\r\n]+)'
    $settings=[regex]::Matches([IO.File]::ReadAllText($Path),$pattern)
    if($settings.Count -ne 1 -or [string]::IsNullOrWhiteSpace($settings[0].Groups[1].Value)){
        throw "Expected one $Name setting in $Path"
    }
    return $settings[0].Groups[1].Value.Trim()
}

function Update-DepositInstallation([string]$Neo4jHome,[string]$JavaHome,[string]$PackageRoot,[string]$StatePath,$ReleaseJars) {
    $recorded=$null
    if(Test-Path -LiteralPath $StatePath){
        $recorded=Read-State $StatePath
        if($recorded.neo4jHome.TrimEnd('\') -ine $Neo4jHome.TrimEnd('\')){throw 'This package is configured for a different Neo4j installation'}
        $JavaHome=$recorded.javaHome
    }

    $pluginDir=Join-Path $Neo4jHome 'plugins'
    if($pluginDir.TrimEnd('\') -ieq (Join-Path $PackageRoot 'plugins').TrimEnd('\')){
        throw 'Extract the release package into a directory separate from the existing Neo4j installation'
    }
    if(!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe') -PathType Leaf)){throw "Java executable is missing from $JavaHome"}
    $depositConfig=Join-Path $Neo4jHome 'conf\deposit.properties'
    $neo4jConfig=Join-Path $Neo4jHome 'conf\neo4j.conf'
    $serviceName=Read-SetupSetting $neo4jConfig 'server.windows_service_name'
    $service=Get-CimInstance Win32_Service | Where-Object { $_.Name -eq $serviceName }
    if($service){
        $executable=[regex]::Escape((Join-Path $Neo4jHome 'bin\tools\prunsrv-amd64.exe'))
        $serviceCommand='^(?:"'+$executable+'"|'+$executable+')\s+//RS//'+[regex]::Escape($serviceName)+'$'
        if($service.PathName.Trim() -notmatch $serviceCommand){throw 'The configured Windows service does not belong to this Neo4j installation'}
        if($service.State -notin @('Running','Stopped')){throw 'Wait for the Windows service to finish changing state, then retry setup'}
    } elseif($recorded -and $recorded.mode -eq 'service'){
        throw 'The recorded Windows service is missing'
    }

    $state=[pscustomobject]@{
        mode=if($service){'service'}else{'local'}
        neo4jHome=$Neo4jHome
        javaHome=$JavaHome
        dataRoot=[IO.Path]::GetFullPath((Read-SetupSetting $depositConfig 'resourceRoot'))
        exportRoot=[IO.Path]::GetFullPath((Read-SetupSetting $depositConfig 'exportRoot'))
        secretRoot=Get-DepositSecretRoot $Neo4jHome
        serviceName=$serviceName
        distributionHome=$PackageRoot
        neo4jVersion=if($recorded -and $recorded.PSObject.Properties['neo4jVersion']){$recorded.neo4jVersion}else{$null}
        pluginFiles=@($ReleaseJars.Name)
    }
    if($service){$state | Add-Member -NotePropertyName serviceAccount -NotePropertyValue $service.StartName}

    Write-Warning "Setup will update Deposit in $Neo4jHome in place. A running server will stop and restart. Plugin JARs will be replaced without a backup or compatibility check."
    if((Read-Host 'Proceed with the update? [y/N]') -notin @('y','yes')){Write-Output 'Update cancelled.';return}

    $wasRunning=$false
    if($service){
        $wasRunning=$service.State -eq 'Running'
        if($wasRunning){Service 'stop' $state $StatePath $PackageRoot @()}
        if((Get-Service -Name $serviceName -ErrorAction Stop).Status -ne 'Stopped'){throw 'The Windows service must be stopped before replacing plugins'}
    } elseif(Get-LocalNeo4jRuntime $Neo4jHome){
        $wasRunning=$true
        Stop-Local $state $PackageRoot
    }
    Test-NoConflictingRuntime $Neo4jHome

    New-Item -ItemType Directory -Path $pluginDir -Force | Out-Null
    $incomingNames=$state.pluginFiles
    $artifactNames=@($incomingNames -replace '-\d+\..*\.jar$','')
    $managedNames=if($recorded -and $recorded.PSObject.Properties['pluginFiles']){@($recorded.pluginFiles)}else{@()}
    $obsolete=@(Get-ChildItem -LiteralPath $pluginDir -Filter '*.jar' -File | Where-Object {
        $_.Name -notin $incomingNames -and ($_.Name -in $managedNames -or ($_.Name -replace '-\d+\..*\.jar$','') -in $artifactNames)
    })
    foreach($jar in $ReleaseJars){
        $target=Join-Path $pluginDir $jar.Name
        $partial=$target+'.'+[guid]::NewGuid().ToString('N')+'.partial'
        try {
            [IO.File]::Copy($jar.FullName,$partial)
            if(Test-Path -LiteralPath $target){[IO.File]::Replace($partial,$target,[NullString]::Value)}else{[IO.File]::Move($partial,$target)}
        } finally {Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue}
    }
    foreach($jar in $obsolete){Remove-Item -LiteralPath $jar.FullName -Force}
    Save-State $StatePath $state

    if($wasRunning){
        if($service){Service 'start' $state $StatePath $PackageRoot @()}else{Start-Local $state $PackageRoot}
    }
    Write-Output "Deposit updated in $Neo4jHome."
}

function Setup([string[]]$Arguments,[string]$PackageRoot,[string]$StatePath,$Manifest) {
    . (Join-Path $PackageRoot 'helpers\Credentials.ps1')
    $configurator=Join-Path $PackageRoot 'helpers\Configure-DepositDownloads.ps1'
    if($Arguments.Count -notin @(4,5)){throw 'Usage: deposit.bat setup <neo4j-home> <java-home> <data-root> <export-root> [bolt-port]'}
    $neo4j=[IO.Path]::GetFullPath($Arguments[0]);$java=[IO.Path]::GetFullPath($Arguments[1])
    $releaseJars=@(Get-ChildItem -LiteralPath (Join-Path $PackageRoot 'plugins') -Filter '*.jar' -File)
    if(!$releaseJars.Count){throw 'The release package has no plugin artifacts'}
    if(Test-Path -LiteralPath (Join-Path $neo4j 'conf\deposit.properties') -PathType Leaf){
        Update-DepositInstallation $neo4j $java $PackageRoot $StatePath $releaseJars
        return
    }
    $data=[IO.Path]::GetFullPath($Arguments[2]);$exports=[IO.Path]::GetFullPath($Arguments[3])
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
    Set-Neo4jSetting $neo4jConfig 'db.logs.query.parameter_logging_enabled' 'false'
    Set-Neo4jSetting $neo4jConfig 'db.logs.query.early_raw_logging_enabled' 'false'
    Set-Neo4jSetting $neo4jConfig 'db.logs.query.obfuscate_literals' 'true'
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
    $state=[ordered]@{mode='local';neo4jHome=$neo4j;javaHome=$java;dataRoot=$data;exportRoot=$exports;secretRoot=$secretRoot;serviceName=$serviceName;distributionHome=$PackageRoot;neo4jVersion=[string]$Manifest.neo4jVersion;createdUtc=[DateTime]::UtcNow.ToString('o');pluginFiles=@($releaseJars.Name)}
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
