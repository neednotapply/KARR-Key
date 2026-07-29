# KARR Installer 1.39 known data

## Artifact identity

- Original package: `KARR+Installer_1.39_APKPure.apk`
- Role: technician fitment, repair, replacement, removal, CAN, and validation
- Framework: Xamarin.Forms for Android
- Native coverage: 8 IDA 9.1 databases, 16,388 commented functions
- Package and database hashes: `manifest.json`

The APK is unchanged. The databases preserve all analyzed native libraries,
including the two bundled SQLite libraries, plus the repository research note.

## Framework and architecture

The startup path is Android `MainActivity` -> Xamarin.Forms/camera/ZXing/BLE
initialization -> `FRMSWTBLEInstaller.App` -> login -> technician summary ->
`VehicleDetails`. The selected job branches through unit selection, BLE, CAN,
installation checks, completion, repair/removal, or offline submission.

Primary managed components:

| Component | Responsibility |
|---|---|
| `FRMSWTBLEInstaller.dll` | Installer UI, fitment workflow, BLE, CAN, offline work |
| `WebhouseStandard.dll` | Shared API/data contracts |
| `Validation.dll` | Input validation |
| `FastAndroidCamera.dll`, `ZXingNetMobile.dll` | Camera and barcode/QR scanning |
| `Plugin.BLE.dll` | BLE abstraction |
| SQLite managed/native dependencies | Shipped persistence capabilities |

`Shared`, `App`, `TransparentData`, and large page classes carry global
workflow state. `SettingsSave` persisted username, password, tokens, current
user, and account through ordinary preferences.

## Managed-code entrypoints

- `FRMSWTBLEInstaller/Droid/MainActivity.cs` initializes Android.
- `FRMSWTBLEInstaller/App.cs` creates global services and login navigation.
- `VehicleDetails.cs` is the central job router.
- `TransparentData.cs` owns BLE command/notification state.
- `Settings/QT_Protocol.cs` implements the legacy packet.
- `Settings/QTAuth.cs` implements the legacy challenge transform.
- CAN settings/features, installation checks, completion, repair, removal,
  and `PushOffline` pages implement the field workflow.

## Features and routes

Recovered features include technician schedules, account/product/serial/VIN
selection, barcode/camera capture, vehicle/CAN selection, install/repair/
replacement/removal paths, validation tests, alarm/control-room events,
firmware/CAN transfers, completion, and offline push.

The literal route inventory contained 88 normalized occurrences:

| Family | Count | Observed use |
|---|---:|---|
| fitments | 31 | Install, repair, replacement, removal, validation |
| users | 15 | Login, access, and technician context |
| vehicles | 14 | Vehicle/unit search and job data |
| vehiclecansupport | 13 | CAN definitions, metadata, and files |
| accounts | 6 | Account context and verification |
| logs | 4 | Location and operational logging |
| controlroom | 2 | Alarm/test event submission |
| enumerators / decoders / stock | 3 | Reference values, VIN, and stock lookup |

Routes are recovered client literals, not authorization rules or a live API
contract. Follow request/response types and job steps when consulting the
external managed source.

## BLE and protocol

- Service: `49535343-FE7D-4AE5-8FA9-9FAFD205E455`
- Characteristic: `49535343-1E4D-4BD9-BA61-23C647249616`

Installer uses the same legacy packet as Customer:

```text
0xAA | length_hi | length_lo | command | payload... | checksum
```

It does not implement Dealer's `0xBB`/`0x55` wrappers in its main protocol
class. Installer callers emphasize authentication, unit identity/state,
settings, CAN transfer, bootload, installation tests, and authorized
repair/removal. The enum is not a payload schema.

## Authentication boundary

The managed sequence is:

```text
RequestAuth(14) -> Read_Mode(34) -> ProvideAuth(15) -> AuthSuccess(16)
```

`QTAuth.cs` contains static legacy material whose values are not retained here
and must not be copied. Optional Android bonding defaults off and does not
replace application authentication or technician-job scope.

The captured `0x0E` 8-byte challenge and subsequent peer GATT-status-19
disconnect match this unanswered legacy flow. Production use requires current
server authorization bound to technician, account, vehicle, unit, job, and
workflow step.

## Native libraries and IDA reading order

| Database | Functions | Role |
|---|---:|---|
| `libxamarin-app.i64` | 1 | App/runtime configuration |
| `libxa-internal-api.i64` | 249 | Xamarin.Android internal API |
| `libmonodroid.i64` | 604 | Android/JNI to Mono bridge |
| `libmono-native.i64` | 392 | Mono platform support |
| `libmono-btls-shared.i64` | 3,838 | Native TLS/cryptography |
| `libmonosgen-2.0.i64` | 7,870 | Mono runtime and garbage collector |
| `libe_sqlite3.i64` | 1,658 | Bundled SQLite engine |
| `libsqlite3_xamarin.i64` | 1,776 | Xamarin SQLite integration/engine |

No direct first-party SQLite reference was established in the recovered
Installer or WebhouseStandard C# source. Presence of the libraries alone does
not prove an active schema. Start in managed C# for installer behavior.

## Confirmed security findings

- Static legacy device material and API/service credential-like constants were
  shipped in managed assemblies.
- Password and token-like values were stored in ordinary preferences.
- A production click handler mutated the in-memory installer token and
  displayed a device identifier.
- Identifiers frequently appeared in query strings.
- Android backup was allowed while account/job/token state was persisted.

Remove hidden token mutation, rotate embedded material, never persist
passwords, use Keystore-backed scoped credentials, bind every mutation to an
authorized job, redact VIN/device/account data, and make offline records
encrypted, versioned, idempotent, and conflict-aware.

CAN/firmware work additionally requires signed artifacts, compatibility and
hash checks, power preconditions, serialized acknowledged chunks,
protocol-safe cancellation, post-transfer verification, and redacted audit
events.

## Limitations and unresolved questions

- Current backend, entitlement, firmware/CAN signing, and conflict contracts
  are not recovered from static clients.
- SQLite library presence does not establish first-party storage behavior.
- Packet payloads and transfer timing require authorized fixtures and captures.
- IDA covers runtime/native dependencies; the full workflow is managed C# in
  the external archive.
