param(
    [ValidateSet('all', 'build', 'validate')]
    [string]$Phase = 'all'
)

$ErrorActionPreference = 'Stop'

$CollectionRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$Idat = 'C:\Program Files\IDA Professional 9.1\idat.exe'
$Annotator = Join-Path $PSScriptRoot 'annotate_xamarin_ida.py'
$Validator = Join-Path $PSScriptRoot 'validate_ida_comments.py'

$Apps = @(
    [pscustomobject]@{ Slug = 'karr_bt_dealer_v3.131'; Label = 'KARR BT Dealer 3.131' },
    [pscustomobject]@{ Slug = 'karr_bt_v2.15'; Label = 'KARR BT 2.15' },
    [pscustomobject]@{ Slug = 'karr_installer_v1.39'; Label = 'KARR Installer 1.39' },
    [pscustomobject]@{ Slug = 'karr_swat_dl_v2.05'; Label = 'KARR SWAT DL 2.05' }
)

foreach ($required in @($Idat, $Annotator, $Validator)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing IDA dependency: $required"
    }
}

foreach ($app in $Apps) {
    $root = Join-Path $CollectionRoot $app.Slug
    $binaryRoot = Join-Path $root '04_native\binaries'
    $databaseRoot = Join-Path $root '04_native\ida_databases'
    $reportRoot = Join-Path $root '04_native\reports'
    $validationRoot = Join-Path $root 'logs\ida_validation'
    $batchLog = Join-Path $root 'logs\ida_batch.log'
    $validationLog = Join-Path $root 'logs\ida_validation.log'
    New-Item -ItemType Directory -Force -Path $databaseRoot, $reportRoot, $validationRoot | Out-Null

    foreach ($binary in Get-ChildItem -LiteralPath $binaryRoot -Filter '*.so' -File | Sort-Object Name) {
        $database = Join-Path $databaseRoot ($binary.BaseName + '.i64')
        $report = Join-Path $reportRoot ($binary.BaseName + '.json')
        $validation = Join-Path $validationRoot ($binary.BaseName + '.json')

        if ($Phase -in @('all', 'build') -and -not (Test-Path -LiteralPath $database -PathType Leaf)) {
            Write-Host "[$($app.Slug)] IDA build $($binary.Name)"
            $env:KARR_APP_LABEL = $app.Label
            & $Idat -A "-L$batchLog" "-o$database" "-S$Annotator $report" $binary.FullName
            if ($LASTEXITCODE -ne 0) {
                throw "IDA build failed for $($app.Slug) $($binary.Name) with exit code $LASTEXITCODE"
            }
        }

        if ($Phase -in @('all', 'build') -and
            (Test-Path -LiteralPath $database -PathType Leaf) -and
            -not (Test-Path -LiteralPath $report -PathType Leaf)) {
            Write-Host "[$($app.Slug)] IDA relabel seeded database $($binary.Name)"
            $env:KARR_APP_LABEL = $app.Label
            & $Idat -A "-L$batchLog" "-S$Annotator $report" $database
            if ($LASTEXITCODE -ne 0) {
                throw "IDA relabel failed for $($app.Slug) $($binary.Name) with exit code $LASTEXITCODE"
            }
        }

        if ($Phase -in @('all', 'validate')) {
            if (-not (Test-Path -LiteralPath $database -PathType Leaf)) {
                throw "IDA database missing for validation: $database"
            }
            Write-Host "[$($app.Slug)] IDA validate $($binary.Name)"
            & $Idat -A "-L$validationLog" "-S$Validator $validation" $database
            if ($LASTEXITCODE -ne 0) {
                throw "IDA validation failed for $($app.Slug) $($binary.Name) with exit code $LASTEXITCODE"
            }
        }
    }
}

Remove-Item Env:\KARR_APP_LABEL -ErrorAction SilentlyContinue
Write-Host "IDA phase '$Phase' completed."
