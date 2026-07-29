# KARR Installer 1.39

- Package: `com.whgos.frmswtbleinstaller`
- Version code: 143
Android SDK: min 21, target 33

This is the technician/installer client. It covers scheduled work, vehicle and
unit selection, VIN/barcode capture, CAN support and transfer, installation
checks, fitment completion, repair/replacement/removal, and offline submission.

## Reading order

1. `05_annotations/ARCHITECTURE.md`
2. `05_annotations/FEATURE_MAP.md`
3. `05_annotations/BLE_PROTOCOL.md`
4. `01_managed/projects/FRMSWTBLEInstaller/FRMSWTBLEInstaller/App.cs`
5. `.../VehicleDetails.cs`
6. `.../InstallationChecks.cs`
7. `.../SelectVehicleCan.cs`
8. `.../Shared.cs`
9. `05_annotations/SECURITY_AND_MASTER_APP_NOTES.md`
10. `05_annotations/IDA_GUIDE.md`

The workspace contains 196 managed C# projects, JADX Java, exact smali, and
eight annotated ARM64 IDA databases. The two extra native databases are SQLite
engines shipped by this APK.
