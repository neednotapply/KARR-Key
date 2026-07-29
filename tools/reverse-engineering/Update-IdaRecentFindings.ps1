param(
    [ValidateSet('all', 'apply', 'validate')]
    [string]$Phase = 'all',
    [string]$ArtifactRoot = (
        Join-Path $PSScriptRoot '..\..\research\artifacts\ida'
    )
)

$ErrorActionPreference = 'Stop'

$Idat = 'C:\Program Files\IDA Professional 9.1\idat.exe'
$Updater = Join-Path $PSScriptRoot 'update_ida_recent_findings.py'
$ArtifactRoot = [System.IO.Path]::GetFullPath($ArtifactRoot)
$LogRoot = Join-Path $ArtifactRoot '_logs'

foreach ($required in @($Idat, $Updater)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing IDA update dependency: $required"
    }
}

if (-not (Test-Path -LiteralPath $ArtifactRoot -PathType Container)) {
    throw "IDA artifact root does not exist: $ArtifactRoot"
}

New-Item -ItemType Directory -Force -Path $LogRoot | Out-Null

$Databases = Get-ChildItem -File -Recurse -LiteralPath $ArtifactRoot `
    -Filter '*.i64' | Sort-Object FullName

if ($Databases.Count -eq 0) {
    throw "No IDA databases found under $ArtifactRoot"
}

foreach ($database in $Databases) {
    $rootPrefix = $ArtifactRoot.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar
    )
    $relative = $database.FullName.Substring(
        $rootPrefix.Length
    ).TrimStart([System.IO.Path]::DirectorySeparatorChar)
    $appSlug = $relative.Split(
        [System.IO.Path]::DirectorySeparatorChar
    )[0]
    $reportRoot = Join-Path $ArtifactRoot "$appSlug\recent_findings"
    $safeName = $database.BaseName
    New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null

    if ($Phase -in @('all', 'apply')) {
        $applyReport = Join-Path $reportRoot "$safeName.apply.json"
        $applyLog = Join-Path $LogRoot "$appSlug-$safeName-apply.log"
        Write-Host "[$appSlug] IDA research update $($database.Name)"
        & $Idat -A "-L$applyLog" `
            "-S$Updater apply $applyReport" $database.FullName
        if ($LASTEXITCODE -ne 0) {
            throw (
                "IDA research update failed for $relative " +
                "with exit code $LASTEXITCODE"
            )
        }
    }

    if ($Phase -in @('all', 'validate')) {
        $validationReport = Join-Path $reportRoot "$safeName.validate.json"
        $validationLog = Join-Path $LogRoot "$appSlug-$safeName-validate.log"
        Write-Host "[$appSlug] IDA research validate $($database.Name)"
        & $Idat -A "-L$validationLog" `
            "-S$Updater validate $validationReport" $database.FullName
        if ($LASTEXITCODE -ne 0) {
            throw (
                "IDA research validation failed for $relative " +
                "with exit code $LASTEXITCODE"
            )
        }
    }
}

Write-Host (
    "IDA recent-findings phase '$Phase' completed for " +
    "$($Databases.Count) databases."
)
