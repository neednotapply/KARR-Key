param(
    [string]$SourceRoot = (
        'C:\Users\mrnic\Documents\Playground\karr_four_app_reverse'
    ),
    [string]$RepoRoot = (
        [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
    )
)

$ErrorActionPreference = 'Stop'

$Apps = @(
    'karr_bt_dealer_v3.131',
    'karr_bt_v2.15',
    'karr_installer_v1.39',
    'karr_swat_dl_v2.05'
)

$Rows = foreach ($app in $Apps) {
    $sourceApkRoot = Join-Path $SourceRoot "$app\00_original_package"
    $repoApkRoot = Join-Path $RepoRoot "research\artifacts\apks\$app"
    $sourceApk = Get-ChildItem -LiteralPath $sourceApkRoot -File `
        -Filter '*.apk'
    $repoApk = Get-ChildItem -LiteralPath $repoApkRoot -File -Filter '*.apk'
    if ($sourceApk.Count -ne 1 -or $repoApk.Count -ne 1) {
        throw "Expected one source and repository APK for $app"
    }
    $apkRelative = $repoApk.FullName.Substring(
        $RepoRoot.TrimEnd('\').Length
    ).TrimStart('\').Replace('\', '/')
    [pscustomobject]@{
        app = $app
        kind = 'apk'
        file = $repoApk.Name
        relative_path = $apkRelative
        size_bytes = $repoApk.Length
        source_sha256 = (Get-FileHash -Algorithm SHA256 `
            -LiteralPath $sourceApk.FullName).Hash
        repository_sha256 = (Get-FileHash -Algorithm SHA256 `
            -LiteralPath $repoApk.FullName).Hash
        annotation_state = 'untouched-source-package'
    }

    $sourceIdaRoot = Join-Path $SourceRoot "$app\04_native\ida_databases"
    $repoIdaRoot = Join-Path $RepoRoot "research\artifacts\ida\$app"
    foreach ($repoIda in (
        Get-ChildItem -LiteralPath $repoIdaRoot -File -Filter '*.i64' |
            Sort-Object Name
    )) {
        $sourceIda = Join-Path $sourceIdaRoot $repoIda.Name
        if (-not (Test-Path -LiteralPath $sourceIda -PathType Leaf)) {
            throw "Missing source IDA database: $sourceIda"
        }
        $validationPath = Join-Path $repoIdaRoot (
            "recent_findings\$($repoIda.BaseName).validate.json"
        )
        if (-not (Test-Path -LiteralPath $validationPath -PathType Leaf)) {
            throw "Missing IDA validation report: $validationPath"
        }
        $validation = Get-Content -Raw -LiteralPath $validationPath |
            ConvertFrom-Json
        if ($validation.passed -ne $true) {
            throw "IDA validation did not pass: $validationPath"
        }
        $idaRelative = $repoIda.FullName.Substring(
            $RepoRoot.TrimEnd('\').Length
        ).TrimStart('\').Replace('\', '/')
        [pscustomobject]@{
            app = $app
            kind = 'ida'
            file = $repoIda.Name
            relative_path = $idaRelative
            size_bytes = $repoIda.Length
            source_sha256 = (Get-FileHash -Algorithm SHA256 `
                -LiteralPath $sourceIda).Hash
            repository_sha256 = (Get-FileHash -Algorithm SHA256 `
                -LiteralPath $repoIda.FullName).Hash
            annotation_state = 'managed-findings-note-reopen-validated'
        }
    }
}

$ManifestPath = Join-Path $RepoRoot 'research\artifacts\ARTIFACT_MANIFEST.csv'
$Rows |
    Sort-Object app, kind, file |
    Export-Csv -LiteralPath $ManifestPath -NoTypeInformation -Encoding UTF8

Write-Host "Wrote $($Rows.Count) artifact records to $ManifestPath"
