# Preserved KARR research evidence

This directory contains the smallest reviewable evidence set retained with
KARR Key:

- three untouched source APKs;
- one IDA project directory per APK;
- all 20 annotated IDA 9.1 databases, covering 42,427 discovered functions;
- a concise known-data document and an integrity/coverage manifest per app.

The APK and IDA files are stored with Git LFS. They are research inputs, not
Android build inputs, and are never packaged into KARR Key.

## Projects

| Project | Role | APK | IDA databases | Functions |
|---|---|---:|---:|---:|
| `karr_bt_dealer_v3.131` | Dealer inventory, service, unit, CAN, and firmware workflows | 1 | 6 | 12,954 |
| `karr_bt_v2.15` | Customer account, vehicle control, alert, map, and log workflows | 1 | 6 | 13,085 |
| `karr_installer_v1.39` | Technician fitment, repair, removal, CAN, and validation workflows | 1 | 8 | 16,388 |
| **Total** |  | **3** | **20** | **42,427** |

Each `ida-project` directory is the project boundary for one APK. An `.i64`
still represents one native binary, so the databases are intentionally not
merged. Open the database for the library being investigated.

## Cross-app conclusions

- All three applications are Xamarin-era Android clients whose useful
  application behavior is managed C#, not native code.
- Dealer, Customer, and Installer use the same Microchip transparent-UART BLE
  service and characteristic. Customer and Installer implement the legacy
  `0xAA` packet; Dealer also implements counter `0xBB` and encrypted `0x55`
  packets.
- The most useful compatibility references are the managed packet codecs,
  command callers, workflows, and DTOs. The native databases cover
  Xamarin/Mono runtime, JNI, TLS, app-registration glue, and Installer's
  bundled SQLite libraries.
- Embedded credentials, static legacy device keys, password persistence,
  global mutable state, string-built URLs, permissive backup behavior, and
  page-owned BLE lifetimes are findings to remediate, not patterns to reuse.

## Coverage and provenance

The complete offline extraction produced 466 managed assembly projects,
11,316 JADX Java files, 20,951 exact smali files, and 20 native libraries.
Every discovered native function has a validated persistent repeatable
comment. Real exports and symbol-bearing names were retained; stripped
functions remain honestly auto-named rather than receiving speculative names.

The repository databases also contain the persistent marker
`[KARR_KEY_RESEARCH_UPDATE_2026_07_28]`. It records the observed `DR`
devices, the `0x0E` authentication challenge, the GATT-status-19 diagnosis,
and the app-specific authorization boundaries. No credential, key, or
challenge response is included.

The full reconstructed C#, Java, smali, extracted native libraries, logs, and
machine-generated indexes remain outside Git.

Reconstructed output is not the publisher's literal original source and may
contain credential-like constants. The hashes and validation facts required
to identify the retained evidence are recorded in each `manifest.json`.

## Checkout

For source-only development, prevent Git LFS from materializing research
binaries:

```powershell
$env:GIT_LFS_SKIP_SMUDGE = "1"
git clone https://github.com/neednotapply/KARR-Key.git
```

To fetch one research project later:

```powershell
git lfs pull --include="research/karr_bt_v2.15/**"
```

Use `git lfs pull` without an include filter only when all three projects are
required. Do not add arbitrary APKs, IDA databases, reconstructed source,
secrets, captures, or account/vehicle data to this directory.
