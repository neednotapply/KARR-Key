# KARR BT Dealer 3.131

- Package: `com.whgos.frmbledealerapp`
- Version code: 183
Android SDK: min 21, target 34

This is the broad dealer-operations client and the newest BLE implementation
in the collection. It combines account/inventory operations, local unit
settings and firmware, sale/unwind/booking flows, mapping/location, offline
queues, background retrieval, and push notifications.

## Reading order

1. `05_annotations/ARCHITECTURE.md`
2. `05_annotations/FEATURE_MAP.md`
3. `05_annotations/BLE_PROTOCOL.md`
4. `01_managed/projects/FRMBLEDealerApp/FRMBLEDealerApp/App.cs`
5. `01_managed/projects/FRMBLEDealerApp/FRMBLEDealerApp/Shared.cs`
6. `01_managed/projects/FRMBLEDealerApp/FRMBLEDealerApp/Pages/UnitSettingsRefreshed.cs`
7. `01_managed/projects/WebhouseStandard/Quantam/Protocol`
8. `05_annotations/SECURITY_AND_MASTER_APP_NOTES.md`
9. `05_annotations/IDA_GUIDE.md`

The interactive assembly diagram is `01_managed/diagrammer/index.html`.

## Layers

- `00_original_package`: untouched APK and provenance.
- `01_managed/binaries`: 190 recovered managed assemblies.
- `01_managed/projects`: 190 ILSpy C# projects.
- `02_java_jadx`: generated Android/Java bridge reconstruction.
- `03_apktool_smali`: exact resources, manifest, and smali.
- `04_native`: six ARM64 libraries and annotated IDA databases.
- `05_annotations`: behavioral guides and machine-readable indexes.

See `05_annotations/COMPLETENESS.md` for limits and validation.
