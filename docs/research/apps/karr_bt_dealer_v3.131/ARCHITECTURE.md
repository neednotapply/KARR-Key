# Architecture map

## Execution model

KARR BT Dealer is a Xamarin.Forms Android application:

```text
Android MainActivity
  -> Xamarin.Forms initialization
  -> FRMBLEDealerApp.App
  -> NavigationPage(Login)
  -> account/dealer selection
  -> download/synchronization
  -> Bluetooth scan or vehicle/map workflow
  -> UnitSettings / UnitSettingsRefreshed
  -> Shared and WebhouseStandard API/data services
  -> Plugin.BLE transparent UART
```

The business logic is managed C#. The ARM64 libraries are Xamarin/Mono runtime,
JNI, and TLS support.

## Startup

- `FRMBLEDealerApp/Droid/MainActivity.cs:28` initializes the Android side.
- `FRMBLEDealerApp/App.cs:120` constructs the application, Plugin.BLE adapter,
  shared collections, connectivity/lifecycle handlers, and root navigation.
- `App.cs:139` selects `NavigationPage(new Login())`.
- Later lifecycle code manages background data retrieval, favorite/current
  device reconnect behavior, and the transparent-data characteristic.

## Main components

| Component | Role |
|---|---|
| `FRMBLEDealerApp.dll` | Pages, lifecycle, dealer workflows, BLE orchestration, offline queues |
| `WebhouseStandard.dll` | Shared data contracts, API helpers, device keys, protocol parser |
| `Validation.dll` | Validation helper code |
| `CsvReader.dll` | CSV-oriented data helper |
| `FormsViewGroup.dll` | Xamarin.Forms Android view helper |
| `Plugin.BLE.dll` | BLE abstraction |
| Xamarin/Essentials/Firebase assemblies | UI, platform services, preferences, push |

## State

The app relies heavily on static state in `App`, `Shared`, `TransparentData`,
and page classes. `SettingsSave` wraps Xamarin.Essentials `Preferences`.
Device authentication keys fetched from authenticated APIs are stored using
Xamarin.Essentials `SecureStorage`.

For a master app, replace selected account, selected vehicle, current BLE
device, and current workflow globals with scoped state objects and explicit
session/job lifetimes.

## Network

`Shared.cs` owns much of the app-specific dealer orchestration. The recovered
`WebhouseStandard/Webhouse/Data` types provide typed access to accounts,
vehicles, CAN data, fitments, products, device security, and related services.
The literal route inventory is `api_route_index.csv`.

## Platform surface

The manifest declares Internet, legacy and Android 12+ BLE permissions,
camera/flashlight, foreground and background location, wake lock, network
state, notifications, Firebase messaging, and a background retrieval service.
The application currently allows Android backup.

## Reading paths

- Dealer transaction: page -> `Shared.cs` -> API route.
- Device command: page -> `TransparentData.cs` -> `QtProtocolParser` ->
  characteristic write/notification.
- Device authentication: unit page -> `AuthService` -> `DeviceData` ->
  `SecureStorage`.
- Firmware: app/unit page -> remote metadata/artifact retrieval -> bootload
  commands -> response validation.
- Native startup: Java/smali stub -> `libxamarin-app` -> `libmonodroid` -> Mono.
