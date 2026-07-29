# KARR BT 2.15 known data

## Artifact identity

- Original package: `KARR+BT_2.15_APKPure.apk`
- Role: customer account, vehicle control, alert, map, and log client
- Framework: Xamarin.Forms for Android, targeting the Android 30 era
- Native coverage: 6 IDA 9.1 databases, 13,085 commented functions
- Package and database hashes: `manifest.json`

The APK is unchanged. The databases preserve native/runtime analysis and the
repository research note; KARR business logic is in the reconstructed managed
projects retained in the external archive.

## Framework and architecture

The startup path is Android `MainActivity` -> Xamarin.Forms -> `QTAP.App` ->
`Login` -> customer account/vehicle state -> `MasterPage`/`StartPage`.
`Shared` owns server behavior and Plugin.BLE provides the transparent-UART
transport.

Primary managed components:

| Component | Responsibility |
|---|---|
| `QTAP.dll` | Customer UI, state, API calls, BLE protocol, and controls |
| `Plugin.BLE.dll` | BLE abstraction |
| Xamarin.Forms/Essentials | UI and platform services |
| Firebase push assemblies | Push messaging |
| Map libraries | Vehicle, geofence, and location presentation |

Current user, account, vehicle, requests, and connected-device state are held
in static fields across `App`, `Shared`, and pages. `SettingsSave` persists
selected state and a password-like value using ordinary preferences.

## Managed-code entrypoints

- `QTAP/Droid/MainActivity.cs` initializes Android.
- `QTAP/App.cs` creates shared state and login navigation.
- `QTAP/Shared.cs` handles account, vehicle, alert, map, log, and remote APIs.
- `QTAP/StartPage.cs` is the central customer control page.
- `QTAP/TransparentData.cs` owns BLE notification and characteristic access.
- `QTAP/QT_Protocol.cs` implements legacy packet framing.
- `QTAP/QTAuth.cs` implements the recovered legacy challenge transform.

## Features and routes

Customer surfaces include login and verification, users, vehicles, BLE scan,
settings/diagnostics, alerts, configuration, geofences, maps, logs,
violations, instructions, help, and contact.

Direct vehicle paths include lock/arm, unlock/disarm, silent variants, trunk,
valet/return-valet, immobilizer state, remote learning, and local diagnostic
feedback. Server-backed behavior includes alerts, configuration, locations,
geofences, logs, violations, remote diagnostics, and request history.

The literal route inventory contained 34 normalized occurrences:

| Family | Count | Observed use |
|---|---:|---|
| vehicles | 12 | Vehicles, requests, diagnostics, and configuration |
| logs | 7 | Violations and location/log persistence |
| users | 6 | Login, access, and user details |
| alerts | 3 | Alerts and configuration |
| accounts | 2 | Current account and verification |
| mapwaypoints | 2 | Geofence/waypoint behavior |
| ncompass | 1 | Server-assisted BLE authentication |
| vehiclecansupport | 1 | CAN support lookup |

Some URLs were assembled across variables. These literals are not a current
API specification and deliberately omit query values.

## BLE and protocol

- Service: `49535343-FE7D-4AE5-8FA9-9FAFD205E455`
- Characteristic: `49535343-1E4D-4BD9-BA61-23C647249616`

The legacy packet is:

```text
0xAA | length_hi | length_lo | command | payload... | checksum
```

Length covers command plus payload. The additive two's-complement checksum
makes the covered bytes sum to zero modulo 256. This client does not implement
Dealer's counter `0xBB` or encrypted `0x55` wrappers.

Commands cover settings, state, VIN, serial, battery, CAN, vehicle controls,
authentication, bootload, violations, doors, movement, remotes, repair mode,
reset/default, acknowledgements, and errors. Payload meaning must be taken from
the individual callers.

## Authentication boundary

The recovered flow is:

```text
RequestAuth(14) -> Read_Mode(34) -> ProvideAuth(15) -> AuthSuccess(16)
```

`QTAuth.cs` contains static legacy customer/dealer material. Exact values are
not retained here and must not be copied into a new client.

The Pixel capture reached UART subscription, received Original-mode command
`0x0E` with an 8-byte challenge, and was later disconnected by the peer with
GATT status 19 because the challenge remained unanswered. Subscription proves
transport only. A production client requires current server-authorized,
per-device authentication and invalidates authenticated state on disconnect.

## Native libraries and IDA reading order

| Database | Functions | Role |
|---|---:|---|
| `libxamarin-app.i64` | 0 | Generated app configuration/data image |
| `libxa-internal-api.i64` | 249 | Xamarin.Android internal API |
| `libmonodroid.i64` | 748 | Android/JNI to Mono bridge |
| `libmono-native.i64` | 392 | Mono platform support |
| `libmono-btls-shared.i64` | 3,838 | Native TLS/cryptography |
| `libmonosgen-2.0.i64` | 7,858 | Mono runtime and garbage collector |

`libxamarin-app` has no IDA-discovered function in this build but retains
valid configuration data and analysis. Start in the `QTAP` C# project for
application behavior.

## Confirmed security findings

- Static legacy authentication keys and API/third-party token-like constants
  were shipped in managed code.
- A password-like value was stored in ordinary preferences.
- Older networking concatenated account, user, vehicle, and location data
  into URLs.
- The target predates Android 12 BLE runtime permissions.
- Android backup was allowed.

Do not persist passwords or static universal keys. Use Keystore-backed,
server-scoped credentials; modern permission handling; typed authenticated
requests; server-side vehicle/capability checks; and explicit confirmation for
safety-sensitive controls.

## Limitations and unresolved questions

- Recovered client behavior does not prove current API or account entitlement.
- Every command payload requires caller review and authorized golden captures.
- Favorite-device behavior must be bound to an authorized vehicle/unit, not a
  BLE address alone.
- IDA covers Xamarin/Mono/JNI/TLS boundaries; managed C# is the useful
  behavior reference in the external archive.
