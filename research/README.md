# KARR research findings

This directory holds the **written findings** from reverse-engineering the
three shipping KARR Android applications. The evidence binaries those findings
came from are no longer stored in this repository.

## Where the evidence lives

The source APKs and the annotated IDA 9.1 databases (~392 MB across 3 APKs and
20 databases) now live in a **separate private repository**:

> **`neednotapply/KARR-Key-research`**

That repository is private and access is granted individually. **Contact
[@neednotapply](https://github.com/neednotapply) to request access.**

They were split out because they are research inputs, never Android build
inputs: nothing in this repository reads them, no workflow checks them out, and
carrying them here made every clone and every CI checkout pay for them.

## What is still here

| File | Contents |
|---|---|
| `dr1_field_compatibility.md` | Observed `DR` field behaviour and compatibility notes |
| `karr_*/KNOWN_DATA.md` | Per-app known-data document |
| `karr_*/manifest.json` | Per-app integrity and coverage manifest, including the hashes identifying each retained binary |

The `manifest.json` hashes are the link between these notes and the evidence
repository: they identify exactly which APK and which database a finding came
from.

## Projects

| Project | Role | APK | IDA databases | Functions |
|---|---|---:|---:|---:|
| `karr_bt_dealer_v3.131` | Dealer inventory, service, unit, CAN, and firmware workflows | 1 | 6 | 12,954 |
| `karr_bt_v2.15` | Customer account, vehicle control, alert, map, and log workflows | 1 | 6 | 13,085 |
| `karr_installer_v1.39` | Technician fitment, repair, removal, CAN, and validation workflows | 1 | 8 | 16,388 |
| **Total** |  | **3** | **20** | **42,427** |

Each `ida-project` directory in the evidence repository is the project boundary
for one APK. An `.i64` still represents one native binary, so the databases are
intentionally not merged. Open the database for the library being investigated.

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

The databases also contain the persistent marker
`[KARR_KEY_RESEARCH_UPDATE_2026_07_28]`. It records the observed `DR`
devices, the `0x0E` authentication challenge, the GATT-status-19 diagnosis,
and the app-specific authorization boundaries. No credential, key, or
challenge response is included.

The full reconstructed C#, Java, smali, extracted native libraries, logs, and
machine-generated indexes remain outside Git entirely.

Reconstructed output is not the publisher's literal original source and may
contain credential-like constants. Do not add APKs, IDA databases,
reconstructed source, secrets, captures, or account/vehicle data to this
directory — they belong in the evidence repository, and `.gitignore` here is
set up to keep them out.
