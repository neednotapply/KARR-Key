# KARR BT Dealer 3.131 known data

## Artifact identity

- Original package: `KARR+BT+Dealer_3.131_APKPure.apk`
- Role: dealer inventory, transaction, service, and direct-unit client
- Framework: Xamarin.Forms for Android
- Native coverage: 6 IDA 9.1 databases, 12,954 commented functions
- Package and database hashes: `manifest.json`

The APK is retained byte-for-byte. The IDA databases contain native analysis
and the repository research note; they do not contain the managed application
projects.

## Framework and architecture

The startup path is Android `MainActivity` -> Xamarin.Forms ->
`FRMBLEDealerApp.App` -> `NavigationPage(Login)`. After authentication the app
selects an account/dealer context, synchronizes data, and enters inventory,
map, transaction, or Bluetooth/unit workflows.

Primary managed components:

| Component | Responsibility |
|---|---|
| `FRMBLEDealerApp.dll` | Pages, lifecycle, dealer workflows, BLE orchestration, offline queues |
| `WebhouseStandard.dll` | Shared API/data contracts, device keys, packet parsing |
| `Validation.dll`, `CsvReader.dll` | Validation and CSV helpers |
| `Plugin.BLE.dll` | BLE abstraction |
| Xamarin/Essentials/Firebase | UI, preferences, secure storage, push, platform services |

Global state is concentrated in `App`, `Shared`, `TransparentData`, and page
classes. `SettingsSave` wraps preferences; server-provided device keys use
Xamarin.Essentials `SecureStorage`.

## Managed-code entrypoints

- `FRMBLEDealerApp/Droid/MainActivity.cs` initializes Android.
- `FRMBLEDealerApp/App.cs` constructs shared state, Plugin.BLE, connectivity
  handlers, background behavior, and login navigation.
- `Shared.cs` coordinates dealer data and operations.
- `TransparentData.cs` owns active-device notifications and command plumbing.
- `WebhouseStandard/Quantam/Protocol/QtProtocolParser.cs` implements packet
  framing.
- `Pages/UnitSettingsRefreshed.cs` is the densest current unit workflow.
- `WebhouseStandard/Webhouse/Data/DeviceData.cs` retrieves authorized
  device-key material.

## Features and routes

The recovered UI includes login, account selection, data download, inventory,
Bluetooth scanning, maps, user settings, legacy unit settings, and the newer
refreshed unit surface. Dealer workflows include:

- inventory, vehicle/serial search, account changes, products, and status;
- sale, pending sale, unwind, booking/BCA, return, and offline sale handling;
- tent/snow modes, statistics, locations, and background synchronization;
- unit state/settings, remotes, violations, diagnostics, CAN, and firmware.

The literal route inventory contained 86 normalized occurrences:

| Family | Count | Observed use |
|---|---:|---|
| vehicles | 28 | Inventory, products, sale/unwind, bookings, statistics |
| fitments | 17 | Install, repair, removal, and test operations |
| users | 14 | Login, access, account, and user context |
| vehiclecansupport | 7 | CAN compatibility and artifact retrieval |
| logs | 6 | Access, location, and operational logs |
| accounts | 5 | Account list/current/create/verification |
| devicesecurity | 5 | Authorized device-key retrieval |
| products | 2 | Product and snow-mode operations |
| decoders / controlroom | 2 | VIN decode and alarm-event submission |

Route names are recovered client literals, not a live API contract. Payload,
authorization, and server behavior must be confirmed from current services.

## BLE and protocol

- Service: `49535343-FE7D-4AE5-8FA9-9FAFD205E455`
- Characteristic: `49535343-1E4D-4BD9-BA61-23C647249616`
- Transport owner: `TransparentData.cs`

`QtProtocolParser` supports:

| Marker | Mode | Shape |
|---:|---|---|
| `0xAA` | Original | marker, big-endian command/payload length, command, payload, checksum |
| `0xBB` | Counter | marker, counter, length, command, payload, checksum |
| `0x55` | Encrypted | marker, 16-byte IV, AES-CBC-wrapped counter packet |

Checksums use additive two's complement. The encrypted path uses a 128-bit key
derived from the first 16 bytes of authorized per-unit key material. Command
values span `0..78`, `253`, `254`, and `255`; callers, not enum names, are the
payload-schema authority.

## Authentication boundary

Dealer supports an 8-byte legacy challenge and a newer 32-byte/AES path.
Authorized customer, dealer, master, and per-unit material is obtained through
the data/backend layer and stored with `SecureStorage`.

The observed `DR D029A54D` and `DR 0F9ACB56` units sent an Original-mode
`RequestAuth` (`0x0E`) with an 8-byte challenge after UART subscription. GATT
status 19 was the peripheral ending the still-unauthenticated link. Android
bonding or a universal numeric PIN cannot replace application authentication.

Dealer access is not installer, customer, master-key, or unrestricted
vehicle-control authority. Every mutation must remain scoped by current server
role, account, vehicle, capability, successful unit authentication, and
explicit workflow state.

## Native libraries and IDA reading order

| Database | Functions | Role |
|---|---:|---|
| `libxamarin-app.i64` | 1 | App configuration and assembly registration |
| `libxa-internal-api.i64` | 249 | Xamarin.Android internal native API |
| `libmonodroid.i64` | 604 | Android/JNI to Mono bridge |
| `libmono-native.i64` | 392 | Mono platform support |
| `libmono-btls-shared.i64` | 3,838 | Native TLS/cryptography |
| `libmonosgen-2.0.i64` | 7,870 | Mono runtime and garbage collector |

Start with reconstructed managed C# for dealer behavior. Use IDA only for
startup, JNI, runtime, TLS, or assembly-loading questions.

## Confirmed security findings

- A preference-key collision uses the same literal for last firmware check and
  multi-dealer mode.
- Password-like data was stored in ordinary preferences.
- Cloud/API/service credential-like constants were shipped in managed code.
- Device keys were server-provided and stored in secure storage, but client
  lifecycle, logout deletion, and scope still require enforcement.
- Sensitive fields were frequently assembled into URLs.
- Android backup was allowed while account/device state was persisted.

Do not reproduce the recovered credential values. Rotate/remove client
credentials, use short-lived signed artifact URLs, exclude sensitive stores
from backup, and make offline records account-scoped and idempotent.

## Limitations and unresolved questions

- Recovered routes and DTOs do not establish the current production contract.
- Static code does not prove which capabilities any present account or unit
  is entitled to use.
- Packet payloads, counter/replay behavior, timeouts, CAN compatibility, and
  firmware rules require authorized golden captures and fixtures.
- IDA covers runtime libraries; managed C# remains the source-level behavior
  reference in the external archive.
