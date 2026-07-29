# Architecture map

## Execution model

```text
Android MainActivity
  -> Xamarin.Forms
  -> SWATDealer.App
  -> NavigationPage(LoginPage)
  -> MainPage
       -> MasterPage drawer
       -> MenuList
            -> BatteryStatus
            -> ListAllVehicles
            -> VehiclesSearch
  -> SWATDealer.Shared
  -> authenticated HTTPS/WebRequest/WebClient
  -> dealer vehicle API
```

`Droid/MainActivity.cs:14` initializes the platform. `App.cs:20` constructs the
application and `App.cs:26` routes to login. Successful login replaces the root
with `MainPage`.

## Main managed components

| Component | Role |
|---|---|
| `SWATDealer.dll` | Login, drawer/menu, search, vehicle list/map, battery/status |
| `WebhouseStandard.dll` | Shared account/login/data helper library |
| `Validation.dll` | Validation helper |
| `FormsViewGroup.dll` | Xamarin.Forms Android view helper |
| Xamarin.Forms/Maps/Google Maps | UI and map rendering |
| AppCenter assemblies | Analytics/crash reporting |

The bundled `WebhouseStandard` contains more data/API types than the SWAT DL
main app calls. Treat its routes as shared-library inventory, not proof of
reachable features.

## State and storage

`Shared.cs` carries static login token, account/user type, dealer-access flag,
search criteria, map bounds, selected vehicle, status totals, and user
location. `SettingsSave` uses Plugin.Settings for username, password, device
token, and current-user values.

## Network boundary

The main application uses an older synchronous `WebRequest`/`WebClient` stack
for user type and vehicle lists. Search parameters are concatenated into query
strings. Vehicle JSON is mapped into `CarPlacement` values while calculating
map bounds and status totals.

## Platform surface

The manifest declares Internet, network state, coarse/fine location, legacy
external storage write, and Google services access. It does not declare
Bluetooth permissions. It permits Android backup.

## Native boundary

All locator logic is managed. The native libraries are Xamarin/Mono, JNI, and
TLS support. There is no SWAT-specific native vehicle protocol.
