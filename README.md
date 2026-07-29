# KARR Master

KARR Master is an independent native Android consolidation of the useful
Customer, Dealer, and Installer surfaces recovered from three legacy KARR
applications. KARR SWAT DL is intentionally excluded because its useful
read-only dealer search behavior overlaps the Dealer app and it has no direct
BLE protocol.

This private repository contains the clean-room Kotlin application,
deterministic protocol and classifier tests, hardware-validation notes,
curated reverse-engineering documentation, the four untouched source APKs, and
26 annotated IDA databases. APK and IDA artifacts are stored through Git LFS.
It does **not** contain embedded vendor credentials, universal device keys, or
the full decompiler output.

> **Status:** research prototype, version 0.1.3. BLE discovery, candidate
> classification, UART subscription, protocol parsing, authentication-state
> diagnostics, local demo navigation, encrypted local storage, and policy gates
> are implemented. Production account services and authorized per-device
> authentication are not configured.

## Current capabilities

- Native Kotlin and Jetpack Compose UI.
- Independent Customer, Dealer, and Installer feature modules.
- Server-derived role and capability policy.
- Live BLE scanning with recovered `DR`/`QT` identifiers, Dealer manufacturer
  frames, evidence tiers, nearby-device counts, and unrelated-device
  diagnostics.
- Shared Microchip/KARR UART discovery and notification subscription.
- Explicit separation between a healthy BLE link, a pending KARR
  authentication challenge, and an authenticated session.
- Byte-compatible original (`0xAA`), counter (`0xBB`), and encrypted (`0x55`)
  packet codecs.
- Android Keystore-backed token and payload encryption.
- Durable encrypted offline-operation queue.
- Local safe-demo mode with live mutation disabled.

## Safety boundary

BLE discovery or GATT connection is not vehicle authorization. Demo mode never
sends vehicle commands. Live mutation requires all of:

1. a configured, authenticated backend;
2. server-issued role and capability entitlements;
3. current account, vehicle, or technician-job scope;
4. authorized device challenge-response;
5. an explicit user action.

The recovered static Customer/Dealer keys and cloud/API credentials are not
copied into this project.

## Build

Requirements:

- JDK 17 or newer;
- Android SDK 36;
- an Android SDK path in `local.properties` or `ANDROID_HOME`.

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

On Linux or macOS:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Repository map

| Path | Purpose |
|---|---|
| `app` | Login/demo shell, role-aware navigation, dashboard, device center |
| `core` | Roles, capabilities, sessions, domain models, authorization |
| `protocol` | Packet codecs, command definitions, parsing, compatibility tests |
| `ble` | Android scan/connect/discover/notify/write session |
| `data` | Server boundaries, Keystore storage, encrypted offline queue |
| `feature/customer` | Customer feature surface |
| `feature/dealer` | Dealer feature surface |
| `feature/installer` | Installer feature surface |
| `docs/research` | Curated four-app analysis, provenance, and indexes |
| `research/artifacts` | Git LFS archive of the four original APKs and 26 annotated IDA databases |
| `tools/reverse-engineering` | Source scripts used to generate the offline analysis |
| `.github/workflows` | GitHub Actions build/test/lint validation |

## Documentation

- [Architecture](ARCHITECTURE.md)
- [Migration status](MIGRATION_STATUS.md)
- [Bluetooth identifiers](BLUETOOTH_IDENTIFIERS.md)
- [Connection authentication](CONNECTION_AUTHENTICATION.md)
- [Validation history](VALIDATION.md)
- [Curated reverse-engineering index](docs/research/README.md)
- [Original APK and IDA artifact archive](research/artifacts/README.md)
- [Repository layout and artifact boundary](docs/REPOSITORY_LAYOUT.md)
- [Security policy](SECURITY.md)

## Project identity and licensing

This is not an official KARR, Webhouse Group, Southwest Dealer Services, or
vehicle-manufacturer project. Product names are used only to identify the
interoperability and research subject.

No open-source license has been selected yet. Until one is added, the repository
does not grant redistribution or modification rights. See [NOTICE.md](NOTICE.md)
before publishing or accepting outside contributions.
