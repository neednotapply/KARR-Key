$ErrorActionPreference = 'Stop'

$CollectionRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

$Apps = @(
    [pscustomobject]@{
        Slug = 'karr_bt_dealer_v3.131'
        MainAssembly = 'FRMBLEDealerApp.dll'
        SourceProjects = @('FRMBLEDealerApp', 'WebhouseStandard', 'Validation', 'CsvReader', 'FormsViewGroup')
    },
    [pscustomobject]@{
        Slug = 'karr_bt_v2.15'
        MainAssembly = 'QTAP.dll'
        SourceProjects = @('QTAP', 'FormsViewGroup')
    },
    [pscustomobject]@{
        Slug = 'karr_installer_v1.39'
        MainAssembly = 'FRMSWTBLEInstaller.dll'
        SourceProjects = @('FRMSWTBLEInstaller', 'WebhouseStandard', 'Validation', 'FastAndroidCamera', 'ZXingNetMobile', 'FormsViewGroup')
    },
    [pscustomobject]@{
        Slug = 'karr_swat_dl_v2.05'
        MainAssembly = 'SWATDealer.dll'
        SourceProjects = @('SWATDealer', 'WebhouseStandard', 'Validation', 'FormsViewGroup')
    }
)

function Get-RelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$Base,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $baseUri = [System.Uri]::new(($Base.TrimEnd('\') + '\'))
    $pathUri = [System.Uri]::new($Path)
    return [System.Uri]::UnescapeDataString($baseUri.MakeRelativeUri($pathUri).ToString()).Replace('/', '\')
}

foreach ($app in $Apps) {
    $root = Join-Path $CollectionRoot $app.Slug
    $annotations = Join-Path $root '05_annotations'
    $projectRoot = Join-Path $root '01_managed\projects'
    $binaryRoot = Join-Path $root '01_managed\binaries'
    New-Item -ItemType Directory -Force -Path $annotations | Out-Null

    $sourceRoots = foreach ($project in $app.SourceProjects) {
        $candidate = Join-Path $projectRoot $project
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            Get-Item -LiteralPath $candidate
        }
    }

    $routes = [System.Collections.Generic.List[object]]::new()
    $views = [System.Collections.Generic.List[object]]::new()
    foreach ($sourceRoot in $sourceRoots) {
        $projectName = $sourceRoot.Name
        Get-ChildItem -LiteralPath $sourceRoot.FullName -Filter '*.cs' -File -Recurse | ForEach-Object {
            $file = $_
            $sourceText = [System.IO.File]::ReadAllText($file.FullName)
            foreach ($viewMatch in [regex]::Matches(
                $sourceText,
                '(?s)\[XamlFilePath\("([^"]+\.xaml)"\)\]\s*public\s+(?:partial\s+)?class\s+([A-Za-z0-9_]+)'
            )) {
                $views.Add([pscustomobject]@{
                    project = $projectName
                    xaml_file = "[compiled into C#] $($viewMatch.Groups[1].Value)"
                    class = $viewMatch.Groups[2].Value
                    code_behind_present = $true
                })
            }
            $lineNumber = 0
            foreach ($line in [System.IO.File]::ReadLines($file.FullName)) {
                $lineNumber++
                foreach ($match in [regex]::Matches($line, '(?i)/api/[A-Za-z0-9_./{}:-]+')) {
                    $operation = if ($line -match '(?i)PostRequest|PostAsync|Upload') {
                        'POST-like'
                    }
                    elseif ($line -match '(?i)GetRequest|GetAsync|WebRequest\.Create|Download') {
                        'GET-like'
                    }
                    else {
                        'unspecified'
                    }
                    $routes.Add([pscustomobject]@{
                        project = $projectName
                        source_file = Get-RelativePath -Base $root -Path $file.FullName
                        line = $lineNumber
                        operation = $operation
                        route = $match.Value
                    })
                }
            }
        }

        Get-ChildItem -LiteralPath $sourceRoot.FullName -Filter '*.xaml' -File -Recurse | ForEach-Object {
            $codeBehind = "$($_.FullName).cs"
            $className = ''
            try {
                $head = [System.IO.File]::ReadAllText($_.FullName)
                $classMatch = [regex]::Match($head, 'x:Class="([^"]+)"')
                if ($classMatch.Success) {
                    $className = $classMatch.Groups[1].Value
                }
            }
            catch {
                $className = ''
            }
            $views.Add([pscustomobject]@{
                project = $projectName
                xaml_file = Get-RelativePath -Base $root -Path $_.FullName
                class = $className
                code_behind_present = Test-Path -LiteralPath $codeBehind -PathType Leaf
            })
        }
    }

    $routes |
        Sort-Object route, project, source_file, line -Unique |
        Export-Csv -LiteralPath (Join-Path $annotations 'api_route_index.csv') -NoTypeInformation -Encoding UTF8
    $views |
        Sort-Object project, class, xaml_file -Unique |
        Export-Csv -LiteralPath (Join-Path $annotations 'view_inventory.csv') -NoTypeInformation -Encoding UTF8

    Get-ChildItem -LiteralPath $binaryRoot -Filter '*.dll' -File -Recurse |
        Sort-Object FullName |
        ForEach-Object {
            [pscustomobject]@{
                relative_path = Get-RelativePath -Base $root -Path $_.FullName
                bytes = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
                main_assembly = ($_.Name -eq $app.MainAssembly)
            }
        } |
        Export-Csv -LiteralPath (Join-Path $annotations 'managed_binary_hashes.csv') -NoTypeInformation -Encoding UTF8

    $nativeRows = foreach ($reportFile in Get-ChildItem -LiteralPath (Join-Path $root '04_native\reports') -Filter '*.json' -File | Sort-Object Name) {
        $report = Get-Content -LiteralPath $reportFile.FullName -Raw | ConvertFrom-Json
        $binary = Join-Path $root "04_native\binaries\$($report.library)"
        $database = Join-Path $root "04_native\ida_databases\$([System.IO.Path]::GetFileNameWithoutExtension($report.library)).i64"
        $validation = Join-Path $root "logs\ida_validation\$([System.IO.Path]::GetFileNameWithoutExtension($report.library)).json"
        $validationRecord = Get-Content -LiteralPath $validation -Raw | ConvertFrom-Json
        [pscustomobject]@{
            library = $report.library
            sha256 = (Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash
            bytes = (Get-Item -LiteralPath $binary).Length
            functions = $report.function_count
            exports = $report.export_count
            tagged_strings = $report.tagged_string_count
            all_functions_commented = $validationRecord.all_functions_commented
            ida_database = Get-RelativePath -Base $root -Path $database
            role = $report.role
        }
    }
    $nativeRows | Export-Csv -LiteralPath (Join-Path $annotations 'native_index.csv') -NoTypeInformation -Encoding UTF8

    $provenance = Get-Content -LiteralPath (Join-Path $root '00_original_package\provenance.json') -Raw | ConvertFrom-Json
    $mainBinary = Get-ChildItem -LiteralPath $binaryRoot -Filter $app.MainAssembly -File -Recurse | Select-Object -First 1
    $artifactRows = @(
        [pscustomobject]@{
            artifact = 'preserved APK'
            relative_path = $provenance.preserved_file
            bytes = $provenance.bytes
            sha256 = $provenance.sha256
        },
        [pscustomobject]@{
            artifact = 'main managed assembly'
            relative_path = Get-RelativePath -Base $root -Path $mainBinary.FullName
            bytes = $mainBinary.Length
            sha256 = (Get-FileHash -LiteralPath $mainBinary.FullName -Algorithm SHA256).Hash
        }
    )
    $artifactRows | Export-Csv -LiteralPath (Join-Path $annotations 'artifact_provenance.csv') -NoTypeInformation -Encoding UTF8
}

Write-Host "Generated documentation indexes for $($Apps.Count) apps."
