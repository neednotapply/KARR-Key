$ErrorActionPreference = 'Stop'

$CollectionRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$Errors = [System.Collections.Generic.List[string]]::new()

$Apps = @(
    [pscustomobject]@{
        Slug = 'karr_bt_dealer_v3.131'
        MainProject = 'FRMBLEDealerApp'
        ProtocolDocument = 'BLE_PROTOCOL.md'
    },
    [pscustomobject]@{
        Slug = 'karr_bt_v2.15'
        MainProject = 'QTAP'
        ProtocolDocument = 'BLE_PROTOCOL.md'
    },
    [pscustomobject]@{
        Slug = 'karr_installer_v1.39'
        MainProject = 'FRMSWTBLEInstaller'
        ProtocolDocument = 'BLE_PROTOCOL.md'
    },
    [pscustomobject]@{
        Slug = 'karr_swat_dl_v2.05'
        MainProject = 'SWATDealer'
        ProtocolDocument = 'NETWORK_DATA_FLOW.md'
    }
)

function Assert-Collection {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not $Condition) {
        $Errors.Add($Message)
    }
}

$AppResults = [System.Collections.Generic.List[object]]::new()
foreach ($app in $Apps) {
    $root = Join-Path $CollectionRoot $app.Slug
    $annotations = Join-Path $root '05_annotations'
    $binaryRoot = Join-Path $root '01_managed\binaries'
    $projectRoot = Join-Path $root '01_managed\projects'
    $nativeBinaryRoot = Join-Path $root '04_native\binaries'
    $idaRoot = Join-Path $root '04_native\ida_databases'
    $reportRoot = Join-Path $root '04_native\reports'
    $validationRoot = Join-Path $root 'logs\ida_validation'

    Assert-Collection (Test-Path -LiteralPath $root -PathType Container) "$($app.Slug): workspace missing"

    $provenance = Get-Content -LiteralPath (Join-Path $root '00_original_package\provenance.json') -Raw | ConvertFrom-Json
    $summary = Get-Content -LiteralPath (Join-Path $root 'logs\extraction_summary.json') -Raw | ConvertFrom-Json
    $managedStatus = Get-Content -LiteralPath (Join-Path $root 'logs\managed_decompile_status.json') -Raw | ConvertFrom-Json
    $preservedApk = Join-Path $root $provenance.preserved_file
    $preservedHash = (Get-FileHash -LiteralPath $preservedApk -Algorithm SHA256).Hash

    Assert-Collection ($preservedHash -eq $provenance.sha256) "$($app.Slug): preserved APK hash differs from provenance"
    Assert-Collection ($preservedHash -eq $summary.apk_sha256) "$($app.Slug): preserved APK hash differs from extraction summary"
    Assert-Collection ((Get-Item -LiteralPath $preservedApk).Length -eq $provenance.bytes) "$($app.Slug): preserved APK byte count differs"
    if (Test-Path -LiteralPath $provenance.source_file -PathType Leaf) {
        $sourceHash = (Get-FileHash -LiteralPath $provenance.source_file -Algorithm SHA256).Hash
        Assert-Collection ($sourceHash -eq $preservedHash) "$($app.Slug): source APK and preserved APK hashes differ"
    }

    $managedAssemblies = @(Get-ChildItem -LiteralPath $binaryRoot -Filter '*.dll' -File -Recurse)
    $managedProjects = @(Get-ChildItem -LiteralPath $projectRoot -Directory)
    $javaFiles = @(Get-ChildItem -LiteralPath (Join-Path $root '02_java_jadx\sources') -Filter '*.java' -File -Recurse)
    $smaliFiles = @(Get-ChildItem -LiteralPath (Join-Path $root '03_apktool_smali') -Filter '*.smali' -File -Recurse)
    $nativeBinaries = @(Get-ChildItem -LiteralPath $nativeBinaryRoot -Filter '*.so' -File)
    $idaDatabases = @(Get-ChildItem -LiteralPath $idaRoot -Filter '*.i64' -File)
    $nativeReports = @(Get-ChildItem -LiteralPath $reportRoot -Filter '*.json' -File)
    $idaValidations = @(Get-ChildItem -LiteralPath $validationRoot -Filter '*.json' -File)

    Assert-Collection ($managedAssemblies.Count -eq [int]$summary.managed_assemblies) "$($app.Slug): managed assembly count mismatch"
    Assert-Collection ($managedAssemblies.Count -eq [int]$managedStatus.assembly_count) "$($app.Slug): managed status assembly count mismatch"
    Assert-Collection ($managedProjects.Count -eq [int]$managedStatus.completed_project_directories) "$($app.Slug): managed project count mismatch"
    Assert-Collection (@($managedStatus.failures).Count -eq 0) "$($app.Slug): managed decompilation failures recorded"
    Assert-Collection (Test-Path -LiteralPath (Join-Path $projectRoot $app.MainProject) -PathType Container) "$($app.Slug): main C# project missing"
    Assert-Collection (Test-Path -LiteralPath (Join-Path $root '01_managed\diagrammer\index.html') -PathType Leaf) "$($app.Slug): managed diagrammer missing"
    Assert-Collection ($javaFiles.Count -eq [int]$summary.jadx_java_files) "$($app.Slug): JADX Java count mismatch"
    Assert-Collection ($smaliFiles.Count -eq [int]$summary.smali_files) "$($app.Slug): smali count mismatch"
    Assert-Collection ($nativeBinaries.Count -eq [int]$summary.native_arm64_libraries) "$($app.Slug): native binary count mismatch"
    Assert-Collection ($nativeBinaries.Count -eq $idaDatabases.Count) "$($app.Slug): native binary/IDA database count mismatch"
    Assert-Collection ($nativeBinaries.Count -eq $nativeReports.Count) "$($app.Slug): native binary/report count mismatch"
    Assert-Collection ($nativeBinaries.Count -eq $idaValidations.Count) "$($app.Slug): native binary/validation count mismatch"

    foreach ($validationFile in $idaValidations) {
        $validation = Get-Content -LiteralPath $validationFile.FullName -Raw | ConvertFrom-Json
        Assert-Collection ([bool]$validation.all_functions_commented) "$($app.Slug): not every IDA function is commented in $($validationFile.Name)"
        Assert-Collection ([int]$validation.function_count -eq [int]$validation.repeatably_commented_function_count) "$($app.Slug): IDA function/comment count differs in $($validationFile.Name)"
    }
    $idaFunctionCount = ($idaValidations | ForEach-Object {
        [int](Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json).function_count
    } | Measure-Object -Sum).Sum

    $requiredAnnotations = @(
        'ARCHITECTURE.md',
        'FEATURE_MAP.md',
        'API_SURFACE.md',
        'VIEW_ROUTE_MAP.md',
        $app.ProtocolDocument,
        'SECURITY_AND_MASTER_APP_NOTES.md',
        'IDA_GUIDE.md',
        'COMPLETENESS.md',
        'managed_symbols.csv',
        'first_party_managed_symbols.csv',
        'first_party_call_graph.csv',
        'api_route_index.csv',
        'view_inventory.csv',
        'managed_binary_hashes.csv',
        'native_index.csv',
        'native_database_provenance.csv',
        'artifact_provenance.csv'
    )
    foreach ($name in $requiredAnnotations) {
        Assert-Collection (Test-Path -LiteralPath (Join-Path $annotations $name) -PathType Leaf) "$($app.Slug): missing annotation $name"
    }

    $managedHashRows = @(Import-Csv -LiteralPath (Join-Path $annotations 'managed_binary_hashes.csv'))
    $nativeRows = @(Import-Csv -LiteralPath (Join-Path $annotations 'native_index.csv'))
    $routeRows = @(Import-Csv -LiteralPath (Join-Path $annotations 'api_route_index.csv'))
    $viewRows = @(Import-Csv -LiteralPath (Join-Path $annotations 'view_inventory.csv'))
    Assert-Collection ($managedHashRows.Count -eq $managedAssemblies.Count) "$($app.Slug): managed hash index count mismatch"
    Assert-Collection ($nativeRows.Count -eq $nativeBinaries.Count) "$($app.Slug): native index count mismatch"
    Assert-Collection (@($nativeRows | Where-Object { $_.all_functions_commented -ne 'True' }).Count -eq 0) "$($app.Slug): native index contains unvalidated database"
    Assert-Collection (@($routeRows | Where-Object { $_.route -match '\?' }).Count -eq 0) "$($app.Slug): API route index unexpectedly contains query values"
    Assert-Collection ($viewRows.Count -gt 0) "$($app.Slug): view inventory is empty"
    Assert-Collection (Test-Path -LiteralPath (Join-Path $root 'README.md') -PathType Leaf) "$($app.Slug): README missing"

    $AppResults.Add([pscustomobject]@{
        slug = $app.Slug
        apk_sha256 = $preservedHash
        managed_assemblies = $managedAssemblies.Count
        managed_projects = $managedProjects.Count
        jadx_java_files = $javaFiles.Count
        smali_files = $smaliFiles.Count
        native_libraries = $nativeBinaries.Count
        ida_databases = $idaDatabases.Count
        ida_functions = $idaFunctionCount
        api_route_rows = $routeRows.Count
        view_rows = $viewRows.Count
        all_ida_functions_commented = (@($idaValidations | ForEach-Object {
            (Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json).all_functions_commented
        } | Where-Object { -not $_ }).Count -eq 0)
    })
}

$requiredCollectionDocuments = @(
    'README.md',
    'MASTER_APP_FEATURE_MAP.md',
    'MASTER_APP_ARCHITECTURE.md',
    'CROSS_APP_CODE_REUSE.md',
    'SECURITY_MIGRATION_NOTES.md',
    'COLLECTION_COMPLETENESS.md',
    'TOOLCHAIN.md'
)
foreach ($name in $requiredCollectionDocuments) {
    Assert-Collection (Test-Path -LiteralPath (Join-Path $CollectionRoot $name) -PathType Leaf) "collection: missing $name"
}

$result = [pscustomobject]@{
    generated_utc = [DateTime]::UtcNow.ToString('o')
    passed = ($Errors.Count -eq 0)
    app_count = $AppResults.Count
    totals = [pscustomobject]@{
        managed_assemblies = ($AppResults | Measure-Object -Property managed_assemblies -Sum).Sum
        managed_projects = ($AppResults | Measure-Object -Property managed_projects -Sum).Sum
        jadx_java_files = ($AppResults | Measure-Object -Property jadx_java_files -Sum).Sum
        smali_files = ($AppResults | Measure-Object -Property smali_files -Sum).Sum
        native_libraries = ($AppResults | Measure-Object -Property native_libraries -Sum).Sum
        ida_databases = ($AppResults | Measure-Object -Property ida_databases -Sum).Sum
        ida_functions = ($AppResults | Measure-Object -Property ida_functions -Sum).Sum
    }
    apps = @($AppResults)
    errors = @($Errors)
}

$output = Join-Path $CollectionRoot 'collection_validation.json'
$result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $output -Encoding UTF8

if (-not $result.passed) {
    $Errors | ForEach-Object { Write-Error $_ }
    throw "Collection validation failed with $($Errors.Count) error(s). See $output"
}

Write-Host "PASS: $($AppResults.Count) apps, $($result.totals.managed_assemblies) assemblies, $($result.totals.ida_databases) IDA databases, $($result.totals.ida_functions) commented functions."
