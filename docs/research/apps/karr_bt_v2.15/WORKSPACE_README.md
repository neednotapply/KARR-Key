# KARR BT 2.15

- Package: `com.whgos.qtap`
- Version code: 69
Android SDK: min 21, target 30

This is the customer-facing KARR client. It combines account/vehicle
selection, direct BLE controls, alerts, configuration, logs/violations,
geofences, maps, user management, and remote diagnostic/request views.

## Reading order

1. `05_annotations/ARCHITECTURE.md`
2. `05_annotations/FEATURE_MAP.md`
3. `05_annotations/BLE_PROTOCOL.md`
4. `01_managed/projects/QTAP/QTAP/App.cs`
5. `01_managed/projects/QTAP/QTAP/StartPage.cs`
6. `01_managed/projects/QTAP/QTAP/Shared.cs`
7. `01_managed/projects/QTAP/QTAP/TransparentData.cs`
8. `05_annotations/SECURITY_AND_MASTER_APP_NOTES.md`
9. `05_annotations/IDA_GUIDE.md`

The interactive assembly diagram is `01_managed/diagrammer/index.html`.

The workspace includes 80 managed C# projects, JADX Java, exact smali, and six
annotated ARM64 IDA databases. See `05_annotations/COMPLETENESS.md`.
