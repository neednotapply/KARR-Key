# KARR Key

KARR Key is an independent native Android research prototype that
consolidates Customer, Dealer, and Installer workflows recovered from legacy
KARR applications.

> **Status:** version 0.3.0. KARR Key is offline-only and Bluetooth-first.
> It opens directly to the device center, requests no Android Internet
> permission, and contains no login or backend adapter. BLE discovery,
> transparent-UART subscription, packet parsing, authentication-state
> diagnostics, locally provisioned legacy challenge authentication, local
> key-gated workflow navigation, recovered live device actions, and encrypted
> local storage are implemented.

## Capabilities

- Kotlin and Jetpack Compose UI with role-aware Customer, Dealer, and Installer
  surfaces.
- Horizontal swipe gestures across the three primary sections, Android back
  navigation from feature details, and bundled navigation icons.
- Red-black application theming and a monochrome car adaptive launcher icon
  with a dedicated layer for Android themed icons.
- Direct startup into the Bluetooth device center; no account sign-in, cloud
  synchronization, push, maps service, telemetry, or production API.
- BLE scanning with recovered `DR`/`QT` identifiers, manufacturer-frame
  evidence tiers, and unrelated-device diagnostics. Connect is offered only
  for connectable advertisements with a confirmed recovered identifier.
- Shared transparent-UART discovery, subscription, notification parsing, and
  explicit transport/authentication states.
- Recovered 8-byte legacy challenge authentication with explicit Customer and
  Dealer/Installer key provisioning. Provisioned values are encrypted with
  Android Keystore and are never compiled into the application.
- Provisioning Customer material exposes the complete offline Customer
  catalog. Provisioning Dealer material exposes the complete offline Dealer
  and Installer catalogs.
- Evidence-backed read and mutation actions are available from their feature
  pages. Enabled mutations run directly, require an authenticated compatible
  unit, and show unit notifications separately from Android GATT delivery.
- The authentication `READ_MODE` response identifies the connected QT/DR
  model before tools become ready. Model-restricted commands are labeled and
  disabled in the catalog and detail screens instead of being sent to a unit
  known not to support them.
- The Installer catalog includes a dedicated experimental immobilizer-output
  test. It shows the exact candidate frame, confirms ignition is off, sends
  once without automatic retry, and reports the raw unit notification without
  treating protocol acknowledgement as proof of physical immobilization.
- Original (`0xAA`), counter (`0xBB`), and encrypted (`0x55`) packet codecs.
- Android Keystore-backed local-value encryption and an encrypted local
  operation journal.
- One local workspace containing only Customer, Dealer, and Installer
  workflows that can operate on local records or direct Bluetooth.

## Safety boundary

BLE discovery or a GATT connection is not application authentication. A
safety-sensitive command remains locked until the connected unit sends
`AUTH_SUCCESS` for the current BLE connection and the user explicitly starts
the operation.

Authorized research keys are entered manually in the Device center. KARR Key
stores only their Android-Keystore-encrypted form, never displays a
saved value, and removes authenticated state on disconnect. A provisioned key
unlocks its corresponding local role catalog; it does not mark a live unit
authenticated. The legacy flow does not cover the newer 32-byte/per-unit AES
authentication path.

Firmware and CAN transfer pages remain visible with the appropriate Dealer
material, but a key is not a firmware/CAN artifact. Live transfer additionally
requires compatible authorized bytes, integrity metadata, power checks,
chunk acknowledgement, and installed-version verification.

The recovered APKs, managed decompilations, and IDA projects are offline
compatibility evidence. KARR Key does not contact their original services
at runtime. Recovered universal credentials are not embedded in the
application; any per-unit material must be provisioned locally and protected
by Android Keystore.

## Build

Requirements:

- JDK 17 or newer;
- Android SDK 36;
- `local.properties` or `ANDROID_HOME` configured for that SDK.

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug
```

`preBuild` runs `verifyOfflineManifest`, which fails if the application starts
requesting `INTERNET` or `ACCESS_NETWORK_STATE`.

On Linux or macOS, use `./gradlew` instead of `.\gradlew.bat`. The debug APK is
written to `app/build/outputs/apk/debug/KARR-Key.apk`.

GitHub Actions retains the debug APK, SHA-256 checksum, and Android package
metadata in `KARR-Key-debug-<commit SHA>`. The APK is named `KARR-Key.apk`,
uses application ID `com.karr.key`, is debug-signed, and is
intended only for development and authorized-device testing.

The Nightly Release workflow runs automatically each day at 2:15 AM Pacific.
It compares `main` with the newest non-draft GitHub release. When the
repository has changed, it runs the full test/lint/build command and publishes
the debug APK, SHA-256 checksum, and Android package metadata as a dated
prerelease. It creates no release when the trees are identical.

## Repository

| Path | Purpose |
|---|---|
| `app` | Application shell, navigation, dashboard, and device center |
| `core` | Roles, capabilities, sessions, domain models, and policy |
| `protocol` | Packet codecs, command definitions, and compatibility tests |
| `ble` | Android scan/connect/discover/notify/write lifecycle |
| `data` | Local workspace, Keystore storage, and local operation journal |
| `feature/*` | Customer, Dealer, and Installer Compose surfaces |
| `research` | Three original APKs and their annotated IDA projects |

See [DEVELOPMENT.md](DEVELOPMENT.md) for architecture, trust boundaries, tests,
and integration requirements. See [research/README.md](research/README.md) for
the optional Git LFS evidence set and selective checkout instructions.

## Identity and licensing

This is not an official KARR, Webhouse Group, Southwest Dealer Services, or
vehicle-manufacturer project. Product names identify the interoperability and
research subject only.

No open-source license has been selected. Until one is added, this repository
does not grant redistribution or modification rights. The original APKs retain
their owners' rights and are not covered by any future license for KARR Key's
independently written code.
