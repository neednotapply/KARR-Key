# Architecture map

## Execution model

```text
Android MainActivity
  -> Xamarin.Forms + camera/ZXing + BLE initialization
  -> FRMSWTBLEInstaller.App
  -> NavigationPage(Login)
  -> technician summary/schedule
  -> VehicleDetails workflow selection
       -> install / repair / replacement / removal / battery removal
       -> serial, VIN, stock, account and product capture
  -> SelectUnit / Bluetooth
  -> SelectVehicleCan / CanSettings / CanFeatures
  -> InstallationChecks
  -> InstallationComplete or PushOffline
  -> server fitment APIs and BLE unit protocol
```

`Droid/MainActivity.cs:23` initializes the platform.
`App.cs:88` and `App.cs:101` are the two constructors; both establish shared
state and route to login (`App.cs:97` or `126`).

## Main managed components

| Component | Role |
|---|---|
| `FRMSWTBLEInstaller.dll` | Installer UI, fitment orchestration, BLE, CAN, offline queue |
| `WebhouseStandard.dll` | Shared API/data contracts and helpers |
| `Validation.dll` | Input validation |
| `FastAndroidCamera.dll` | Camera capture integration |
| `ZXingNetMobile.dll` | Barcode/QR scanning integration |
| `FormsViewGroup.dll` | Xamarin.Forms Android view helper |
| `Plugin.BLE.dll` | BLE abstraction |
| `SQLite*` managed/native dependencies | Shipped persistence dependencies |

The app ships both `libe_sqlite3.so` and `libsqlite3_xamarin.so`. No direct
first-party `SQLite` references were found in the recovered
`FRMSWTBLEInstaller` or `WebhouseStandard` C# source, so these may be transitive
or dormant dependencies. Their presence alone is not evidence of a specific
active database schema.

## State and offline work

`Shared`, `App`, `TransparentData`, and large page code-behind classes carry
global workflow state. `SettingsSave` persists username, password, user token,
device token, current user, and account through Plugin.Settings.

Offline fitment data and later push paths are present. A master app should use
a versioned encrypted job database with immutable job identity, explicit step
state, validation evidence, idempotency key, and server conflict handling.

## Network

The app uses Webhouse API helpers plus app-specific logic in `Shared.cs`.
Observed service families include users/access, accounts, products/serials,
vehicles/VIN, CAN support/files, technicians/schedules, fitments/tests,
enumerators/consumables, alarm events, firmware, and location.

Use `api_route_index.csv` as the literal route inventory.

## Android surface

The manifest declares Internet, legacy and Android 12+ BLE permissions,
camera/flashlight, coarse/fine location, vibration, and network state. It
includes a ZXing scanner activity and a keep-alive service and permits Android
backup.
