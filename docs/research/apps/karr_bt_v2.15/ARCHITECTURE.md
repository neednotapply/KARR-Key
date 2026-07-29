# Architecture map

## Execution model

```text
Android MainActivity
  -> Xamarin.Forms
  -> QTAP.App
  -> Login
  -> customer vehicle/account state
  -> MasterPage / StartPage
       -> local BLE controls
       -> maps, logs, alerts, config, geofences
       -> remote request/diagnostic APIs
  -> Shared server helper
  -> Plugin.BLE transparent UART
```

`Droid/MainActivity.cs:17` initializes the platform. `App.cs:73` constructs
the shared adapter/state, and `App.cs:88` selects the login page.

## Main managed components

| Component | Role |
|---|---|
| `QTAP.dll` | Customer UI, global state, API calls, BLE protocol and controls |
| `FormsViewGroup.dll` | Xamarin.Forms Android view helper |
| `Plugin.BLE.dll` | BLE abstraction |
| Xamarin.Forms/Essentials | UI and platform services |
| Firebase/Plugin.FirebasePushNotification | Push messaging |
| Map libraries | Vehicle, geofence, and location presentation |

## State and storage

The client holds current user, account, vehicle, requests, and connected-device
state in static fields spread across `App`, `Shared`, and pages. `SettingsSave`
wraps Plugin.Settings preferences, including a password-like value and selected
state.

The master app should use a session-scoped state store and never persist the
password. Favorite-device reconnect should reference an authorized vehicle/unit
identity, not only a BLE identifier.

## Server behavior

`Shared.cs` performs login/access/user operations, customer-vehicle lookup,
BLE server authentication, remote diagnostic/request acknowledgement, alerts,
geofences, configuration, logs, violations, and location uploads. It uses an
older `WebRequest`-style network stack with string-concatenated URLs.

`api_route_index.csv` lists the literal route paths without query values.

## Android surface

The app targets Android 30 and declares legacy Bluetooth permissions, location,
Internet, wake lock, network state, and push messaging. It predates Android
12's `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` permission model. The manifest allows
backup.

## Business-logic boundary

All KARR behavior is managed C#. The six native libraries are Xamarin/Mono,
JNI, and TLS runtime support. Enter IDA only for those boundaries.
