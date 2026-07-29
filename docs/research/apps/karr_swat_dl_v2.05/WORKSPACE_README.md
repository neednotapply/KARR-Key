# KARR SWAT DL 2.05

- Package: `com.WHGOS.swat_dealer`
- Version code: 12
Android SDK: min 21, target 33

This is a dealer vehicle-locator client. It authenticates to the server,
retrieves active/search vehicle data, filters dealer inventory, and presents
map/list/battery-status views. It has no BLE discovery, GATT service, unit
authentication, or local vehicle-control path.

## Reading order

1. `05_annotations/ARCHITECTURE.md`
2. `05_annotations/FEATURE_MAP.md`
3. `05_annotations/NETWORK_DATA_FLOW.md`
4. `01_managed/projects/SWATDealer/SWATDealer/App.cs`
5. `.../LoginPage.cs`
6. `.../MenuList.cs`
7. `.../ListAllVehicles.cs`
8. `.../VehiclesSearch.cs`
9. `.../BatteryStatus.cs`
10. `.../Shared.cs`

The workspace contains 70 managed C# projects, JADX Java, exact smali, and six
annotated ARM64 IDA databases.
