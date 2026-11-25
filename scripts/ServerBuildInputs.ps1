function Get-ServerArtifact($Artifact, [string]$Directory) {
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    $file = Join-Path $Directory $Artifact.file
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) {
        $partial = $file + '.' + [guid]::NewGuid().ToString('N') + '.partial'
        try {
            Write-Host "Downloading $($Artifact.file)"
            Invoke-WebRequest -UseBasicParsing -Uri $Artifact.url -OutFile $partial
            if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ine $Artifact.sha256) {
                throw "Download checksum mismatch: $($Artifact.file)"
            }
            Move-Item -LiteralPath $partial -Destination $file
        } finally { Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue }
    }
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ine $Artifact.sha256) {
        throw "Cached artifact checksum mismatch: $file. Remove this file and retry."
    }
    return $file
}

function Initialize-ServerTools($Manifest, [string]$Root) {
    $tools = Join-Path $root 'tools'
    New-Item -ItemType Directory -Path $tools -Force | Out-Null
    foreach ($id in @('temurin','neo4j','maven')) {
        $artifact = $manifest.artifacts | Where-Object id -eq $id
        $relative = switch ($id) {
            temurin { $manifest.javaHome }
            neo4j { $manifest.neo4jHome }
            maven { 'tools/' + ($artifact.file -replace '-bin\.zip$','') }
        }
        $toolHome = Join-Path $root $relative
        $required = switch ($id) {
            temurin { @('bin\java.exe','bin\javac.exe') }
            neo4j { @('bin\neo4j.bat','bin\neo4j-admin.bat') }
            maven { @('bin\mvn.cmd') }
        }
        if (@($required | Where-Object { !(Test-Path -LiteralPath (Join-Path $toolHome $_) -PathType Leaf) }).Count) {
            $archive = Get-ServerArtifact $artifact (Join-Path $root 'cache')
            Expand-Archive -LiteralPath $archive -DestinationPath $tools -Force
        }
        foreach ($name in $required) {
            if (!(Test-Path -LiteralPath (Join-Path $toolHome $name) -PathType Leaf)) {
                throw "Runtime extraction did not yield $id file $name"
            }
        }
    }
}

function Get-ServerPluginDependencies($Manifest, [string]$Root, [string]$Repo) {
    $jars = @()
    foreach ($id in @('apoc','spatial','bcrypt-provider')) {
        $jars += Get-ServerArtifact ($manifest.artifacts | Where-Object id -eq $id) (Join-Path $root 'cache')
    }
    foreach ($dependency in @(
        @{ File=$manifest.dependencyLock; Pom='spatial-dependencies.pom.xml'; Cache='spatial-dependencies' },
        @{ File=$manifest.ocflDependencyLock; Pom='ocfl-dependencies.pom.xml'; Cache='ocfl-dependencies' },
        @{ File=$manifest.formatDependencyLock; Pom='format-dependencies.pom.xml'; Cache='format-dependencies' }
    )) {
        $dependencyLock = Get-Content -LiteralPath (Join-Path "$repo\runtime" $dependency.File) -Raw | ConvertFrom-Json
        if ((Get-FileHash -LiteralPath (Join-Path "$repo\runtime" $dependency.Pom) -Algorithm SHA256).Hash -ine $dependencyLock.sourcePomSha256) {
            throw "Dependency lock does not match $($dependency.Pom)"
        }
        foreach ($artifact in $dependencyLock.dependencies) {
            if ($artifact.ship) {
                $jars += Get-ServerArtifact $artifact (Join-Path "$root\cache" $dependency.Cache)
            } elseif ($dependency.Cache -ne 'spatial-dependencies') {
                # Spatial's approved lock deliberately uses newer Neo4j
                # replacements; only the other locks require identical JARs.
                $provided = Join-Path (Join-Path $root $manifest.neo4jHome) "lib\$($artifact.file)"
                if ((Get-FileHash -LiteralPath $provided -Algorithm SHA256).Hash -ine $artifact.sha256) {
                    throw "Pinned Neo4j dependency differs: $($artifact.file)"
                }
            }
        }
    }
    return $jars
}
