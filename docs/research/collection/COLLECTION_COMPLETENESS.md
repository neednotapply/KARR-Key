# Collection completeness

## Extraction result

| App | Managed assemblies | C# projects | JADX Java | Exact smali | ARM64 IDA DBs |
|---|---:|---:|---:|---:|---:|
| BT Dealer 3.131 | 190 | 190 | 5,200 | 9,876 | 6 |
| BT 2.15 | 80 | 80 | 3,301 | 6,119 | 6 |
| Installer 1.39 | 196 | 196 | 2,815 | 4,956 | 8 |
| SWAT DL 2.05 | 70 | 70 | 3,144 | 6,169 | 6 |
| **Total** | **536** | **536** | **14,460** | **27,120** | **26** |

All 536 managed assemblies completed ILSpy project export with zero recorded
failures.

## IDA result

Every shipped `arm64-v8a` library has:

- its original `.so`;
- an IDA 9.1 `.i64` database;
- a sidecar report;
- a repeatable comment on every function IDA discovered;
- a validation record with `all_functions_commented: true`.

Across the 26 databases, IDA discovered 55,374 functions. All 55,374 have
validated persistent repeatable comments.

Real exports and symbol-bearing names were retained. Stripped `sub_*`
functions were marked as auto-discovered rather than assigned speculative
semantic names. This is intentional: false names would be less useful than
honest, persistent classification. The application business logic is in the
managed C# projects, not in the Xamarin runtime libraries.

## Decompiled-code caveats

JADX reported recoverable reconstruction errors in all four applications.
Usable Java output is present, and exact smali is retained for every method.
ILSpy output may include generated XAML loaders, state machines, compiler
generated types, and dependencies that require the historical Xamarin Android
toolchain. No claim is made that these reconstructed projects are drop-in
rebuilds of the publisher's source tree.

## Machine validation surfaces

Per app:

- `logs/extraction_summary.json`
- `logs/managed_decompile_status.json`
- `logs/jadx_status.json` where emitted
- `logs/ida_validation/*.json`
- `05_annotations/artifact_provenance.csv`
- `05_annotations/managed_binary_hashes.csv`
- `05_annotations/native_index.csv`
- `05_annotations/native_database_provenance.csv`

Run `scripts/Validate-Collection.ps1` for the final consistency audit.
