$ErrorActionPreference = 'Stop'

$CollectionRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$PlaygroundRoot = [System.IO.Path]::GetFullPath((Join-Path $CollectionRoot '..'))
$PriorScripts = Join-Path $PlaygroundRoot 'karr_xapk_compare\karr_v0.89_original_decomp_ida\scripts'
$Csc = 'C:\Program Files\Microsoft Visual Studio\2022\Community\MSBuild\Current\Bin\Roslyn\csc.exe'
$NetStandard = 'C:\Program Files (x86)\Reference Assemblies\Microsoft\Framework\.NETFramework\v4.8\Facades\netstandard.dll'
$Cecil = Join-Path $PriorScripts 'Mono.Cecil.dll'
$IndexTool = Join-Path $PSScriptRoot 'ExportManagedIndex.exe'
$CallTool = Join-Path $PSScriptRoot 'ExportManagedCalls.exe'
$IlSpy = Join-Path $PlaygroundRoot 'karr_xapk_compare\tools\ilspycmd9\ilspycmd.exe'

$Apps = @(
    [pscustomobject]@{
        Slug = 'karr_bt_dealer_v3.131'
        Main = 'FRMBLEDealerApp.dll'
        FirstParty = @('FRMBLEDealerApp.dll', 'WebhouseStandard.dll', 'Validation.dll', 'CsvReader.dll', 'FormsViewGroup.dll')
    },
    [pscustomobject]@{
        Slug = 'karr_bt_v2.15'
        Main = 'QTAP.dll'
        FirstParty = @('QTAP.dll', 'FormsViewGroup.dll')
    },
    [pscustomobject]@{
        Slug = 'karr_installer_v1.39'
        Main = 'FRMSWTBLEInstaller.dll'
        FirstParty = @('FRMSWTBLEInstaller.dll', 'WebhouseStandard.dll', 'Validation.dll', 'FastAndroidCamera.dll', 'ZXingNetMobile.dll', 'FormsViewGroup.dll')
    },
    [pscustomobject]@{
        Slug = 'karr_swat_dl_v2.05'
        Main = 'SWATDealer.dll'
        FirstParty = @('SWATDealer.dll', 'WebhouseStandard.dll', 'Validation.dll', 'FormsViewGroup.dll')
    }
)

foreach ($required in @($Csc, $NetStandard, $Cecil, $IlSpy)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing tool dependency: $required"
    }
}

Copy-Item -LiteralPath $Cecil -Destination (Join-Path $PSScriptRoot 'Mono.Cecil.dll') -Force
Copy-Item -LiteralPath (Join-Path $PriorScripts 'ExportManagedIndex.exe') -Destination $IndexTool -Force

& $Csc /nologo /target:exe /langversion:7.3 /reference:$Cecil /reference:$NetStandard "/out:$CallTool" (Join-Path $PSScriptRoot 'ExportManagedCalls.cs')
if ($LASTEXITCODE -ne 0) {
    throw 'ExportManagedCalls compilation failed'
}

foreach ($app in $Apps) {
    $root = Join-Path $CollectionRoot $app.Slug
    $binaryRoot = Join-Path $root '01_managed\binaries'
    $annotations = Join-Path $root '05_annotations'
    $firstPartyRoot = Join-Path $root 'logs\first_party_binaries'
    New-Item -ItemType Directory -Force -Path $firstPartyRoot | Out-Null

    & $IndexTool $binaryRoot (Join-Path $annotations 'managed_symbols.csv')
    if ($LASTEXITCODE -ne 0) {
        throw "Full managed symbol export failed for $($app.Slug)"
    }

    foreach ($name in $app.FirstParty) {
        $source = Join-Path $binaryRoot $name
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Missing first-party assembly for $($app.Slug): $name"
        }
        Copy-Item -LiteralPath $source -Destination $firstPartyRoot -Force
    }

    & $IndexTool $firstPartyRoot (Join-Path $annotations 'first_party_managed_symbols.csv')
    if ($LASTEXITCODE -ne 0) {
        throw "First-party symbol export failed for $($app.Slug)"
    }

    & $CallTool $binaryRoot (Join-Path $annotations 'first_party_call_graph.csv') ($app.FirstParty -join ';')
    if ($LASTEXITCODE -ne 0) {
        throw "First-party call graph export failed for $($app.Slug)"
    }

    $diagrammer = Join-Path $root '01_managed\diagrammer'
    if (-not (Test-Path -LiteralPath (Join-Path $diagrammer 'index.html') -PathType Leaf)) {
        & $IlSpy --disable-updatecheck (Join-Path $binaryRoot $app.Main) --generate-diagrammer --outputdir $diagrammer
        if ($LASTEXITCODE -ne 0) {
            throw "Diagrammer generation failed for $($app.Slug)"
        }
    }
}

Write-Host 'Managed indexes and app diagrams completed.'
