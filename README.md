# KARR Master

KARR Master is an independent native Android research prototype that
consolidates Customer, Dealer, and Installer workflows recovered from legacy
KARR applications.

> **Status:** version 0.1.3. BLE discovery and classification, UART
> subscription, packet parsing, authentication-state diagnostics, safe demo
> navigation, encrypted local storage, and policy gates are implemented.
> Production account services and authorized per-device authentication are not
> configured.

## Capabilities

- Kotlin and Jetpack Compose UI with role-aware Customer, Dealer, and Installer
  surfaces.
- BLE scanning with recovered `DR`/`QT` identifiers, manufacturer-frame
  evidence tiers, and unrelated-device diagnostics.
- Shared transparent-UART discovery, subscription, notification parsing, and
  explicit transport/authentication states.
- Original (`0xAA`), counter (`0xBB`), and encrypted (`0x55`) packet codecs.
- Android Keystore-backed token/payload encryption and an encrypted durable
  offline-operation queue.
- Safe demo mode in which live mutation remains disabled.

## Safety boundary

BLE discovery or a GATT connection is not vehicle authorization. Live mutation
requires an authenticated backend, server-issued role/capability data, current
account/vehicle/technician-job scope, authorized device challenge-response,
and explicit user intent.

Recovered credentials, universal device keys, and challenge responses are not
included in the application source or documentation.

## Build

Requirements:

- JDK 17 or newer;
- Android SDK 36;
- `local.properties` or `ANDROID_HOME` configured for that SDK.

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug
```

On Linux or macOS, use `./gradlew` instead of `.\gradlew.bat`. The debug APK is
written to `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions retains the debug APK, SHA-256 checksum, and Android package
metadata in `karr-master-debug-<commit SHA>`. The package is debug-signed and
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
| `data` | Repository boundaries, Keystore storage, and offline queue |
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
their owners' rights and are not covered by any future license for KARR
Master's independently written code.
