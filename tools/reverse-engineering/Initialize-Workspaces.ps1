param(
    [ValidateSet('all', 'extract', 'managed')]
    [string]$Phase = 'all'
)

$ErrorActionPreference = 'Stop'

$CollectionRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$PlaygroundRoot = [System.IO.Path]::GetFullPath((Join-Path $CollectionRoot '..'))
$PriorKarrRoot = Join-Path $PlaygroundRoot 'karr_xapk_compare'
$Jadx = Join-Path $PlaygroundRoot '.tools\jadx-1.5.6\bin\jadx.bat'
$IlSpy = Join-Path $PriorKarrRoot 'tools\ilspycmd9\ilspycmd.exe'
$PyXamStore = Join-Path $PriorKarrRoot 'tools\pyxamstore'
$PyDependencies = Join-Path $PriorKarrRoot 'tools\pydeps_rebuild'
$Python = 'C:\Program Files\Python310\python.exe'

$Apps = @(
    [pscustomobject]@{
        Slug = 'karr_bt_dealer_v3.131'
        DisplayName = 'KARR BT Dealer'
        Version = '3.131'
        Package = 'com.whgos.frmbledealerapp'
        Source = 'C:\Users\mrnic\Downloads\KARR+BT+Dealer_3.131_APKPure.apk'
    },
    [pscustomobject]@{
        Slug = 'karr_bt_v2.15'
        DisplayName = 'KARR BT'
        Version = '2.15'
        Package = 'com.whgos.qtap'
        Source = 'C:\Users\mrnic\Downloads\KARR+BT_2.15_APKPure.apk'
    },
    [pscustomobject]@{
        Slug = 'karr_installer_v1.39'
        DisplayName = 'KARR Installer'
        Version = '1.39'
        Package = 'com.whgos.frmswtbleinstaller'
        Source = 'C:\Users\mrnic\Downloads\KARR+Installer_1.39_APKPure.apk'
    },
    [pscustomobject]@{
        Slug = 'karr_swat_dl_v2.05'
        DisplayName = 'KARR SWAT DL'
        Version = '2.05'
        Package = 'com.WHGOS.swat_dealer'
        Source = 'C:\Users\mrnic\Downloads\KARR+SWAT+DL_2.05_APKPure.apk'
    }
)

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock]$Command,
        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE"
    }
}

function New-WorkspaceDirectories {
    param([string]$Root)

    @(
        '00_original_package',
        '01_managed\binaries',
        '01_managed\projects',
        '01_managed\store_metadata',
        '01_managed\diagrammer',
        '02_java_jadx',
        '03_apktool_smali',
        '04_native\binaries',
        '04_native\ida_databases',
        '04_native\reports',
        '05_annotations',
        'logs'
    ) | ForEach-Object {
        New-Item -ItemType Directory -Force -Path (Join-Path $Root $_) | Out-Null
    }
}

function Initialize-AppExtraction {
    param([pscustomobject]$App)

    if (-not (Test-Path -LiteralPath $App.Source -PathType Leaf)) {
        throw "Missing input APK: $($App.Source)"
    }

    $root = Join-Path $CollectionRoot $App.Slug
    New-WorkspaceDirectories -Root $root

    $originalName = [System.IO.Path]::GetFileName($App.Source)
    $preservedApk = Join-Path $root "00_original_package\$originalName"
    if (-not (Test-Path -LiteralPath $preservedApk -PathType Leaf)) {
        Copy-Item -LiteralPath $App.Source -Destination $preservedApk
    }

    $sourceHash = (Get-FileHash -LiteralPath $App.Source -Algorithm SHA256).Hash
    $copyHash = (Get-FileHash -LiteralPath $preservedApk -Algorithm SHA256).Hash
    if ($sourceHash -ne $copyHash) {
        throw "Preserved APK hash mismatch for $($App.DisplayName)"
    }

    [pscustomobject]@{
        display_name = $App.DisplayName
        version = $App.Version
        package = $App.Package
        source_file = $App.Source
        preserved_file = "00_original_package\$originalName"
        bytes = (Get-Item -LiteralPath $preservedApk).Length
        sha256 = $copyHash
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root '00_original_package\provenance.json') -Encoding UTF8

    $apktoolRoot = Join-Path $root '03_apktool_smali'
    if (-not (Test-Path -LiteralPath (Join-Path $apktoolRoot 'apktool.yml') -PathType Leaf)) {
        Write-Host "[$($App.Slug)] Apktool decode"
        Invoke-Checked -Description "Apktool decode for $($App.DisplayName)" -Command {
            & apktool d --force --output $apktoolRoot $preservedApk
        }
    }

    $jadxRoot = Join-Path $root '02_java_jadx'
    if (-not (Test-Path -LiteralPath (Join-Path $jadxRoot 'sources') -PathType Container)) {
        Write-Host "[$($App.Slug)] JADX reconstruction"
        & $Jadx --show-bad-code --output-dir $jadxRoot $preservedApk
        $jadxExitCode = $LASTEXITCODE
        if ($jadxExitCode -notin @(0, 1)) {
            throw "JADX for $($App.DisplayName) failed with exit code $jadxExitCode"
        }
        [pscustomobject]@{
            exit_code = $jadxExitCode
            complete_without_reported_errors = ($jadxExitCode -eq 0)
            note = 'Exit code 1 means JADX emitted usable output with reconstruction errors; exact smali is retained as the fallback.'
        } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'logs\jadx_status.json') -Encoding UTF8
    }

    $assemblyStoreRoot = Join-Path $apktoolRoot 'unknown\assemblies'
    if (-not (Test-Path -LiteralPath (Join-Path $assemblyStoreRoot 'assemblies.blob') -PathType Leaf)) {
        throw "Xamarin assembly store was not recovered for $($App.DisplayName)"
    }

    $managedRoot = Join-Path $root '01_managed'
    $binaryRoot = Join-Path $managedRoot 'binaries'
    if (-not (Get-ChildItem -LiteralPath $binaryRoot -Filter '*.dll' -File -ErrorAction SilentlyContinue)) {
        Write-Host "[$($App.Slug)] Xamarin assembly-store extraction"
        $extractRoot = Join-Path $managedRoot 'store_extract_work'
        New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null
        $oldPythonPath = $env:PYTHONPATH
        try {
            $env:PYTHONPATH = "$PyXamStore;$PyDependencies"
            Push-Location $extractRoot
            Invoke-Checked -Description "pyxamstore extraction for $($App.DisplayName)" -Command {
                & $Python -m pyxamstore.explorer unpack --dir $assemblyStoreRoot --arch arm64 --force
            }
            Pop-Location
        }
        finally {
            if ((Get-Location).Path -eq $extractRoot) {
                Pop-Location
            }
            $env:PYTHONPATH = $oldPythonPath
        }

        $storeOutput = Join-Path $extractRoot 'out'
        Copy-Item -Path (Join-Path $storeOutput '*') -Destination $binaryRoot -Recurse
        Copy-Item -LiteralPath (Join-Path $storeOutput '..\assemblies.json') -Destination (Join-Path $managedRoot 'store_metadata') -ErrorAction SilentlyContinue
    }

    $nativeInput = Join-Path $apktoolRoot 'lib\arm64-v8a'
    if (-not (Test-Path -LiteralPath $nativeInput -PathType Container)) {
        throw "ARM64 native directory was not decoded for $($App.DisplayName)"
    }
    Get-ChildItem -LiteralPath $nativeInput -Filter '*.so' -File |
        ForEach-Object {
            $destination = Join-Path $root "04_native\binaries\$($_.Name)"
            if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
                Copy-Item -LiteralPath $_.FullName -Destination $destination
            }
        }

    $summary = [pscustomobject]@{
        app = $App.DisplayName
        version = $App.Version
        package = $App.Package
        managed_assemblies = @(Get-ChildItem -LiteralPath $binaryRoot -Filter '*.dll' -File -Recurse).Count
        jadx_java_files = @(Get-ChildItem -LiteralPath (Join-Path $jadxRoot 'sources') -Filter '*.java' -File -Recurse).Count
        smali_files = @(Get-ChildItem -LiteralPath $apktoolRoot -Filter '*.smali' -File -Recurse).Count
        native_arm64_libraries = @(Get-ChildItem -LiteralPath (Join-Path $root '04_native\binaries') -Filter '*.so' -File).Count
        apk_sha256 = $sourceHash
    }
    $summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'logs\extraction_summary.json') -Encoding UTF8
}

function Export-ManagedProjects {
    param([pscustomobject]$App)

    $root = Join-Path $CollectionRoot $App.Slug
    $binaryRoot = Join-Path $root '01_managed\binaries'
    $projectRoot = Join-Path $root '01_managed\projects'
    $logPath = Join-Path $root 'logs\managed_decompile.log'

    if (-not (Test-Path -LiteralPath $binaryRoot -PathType Container)) {
        throw "Run extraction first for $($App.DisplayName)"
    }

    $failures = [System.Collections.Generic.List[string]]::new()
    $assemblies = Get-ChildItem -LiteralPath $binaryRoot -Filter '*.dll' -File -Recurse | Sort-Object FullName
    foreach ($assembly in $assemblies) {
        $relativeAssembly = $assembly.FullName.Substring($binaryRoot.Length + 1)
        $projectName = [System.IO.Path]::ChangeExtension($relativeAssembly, $null) -replace '[\\/]', '__'
        $destination = Join-Path $projectRoot $projectName
        if (Test-Path -LiteralPath $destination -PathType Container) {
            continue
        }
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        Write-Host "[$($App.Slug)] ILSpy $($assembly.Name)"
        & $IlSpy --disable-updatecheck --nested-directories --project --referencepath $binaryRoot --outputdir $destination $assembly.FullName *>> $logPath
        if ($LASTEXITCODE -ne 0) {
            $failures.Add($assembly.Name)
        }
    }

    [pscustomobject]@{
        assembly_count = $assemblies.Count
        completed_project_directories = @(Get-ChildItem -LiteralPath $projectRoot -Directory).Count
        failures = @($failures)
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'logs\managed_decompile_status.json') -Encoding UTF8

    if ($failures.Count -gt 0) {
        throw "ILSpy failed for $($App.DisplayName): $($failures -join ', ')"
    }
}

if (-not (Test-Path -LiteralPath $Jadx -PathType Leaf)) {
    throw "JADX was not found at $Jadx"
}
if (-not (Test-Path -LiteralPath $IlSpy -PathType Leaf)) {
    throw "ILSpy was not found at $IlSpy"
}
if (-not (Test-Path -LiteralPath $PyXamStore -PathType Container)) {
    throw "pyxamstore was not found at $PyXamStore"
}
if (-not (Test-Path -LiteralPath $PyDependencies -PathType Container)) {
    throw "pyxamstore dependencies were not found at $PyDependencies"
}
if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) {
    throw "Python 3.10 was not found at $Python"
}

foreach ($app in $Apps) {
    if ($Phase -in @('all', 'extract')) {
        Initialize-AppExtraction -App $app
    }
    if ($Phase -in @('all', 'managed')) {
        Export-ManagedProjects -App $app
    }
}

Write-Host "Completed phase '$Phase' for $($Apps.Count) apps."
