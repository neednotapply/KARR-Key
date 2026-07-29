# Validation

Validation is intentionally split between deterministic build checks and safe
real-hardware checks. No KARR unit command was sent during this pass.

## Automated

Run from the project root:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
.\gradlew.bat lintDebug
```

Validated on 2026-07-28:

- all core authorization tests passed;
- all protocol golden-vector, checksum, truncation, and encrypted round-trip
  tests passed;
- the complete multi-module debug build passed;
- Android lint passed without a baseline or suppressed project errors;
- the debug APK is signed and installable as `com.karr.master`;
- classifier tests passed for the two observed `DR` names, exact `DR`/`QT`
  serials, embedded Dealer manufacturer frames, legacy prefixes, generic UART
  candidates, branding hints, and unrelated-device retention;
- both captured in-vehicle notifications parse as `REQUEST_AUTH` with an
  eight-byte challenge;
- GATT disconnect diagnostics distinguish peer termination, authentication
  timeout, local termination, connection timeout, establishment failure, and
  Android's generic status 133;
- final APK version: `0.1.3` (`versionCode 4`);
- final APK size: 12,955,406 bytes;
- final APK SHA-256:
  `179604877A10FF648ACCE363D0D90B03ECD283C8063BAD8960FB11C48DAAB812`.

## Pixel 6a hardware pass

- ADB installation succeeded without replacing any legacy KARR package.
- Cold launch reached `com.karr.master/.MainActivity`.
- Login and all-role safe demo rendered.
- Customer, Dealer, Installer, and Device navigation rendered without a
  runtime exception.
- Nearby-device permission was requested by Android and accepted.
- With Bluetooth temporarily enabled, the UI entered `Scanning` and received
  real scan callbacks.
- Scan was stopped without connecting to a device.
- Bluetooth was restored to its original off state.
- Local protocol self-test produced:
  `AA 00 01 21 DE -> READ_STATE`.
- `Send READ_STATE` remained disabled because no production authorization or
  device authentication existed.

Screenshots and UI hierarchy dumps from this local run are generated under
`build/validation` and are excluded from source control.

## Filtered-scan modification

The 2026-07-28 filtered-scan update passed the full unit, lint, and debug-build
suite. Version `0.1.1` was then installed on the Pixel 6a and validated:

- the Device screen started at `0 Compatible Devices Found`;
- during a ten-second real scan, the connection status continuously displayed
  `Scanning - 0 Compatible Devices Found`;
- unrelated nearby devices did not enter the list or count;
- stopping the scan returned the controller to `Idle` while retaining the
  completed-scan count;
- no connection or KARR command was attempted;
- no Android runtime exception was logged;
- Bluetooth was on before the test and remained on afterward.

## Evidence-tier scanner modification

The 2026-07-28 evidence-tier update passed
`testDebugUnitTest lintDebug assembleDebug` and was installed over the prior
debug build on the Pixel 6a:

- Android reported `versionName=0.1.2` and `versionCode=3`;
- the Device screen rendered
  `0 KARR Candidates Found / 0 Nearby Devices Found` before scanning;
- a real scan continuously increased the total-nearby count from 19 to 32;
- the test location produced zero KARR candidates, as expected from the
  observed advertisements there;
- **Show Other Nearby (32)** rendered named and unnamed advertisements with
  RSSI and `No recovered KARR identifier`;
- unrelated rows had no Connect button;
- stopping the scan returned to `Idle` and retained both result groups;
- no device connection or KARR command was attempted;
- Bluetooth was already enabled and its state was not changed.

The exact vehicle-screenshot names `DR D029A54D` and `DR 0F9ACB56` are covered
by deterministic classifier tests and were subsequently confirmed in the
user's in-vehicle version-0.1.2 scan.

## Connection-authentication diagnosis

The 2026-07-28 connection-diagnostics update passed
`testDebugUnitTest lintDebug assembleDebug`:

- both user captures beginning `AA 00 09 0E` were reproduced as deterministic
  protocol tests and decoded as command `REQUEST_AUTH` plus eight challenge
  bytes;
- UART notification subscription is now represented as
  `UartSubscribed`, not authenticated `Ready`;
- receiving `REQUEST_AUTH` moves the UI to `AuthenticationRequired`;
- `Ready` is only reached after a valid `AUTH_SUCCESS` notification;
- a status-19 disconnect after `REQUEST_AUTH` is explained as the KARR unit
  ending a healthy BLE link because the application challenge was unanswered;
- captured notifications display their decoded command and payload length;
- live protocol writes remain locked in demo mode;
- no recovered shared key, pairing secret, or challenge-response bypass was
  added to the app.

The current build can prove that BLE transport and notification setup succeed,
but it cannot maintain an authenticated KARR session without an authorized
challenge-response provider. The recovered current Dealer flow obtains its
key material through the production backend; that integration remains an
external requirement.

Version `0.1.3` (`versionCode 4`) was then installed over the existing build on
the connected Pixel 6a:

- Android reported the expected package version;
- a cold launch resumed `com.karr.master/.MainActivity`;
- the login and Device screens rendered correctly;
- no KARR scan, connection, or protocol command was initiated;
- logcat contained no `FATAL EXCEPTION` for the app.

## Repository packaging validation

The GitHub-oriented repository was independently rebuilt from
`C:\Users\mrnic\Documents\GitHub\KARR-Master`. Generated Gradle output was
excluded from the copy; an ignored workstation-local `local.properties` was
then supplied only for the local validation build:

- Git was initialized on an uncommitted local `main` branch;
- the complete `testDebugUnitTest lintDebug assembleDebug` command passed;
- the resulting APK retained the expected size and SHA-256 listed above;
- 155 non-ignored candidate files total approximately 15.22 MiB;
- the largest candidate file is under 3 MiB;
- representative APK, XAPK, IDA, native-library, managed-binary,
  `local.properties`, secret, and raw-research paths are ignored;
- no symlinks or junctions point back into the Playground workspace;
- a literal-pattern scan found no credential assignment in the packaged source
  or curated research files.

The repository was intentionally left uncommitted and unstaged for owner review.

## Original APK and updated IDA archive

The private Git LFS artifact pass on 2026-07-28 validated:

- four APKs copied byte-for-byte from each app's `00_original_package`
  directory;
- all four repository APK SHA-256 values match both the offline source files
  and `docs/research/collection/collection_validation.json`;
- 26 IDA 9.1 databases copied from the four apps' `04_native/ida_databases`
  directories: Dealer 6, Customer/BT 6, Installer 8, and SWAT DL 6;
- the prior IDA coverage remains 55,374 functions with every indexed function
  carrying its existing generated comment;
- all 26 databases received the
  `[KARR_MASTER_RESEARCH_UPDATE_2026_07_28]` note, were saved, closed, reopened
  by IDA, and passed marker/content validation;
- every IDA source hash differs from its repository hash after the annotation,
  while the manifest retains both hashes;
- 26 apply reports and 26 reopen-validation reports were generated, with no
  report claiming embedded secret values;
- `ARTIFACT_MANIFEST.csv` contains 30 records totaling 509,810,807 bytes
  (approximately 0.475 GiB before Git LFS transfer overhead).

The added database note records the observed `DR` candidates, Original-mode
`RequestAuth` challenge, GATT-status-19 diagnosis, per-app authentication
boundary, and SWAT DL negative BLE finding. It deliberately points to the
managed C# evidence rather than assigning fabricated meanings to Xamarin/Mono
native runtime functions.
