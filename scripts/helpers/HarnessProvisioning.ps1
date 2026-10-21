function Test-Artifacts([switch]$VerifyChecksums,[switch]$SkipProvided) {
    foreach ($artifact in $manifest.artifacts) {
        $file = Join-Path $Root "cache\$($artifact.file)"
        if (!(Test-Path $file) -or ($VerifyChecksums -and (Get-FileHash $file -Algorithm SHA256).Hash -ne $artifact.sha256)) {
            throw "Artifact missing or checksum mismatch: $($artifact.id)"
        }
    }
    if($VerifyChecksums -and (Get-FileHash "$repo\runtime\spatial-dependencies.pom.xml" -Algorithm SHA256).Hash -ne $spatialLock.sourcePomSha256) {throw 'Spatial dependency lock does not match its source POM'}
    foreach($artifact in @($spatialLock.dependencies | Where-Object ship)) {
        $file=Join-Path $Root "cache\spatial-dependencies\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file) -or ($VerifyChecksums -and (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $artifact.sha256)) {throw "Spatial dependency missing or checksum mismatch: $($artifact.file)"}
    }
    if($VerifyChecksums -and (Get-FileHash "$repo\runtime\ocfl-dependencies.pom.xml" -Algorithm SHA256).Hash -ne $ocflLock.sourcePomSha256) {throw 'OCFL dependency lock does not match its source POM'}
    foreach($artifact in @($ocflLock.dependencies | Where-Object ship)) {
        $file=Join-Path $Root "cache\ocfl-dependencies\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file) -or ($VerifyChecksums -and (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $artifact.sha256)) {throw "OCFL dependency missing or checksum mismatch: $($artifact.file)"}
    }
    foreach($artifact in @($ocflLock.dependencies | Where-Object {-not $_.ship -and -not $SkipProvided})) {
        $file=Join-Path $Root "$($manifest.neo4jHome)\lib\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $artifact.sha256){throw "Provided OCFL dependency differs from the pinned runtime: $($artifact.file)"}
    }
    if($VerifyChecksums -and (Get-FileHash "$repo\runtime\format-dependencies.pom.xml" -Algorithm SHA256).Hash -ne $formatLock.sourcePomSha256) {throw 'Format dependency lock does not match its source POM'}
    foreach($artifact in @($formatLock.dependencies | Where-Object ship)) {
        $file=Join-Path $Root "cache\format-dependencies\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file) -or ($VerifyChecksums -and (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $artifact.sha256)) {throw "Format dependency missing or checksum mismatch: $($artifact.file)"}
    }
    foreach($artifact in @($formatLock.dependencies | Where-Object {-not $_.ship -and -not $SkipProvided})) {
        $file=Join-Path $Root "$($manifest.neo4jHome)\lib\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $artifact.sha256){throw "Provided format dependency differs from the pinned runtime: $($artifact.file)"}
    }
}

function Invoke-Provision($Context,[string]$ScriptsDir) {$Root=$Context.Root; $repo=$Context.Repo; $run=$Context.Run; $evidence=$Context.Evidence; $manifest=$Context.Manifest; $spatialLock=$Context.SpatialLock; $ocflLock=$Context.OcflLock; $formatLock=$Context.FormatLock; $RunId=$Context.RunId; $Port=$Context.Port; $ReleasePackage=$Context.ReleasePackage; $BoltUri=$Context.BoltUri; $DiagnosticBuild=$Context.DiagnosticBuild; $WithoutProbe=$Context.WithoutProbe; $StartupTimeoutSeconds=$Context.StartupTimeoutSeconds; $installation=$Context.Installation; $credentialTarget=$Context.CredentialTarget; $utf8=$Context.Utf8;
    New-Item -ItemType Directory -Force "$Root\cache","$Root\tools" | Out-Null
    foreach ($artifact in $manifest.artifacts) {
        $file = Join-Path $Root "cache\$($artifact.file)"
        if (!(Test-Path $file)) {
            Invoke-WebRequest -UseBasicParsing -Uri $artifact.url -OutFile "$file.partial"
            if ((Get-FileHash "$file.partial" -Algorithm SHA256).Hash -ne $artifact.sha256) { throw "Download hash mismatch: $($artifact.id)" }
            Move-Item -LiteralPath "$file.partial" -Destination $file
        }
    }
    New-Item -ItemType Directory -Force "$Root\cache\spatial-dependencies" | Out-Null
    foreach($artifact in @($spatialLock.dependencies | Where-Object ship)) {
        $file=Join-Path $Root "cache\spatial-dependencies\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file)) {
            Invoke-WebRequest -UseBasicParsing -Uri $artifact.url -OutFile "$file.partial"
            if((Get-FileHash -LiteralPath "$file.partial" -Algorithm SHA256).Hash -ne $artifact.sha256) {throw "Spatial dependency download hash mismatch: $($artifact.file)"}
            Move-Item -LiteralPath "$file.partial" -Destination $file
        }
    }

    New-Item -ItemType Directory -Force "$Root\cache\ocfl-dependencies" | Out-Null
    foreach($artifact in @($ocflLock.dependencies | Where-Object ship)) {
        $file=Join-Path $Root "cache\ocfl-dependencies\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file)) {
            Invoke-WebRequest -UseBasicParsing -Uri $artifact.url -OutFile "$file.partial"
            if((Get-FileHash -LiteralPath "$file.partial" -Algorithm SHA256).Hash -ne $artifact.sha256) {throw "OCFL dependency download hash mismatch: $($artifact.file)"}
            Move-Item -LiteralPath "$file.partial" -Destination $file
        }
    }
    New-Item -ItemType Directory -Force "$Root\cache\format-dependencies" | Out-Null
    foreach($artifact in @($formatLock.dependencies | Where-Object ship)) {
        $file=Join-Path $Root "cache\format-dependencies\$($artifact.file)"
        if(!(Test-Path -LiteralPath $file)) {
            Invoke-WebRequest -UseBasicParsing -Uri $artifact.url -OutFile "$file.partial"
            if((Get-FileHash -LiteralPath "$file.partial" -Algorithm SHA256).Hash -ne $artifact.sha256) {throw "Format dependency download hash mismatch: $($artifact.file)"}
            Move-Item -LiteralPath "$file.partial" -Destination $file
        }
    }
    Invoke-ProvisionIntegrationTools
    Test-Artifacts -VerifyChecksums -SkipProvided
    Invoke-ProvisionLicenses
    foreach ($id in @('temurin','neo4j','maven')) {
        $artifact = $manifest.artifacts | Where-Object id -eq $id
        $relative=switch($id) {
            'temurin' {$manifest.javaHome}
            'neo4j' {$manifest.neo4jHome}
            'maven' {'tools/'+(($artifact.file -replace '-bin\.zip$',''))}
        }
        if (!(Test-Path -LiteralPath (Join-Path $Root $relative) -PathType Container)) {
            Expand-Archive -LiteralPath (Join-Path $Root "cache\$($artifact.file)") -DestinationPath "$Root\tools" -Force
        }
        $null=Get-ToolDirectory $manifest $id $Root
    }
    Test-Artifacts -VerifyChecksums
}

function Invoke-ProvisionIntegrationTools {
    $toolManifest=Get-Content -LiteralPath (Join-Path $repo 'runtime\integration-tools.json') -Raw | ConvertFrom-Json
    $pythonArchive=Join-Path $Root ('cache\'+$toolManifest.pythonArchive)
    $firefoxArchive=Join-Path $Root ('cache\'+$toolManifest.firefoxArchive)
    New-Item -ItemType Directory -Force (Join-Path $Root 'cache'),(Join-Path $Root $toolManifest.pythonDirectory),(Join-Path $Root $toolManifest.firefoxDirectory) | Out-Null
    if(!(Test-Path -LiteralPath $pythonArchive -PathType Leaf)){
        Invoke-WebRequest -UseBasicParsing -Uri $toolManifest.pythonUrl -OutFile ($pythonArchive+'.partial')
        if((Get-FileHash -LiteralPath ($pythonArchive+'.partial') -Algorithm SHA256).Hash -ine $toolManifest.pythonSha256){throw 'Pinned Python embed package checksum mismatch'}
        Move-Item -LiteralPath ($pythonArchive+'.partial') -Destination $pythonArchive
    }
    if((Get-FileHash -LiteralPath $pythonArchive -Algorithm SHA256).Hash -ine $toolManifest.pythonSha256){throw 'Cached Python embed package checksum mismatch'}
    $pythonExe=Join-Path $Root $toolManifest.pythonExe
    if(!(Test-Path -LiteralPath $pythonExe -PathType Leaf)){Expand-Archive -LiteralPath $pythonArchive -DestinationPath (Join-Path $Root $toolManifest.pythonDirectory) -Force}
    if(!(Test-Path -LiteralPath $pythonExe -PathType Leaf)){throw 'Pinned Python package did not yield python.exe'}

    if(!(Test-Path -LiteralPath $firefoxArchive -PathType Leaf)){
        Invoke-WebRequest -UseBasicParsing -Uri $toolManifest.firefoxUrl -OutFile ($firefoxArchive+'.partial')
        if((Get-FileHash -LiteralPath ($firefoxArchive+'.partial') -Algorithm SHA512).Hash -ine $toolManifest.firefoxSha512){throw 'Pinned Firefox MSI checksum mismatch'}
        Move-Item -LiteralPath ($firefoxArchive+'.partial') -Destination $firefoxArchive
    }
    if((Get-FileHash -LiteralPath $firefoxArchive -Algorithm SHA512).Hash -ine $toolManifest.firefoxSha512){throw 'Cached Firefox MSI checksum mismatch'}
    $firefoxExe=Join-Path $Root $toolManifest.firefoxExe
    if(!(Test-Path -LiteralPath $firefoxExe -PathType Leaf)){
        $firefoxDirectory=Join-Path $Root $toolManifest.firefoxDirectory
        $arguments=@('/i',('"'+$firefoxArchive+'"'),('EXTRACT_DIR="'+$firefoxDirectory+'"'),'/quiet','/norestart')
        $process=Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden
        if($process.ExitCode -ne 0){throw "Mozilla Firefox extraction failed with exit code $($process.ExitCode)"}
    }
    if(!(Test-Path -LiteralPath $firefoxExe -PathType Leaf)){throw 'Pinned Firefox MSI did not yield firefox.exe'}

    $geckodriverArchive=Join-Path $Root ('cache\'+$toolManifest.geckodriverArchive)
    if(!(Test-Path -LiteralPath $geckodriverArchive -PathType Leaf)){
        Invoke-WebRequest -UseBasicParsing -Uri $toolManifest.geckodriverUrl -OutFile ($geckodriverArchive+'.partial')
        if((Get-FileHash -LiteralPath ($geckodriverArchive+'.partial') -Algorithm SHA256).Hash -ine $toolManifest.geckodriverSha256){throw 'Pinned geckodriver archive checksum mismatch'}
        Move-Item -LiteralPath ($geckodriverArchive+'.partial') -Destination $geckodriverArchive
    }
    if((Get-FileHash -LiteralPath $geckodriverArchive -Algorithm SHA256).Hash -ine $toolManifest.geckodriverSha256){throw 'Cached geckodriver archive checksum mismatch'}
    $geckodriverExe=Join-Path $Root $toolManifest.geckodriverExe
    if(!(Test-Path -LiteralPath $geckodriverExe -PathType Leaf)){
        $geckodriverDirectory=Join-Path $Root $toolManifest.geckodriverDirectory
        New-Item -ItemType Directory -Force -Path $geckodriverDirectory | Out-Null
        Expand-Archive -LiteralPath $geckodriverArchive -DestinationPath $geckodriverDirectory -Force
    }
    if(!(Test-Path -LiteralPath $geckodriverExe -PathType Leaf)){throw 'Pinned geckodriver archive did not yield geckodriver.exe'}
}

function Invoke-ProvisionLicenses {
    $licenses=Get-Content "$repo\runtime\license-overrides.json" -Raw | ConvertFrom-Json
    foreach($property in $licenses.PSObject.Properties) {
        $license=$property.Value
        $file=Join-Path "$Root\cache" $license.file
        if(!(Test-Path -LiteralPath $file)) {
            New-Item -ItemType Directory -Force (Split-Path $file -Parent) | Out-Null
            Invoke-WebRequest -UseBasicParsing -Uri $license.url -OutFile $file
            if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $license.sha256) {throw 'License download checksum mismatch'}
        }
    }
}
