# KARR Key

KARR Key is an Android research app that demonstrates a security problem in
some older, dealer-installed KARR vehicle security systems.

These devices are connected to the CAN bus, the internal network used by many
systems in a vehicle. They need that access to lock doors, sound alarms, read
vehicle information, and perform other security functions. Some older models
also identify themselves over Bluetooth and use security information that was
built into the original mobile apps. If that older connection and login process
is reproduced, it can expose much of the vehicle control available to the KARR
unit.

KARR Key makes that problem visible with a local, Bluetooth-based proof of
concept. It is intended only for authorized research and testing. The app does
not include recovered passwords or universal device keys.

## Learn more

- [Read about the security research](https://today.ucsd.edu/story/2-million-cars-with-anti-theft-systems-installed-by-dealers-are-at-higher-risk-of-theft)
- [View the official firmware update instructions](https://www.karrsecurity.com/karr-security-firmware-update-instructions)

## What the app can do

- Find nearby KARR units by their recognizable Bluetooth names and wireless
  service.
- Connect to an authorized unit using research access information supplied by
  the user.
- Read information from the unit and send recovered vehicle or service
  commands.
- Show 21 unique operations without repeating the same command for different
  Customer, Dealer, or Installer workflows.
- Search the operations list or filter it with simple tags: **Reads**,
  **Commands**, **Vehicle controls**, **Diagnostics**, **Unit service**, and
  **Validation**.
- Identify the connected model and disable an operation when that model is not
  supported. The reason is shown before the operation can be opened.
- Show the difference between a command reaching Bluetooth, the KARR unit
  replying, and the vehicle physically doing something.

The app itself works offline. It has no account system, cloud service,
tracking, or Android Internet permission. The two links above open in the
phone's web browser.

## App layout

### Home

Home briefly explains the security issue, shows the current connection status,
and links to the research and firmware update instructions.

### Operations

Operations contains the searchable, filterable list of reads and commands.
Operations that are incompatible with the connected KARR model are visibly
disabled. Opening a supported operation shows what it does and any checks that
must pass before it can run.

One Installer immobilizer-output command is marked **Experimental**. It is sent
only once, requires the ignition to be off, and does not treat a reply from the
unit as proof that the vehicle was physically immobilized.

### Device

Device is where the user adds their research access information, scans for a
unit, connects, authenticates, and views Bluetooth diagnostics. Saved access
information is protected by Android Keystore and is never built into the app.

The app can keep a user-started scan or active connection running in the
background. If a connection unexpectedly drops, it will only try to reconnect
to the last unit that successfully authenticated.

## Important limits

- Finding a device over Bluetooth does not prove ownership or permission to
  use it.
- A Bluetooth connection is not enough to run commands. The KARR unit must
  complete its own authentication for the current connection.
- KARR Key supports the older authentication method recovered from the legacy
  apps. It does not support the newer per-unit method.
- KARR Key is not a firmware installer or a general-purpose CAN bus tool. It
  only exposes operations supported by the preserved app evidence.
- A response from the KARR unit does not prove that a door, alarm, trunk, or
  other physical part of the vehicle changed state.
- The app has been installed and smoke-tested on a Pixel 6a. That confirms the
  Android app and interface work; testing real vehicle behavior still requires
  an authorized compatible unit and direct observation.

## Design and navigation

The interface uses a greyscale light and dark theme. Home, Operations, and
Device can be selected from the navigation bar or by swiping between them.
Operation details use the normal Android back action.

## Building the app

Developers need JDK 17, Android SDK 36, and either `local.properties` or
`ANDROID_HOME` configured for that SDK.

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug :app:assembleDebugAndroidTest
```

On Linux or macOS, use `./gradlew`. The debug APK is written to
`app/build/outputs/apk/debug/KARR-Key.apk` and uses package name `com.karr.key`.
The current app version is `1.0.0` (code 9).

The build checks that the app does not request Internet or network-state
permission. GitHub Actions also runs the tests, lint, and debug build. A nightly
prerelease is created only when the main branch has changed since the previous
release.

Stable releases are also produced entirely by GitHub Actions. Pushing a tag in
the form `vMAJOR.MINOR.PATCH` runs the tests and lint against that exact tagged
commit, builds and verifies a CI-signed release APK, and creates the GitHub
release with the APK, its SHA-256 checksum, the signing-certificate fingerprint,
Android output metadata, and workflow-build provenance. The workflow rejects a
tag whose version does not match the app's `versionName`; release APKs are never
uploaded manually.

For implementation details and exact safety rules, see
[DEVELOPMENT.md](DEVELOPMENT.md). For the preserved research evidence, see
[research/README.md](research/README.md).

## Identity and licensing

KARR Key is not an official KARR, Webhouse Group, Southwest Dealer Services, or
vehicle-manufacturer project. Product names are used only to identify the
subject of this research.

No open-source license has been selected. Until one is added, this repository
does not grant redistribution or modification rights. The original APKs retain
their owners' rights.
