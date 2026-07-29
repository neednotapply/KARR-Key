$ErrorActionPreference = 'Stop'

$CollectionRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$PlaygroundRoot = [System.IO.Path]::GetFullPath((Join-Path $CollectionRoot '..'))
$PriorRoot = Join-Path $PlaygroundRoot 'karr_xapk_compare\karr_v0.89_original_decomp_ida'
$PriorBinaries = Join-Path $PriorRoot '04_native\binaries'
$PriorDatabases = Join-Path $PriorRoot '04_native\ida_databases'

if (-not (Test-Path -LiteralPath $PriorBinaries -PathType Container) -or
    -not (Test-Path -LiteralPath $PriorDatabases -PathType Container)) {
    throw 'The prior verified KARR 0.89 IDA workspace is unavailable.'
}

$priorByHash = @{}
foreach ($binary in Get-ChildItem -LiteralPath $PriorBinaries -Filter '*.so' -File) {
    $hash = (Get-FileHash -LiteralPath $binary.FullName -Algorithm SHA256).Hash
    $database = Join-Path $PriorDatabases ($binary.BaseName + '.i64')
    if (Test-Path -LiteralPath $database -PathType Leaf) {
        $priorByHash[$hash] = [pscustomobject]@{
            Binary = $binary.FullName
            Database = $database
            Name = $binary.Name
        }
    }
}

foreach ($app in Get-ChildItem -LiteralPath $CollectionRoot -Directory | Where-Object { $_.Name -like 'karr_*' }) {
    $databaseRoot = Join-Path $app.FullName '04_native\ida_databases'
    $rows = [System.Collections.Generic.List[object]]::new()
    foreach ($binary in Get-ChildItem -LiteralPath (Join-Path $app.FullName '04_native\binaries') -Filter '*.so' -File | Sort-Object Name) {
        $hash = (Get-FileHash -LiteralPath $binary.FullName -Algorithm SHA256).Hash
        $database = Join-Path $databaseRoot ($binary.BaseName + '.i64')
        $seeded = $false
        $seedSource = ''
        if (-not (Test-Path -LiteralPath $database -PathType Leaf) -and $priorByHash.ContainsKey($hash)) {
            $match = $priorByHash[$hash]
            Copy-Item -LiteralPath $match.Database -Destination $database
            $seeded = $true
            $seedSource = $match.Database
        }
        elseif (Test-Path -LiteralPath $database -PathType Leaf) {
            $seedSource = 'generated-or-already-present'
        }

        $rows.Add([pscustomobject]@{
            library = $binary.Name
            sha256 = $hash
            database = "04_native\ida_databases\$($binary.BaseName).i64"
            seeded_from_identical_prior_database = $seeded
            seed_source = $seedSource
        })
    }
    $rows | Export-Csv -LiteralPath (Join-Path $app.FullName '05_annotations\native_database_provenance.csv') -NoTypeInformation -Encoding UTF8
}

Write-Host 'Seeded every available SHA-256-identical prior IDA database.'
