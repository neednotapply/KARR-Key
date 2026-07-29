# KARR SWAT DL 2.05 known data

## Artifact identity

- Original package: `KARR+SWAT+DL_2.05_APKPure.apk`
- Role: read-only dealer vehicle search, map, and battery/status client
- Framework: Xamarin.Forms for Android
- Native coverage: 6 IDA 9.1 databases, 12,947 commented functions
- Package and database hashes: `manifest.json`

The APK is unchanged. The databases preserve native/runtime analysis and the
explicit finding that the application contains no BLE/unit protocol.

## Framework and architecture

The startup path is Android `MainActivity` -> Xamarin.Forms ->
`SWATDealer.App` -> `LoginPage` -> `MainPage`/drawer -> battery status,
vehicle list, or vehicle search. `SWATDealer.Shared` performs authenticated
dealer vehicle requests and maps JSON results into list/map models.

Primary managed components:

| Component | Responsibility |
|---|---|
| `SWATDealer.dll` | Login, menu, search, vehicle list/map, battery/status |
| `WebhouseStandard.dll` | Shared login/account/data helper library |
| `Validation.dll` | Validation helper |
| Xamarin maps/Google Maps | Map rendering |
| AppCenter assemblies | Analytics and crash reporting |

`Shared.cs` holds token, account/user type, dealer scope, search criteria, map
bounds, selected vehicle, totals, and location in static state. `SettingsSave`
persisted username, password, device token, and current user.

## Managed-code entrypoints

- `SWATDealer/Droid/MainActivity.cs` initializes Android.
- `SWATDealer/App.cs` creates login navigation.
- `LoginPage` and `Shared.DoLogin` establish the session.
- `Shared.LoadUserType` retrieves account/user/dealer-access context.
- `VehiclesSearch`, `ListAllVehicles`, and `BatteryStatus` own the visible
  locator workflows.
- `SWATDealer/Shared.cs` contains app-owned network behavior.

## Features and routes

Observed features include dealer login/account context, active vehicle
retrieval, VIN/stock/make/model/color/year filters, local versus other-dealer
scope, map/list presentation, bounds and distance calculation, vehicle detail,
and battery/status totals.

The app-owned network surface is deliberately small:

- user type and current-account context;
- active vehicles with restriction/search parameters;
- explicit VIN/stock vehicle search.

The previous generated route index contained 57 rows because it also indexed
the bundled `WebhouseStandard` library. Those extra user/account/vehicle/
fitment/CAN/log/decode/control-room literals do not prove the SWAT UI calls
them.

The result mapper normalizes coordinates, expands map bounds, builds
stock/VIN display text, creates markers for non-zero coordinates, and updates
status totals. The legacy implementation used synchronous
`WebRequest`/`WebClient`, locale-sensitive parsing, and query-string filters.

## BLE and protocol

SWAT DL declares no Bluetooth permissions and contains no BLE adapter, scan,
GATT UUID, transparent characteristic, authentication challenge, packet codec,
CAN transfer, firmware transfer, or local vehicle control.

Adding KARR Master's BLE modules must never make a locator result sufficient
authority to connect to or control a vehicle.

## Authentication boundary

Login provides server token/account/user/dealer-scope state for read-only
vehicle queries. Dealer scope must be enforced by the server. Search results
are not device authorization, ownership proof, or permission for mutation.

Account-scoped caches must expire on logout, role change, or account switch.
Push/telemetry data and client-side flags cannot widen dealer scope.

## Native libraries and IDA reading order

| Database | Functions | Role |
|---|---:|---|
| `libxamarin-app.i64` | 1 | App/runtime configuration |
| `libxa-internal-api.i64` | 249 | Xamarin.Android internal API |
| `libmonodroid.i64` | 605 | Android/JNI to Mono bridge |
| `libmono-native.i64` | 392 | Mono platform support |
| `libmono-btls-shared.i64` | 3,838 | Native TLS/cryptography |
| `libmonosgen-2.0.i64` | 7,862 | Mono runtime and garbage collector |

All locator behavior is managed. Use IDA for Xamarin/JNI/TLS/runtime questions,
not as the starting point for search or map logic.

## Confirmed security findings

- A credential-like API value was shipped in managed code.
- A password-like value was stored in ordinary preferences.
- Token, account, role, search, map, and selected-vehicle state was global.
- Search and location values were concatenated into URLs.
- Broad empty catches hid authentication, parsing, and network failures.
- Analytics/crash tooling could receive operational context.
- Android backup and obsolete external-storage permission were enabled.

Rotate/remove client credentials, never persist passwords, use
Keystore-backed refresh state, clear scoped caches, use typed errors and
cancellable async clients, move PII/location out of URLs where supported, and
exclude VIN/stock/location/user/account/token data from telemetry.

## Limitations and unresolved questions

- Shared-library route presence is not proof of reachable SWAT behavior.
- Current pagination, server filtering, role vocabulary, and API shape require
  live authorized documentation.
- Invalid/missing coordinate and large-fleet behavior need explicit policy.
- IDA covers runtime libraries; application behavior is managed C# in the
  external archive.
