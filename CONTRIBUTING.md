# Contributing

## Before changing code

1. Read `README.md`, `ARCHITECTURE.md`, and `SECURITY.md`.
2. Keep Customer, Dealer, Installer, transport, protocol, data, and policy
   responsibilities in their existing modules.
3. Preserve demo mode's no-live-command guarantee.
4. Do not add recovered credentials, static universal keys, APKs, decompiler
   payloads, or vehicle-identifying captures.

## Validation

Run the complete local check before opening a pull request:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Protocol changes should include deterministic packet tests. BLE-classification
changes should include positive, negative, and ambiguous advertisement cases.
UI or Android-lifecycle changes should be smoke-tested on an emulator or an
authorized physical device.

## Pull requests

Describe:

- the user-visible outcome;
- the trust boundary or authorization impact;
- tests performed;
- any remaining production integration requirement.

Do not include device secrets, account data, proprietary binaries, or raw
vehicle captures in issues, commits, CI artifacts, or pull-request text.
