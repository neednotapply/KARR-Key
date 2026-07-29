# Preserved KARR research evidence

This directory contains the smallest reviewable evidence set retained with
KARR Master:

- four untouched source APKs;
- one IDA project directory per APK;
- all 26 annotated IDA 9.1 databases, covering 55,374 discovered functions;
- a concise known-data document and an integrity/coverage manifest per app.

The APK and IDA files are stored with Git LFS. They are research inputs, not
Android build inputs, and are never packaged into KARR Master.

## Projects

| Project | Role | APK | IDA databases | Functions |
|---|---|---:|---:|---:|
| `karr_bt_dealer_v3.131` | Dealer inventory, service, unit, CAN, and firmware workflows | 1 | 6 | 12,954 |
| `karr_bt_v2.15` | Customer account, vehicle control, alert, map, and log workflows | 1 | 6 | 13,085 |
| `karr_installer_v1.39` | Technician fitment, repair, removal, CAN, and validation workflows | 1 | 8 | 16,388 |
| `karr_swat_dl_v2.05` | Read-only dealer vehicle search, map, and battery/status workflows | 1 | 6 | 12,947 |
| **Total** |  | **4** | **26** | **55,374** |

Each `ida-project` directory is the project boundary for one APK. An `.i64`
still represents one native binary, so the databases are intentionally not
merged. Open the database for the library being investigated.

## Cross-app conclusions

- All four applications are Xamarin-era Android clients whose useful
  application behavior is managed C#, not native code.
- Dealer, Customer, and Installer use the same Microchip transparent-UART BLE
  service and characteristic. Customer and Installer implement the legacy
  `0xAA` packet; Dealer also implements counter `0xBB` and encrypted `0x55`
  packets.
- SWAT DL has no BLE, GATT, unit-authentication, CAN, firmware, or local
  vehicle-control flow.
- The most useful compatibility references are the managed packet codecs,
  command callers, workflows, and DTOs. The native databases cover
  Xamarin/Mono runtime, JNI, TLS, app-registration glue, and Installer's
  bundled SQLite libraries.
- Embedded credentials, static legacy device keys, password persistence,
  global mutable state, string-built URLs, permissive backup behavior, and
  page-owned BLE lifetimes are findings to remediate, not patterns to reuse.

## Coverage and provenance

The complete offline extraction produced 536 managed assembly projects,
14,460 JADX Java files, 27,120 exact smali files, and 26 native libraries.
Every discovered native function has a validated persistent repeatable
comment. Real exports and symbol-bearing names were retained; stripped
functions remain honestly auto-named rather than receiving speculative names.

The repository databases also contain the persistent marker
`[KARR_MASTER_RESEARCH_UPDATE_2026_07_28]`. It records the observed `DR`
devices, the `0x0E` authentication challenge, the GATT-status-19 diagnosis,
the app-specific authorization boundaries, and SWAT DL's confirmed lack of a
BLE path. No credential, key, or challenge response is included.

The full reconstructed C#, Java, smali, extracted native libraries, logs, and
machine-generated indexes remain outside Git. The current maintainer archive
is:

```text
C:\Users\mrnic\Documents\Playground\karr_four_app_reverse
```

Reconstructed output is not the publisher's literal original source and may
contain credential-like constants. The hashes and validation facts required
to identify the retained evidence are recorded in each `manifest.json`.

## Checkout

For source-only development, prevent Git LFS from materializing research
binaries:

```powershell
$env:GIT_LFS_SKIP_SMUDGE = "1"
git clone https://github.com/neednotapply/KARR-Master.git
```

To fetch one research project later:

```powershell
git lfs pull --include="research/karr_bt_v2.15/**"
```

Use `git lfs pull` without an include filter only when all four projects are
required. Do not add arbitrary APKs, IDA databases, reconstructed source,
secrets, captures, or account/vehicle data to this directory.
