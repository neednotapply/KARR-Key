# Curated reverse-engineering index

This directory preserves the useful, non-secret analysis surfaces from the
four-app reverse-engineering collection without committing the original APKs,
full reconstructed vendor code, native libraries, or IDA databases.

## Collection-level documents

- [`MASTER_APP_FEATURE_MAP.md`](collection/MASTER_APP_FEATURE_MAP.md): feature
  ownership across Customer, Dealer, Installer, and SWAT DL.
- [`MASTER_APP_ARCHITECTURE.md`](collection/MASTER_APP_ARCHITECTURE.md): proposed
  combined-client architecture.
- [`CROSS_APP_CODE_REUSE.md`](collection/CROSS_APP_CODE_REUSE.md): confirmed
  shared code and protocol boundaries.
- [`SECURITY_MIGRATION_NOTES.md`](collection/SECURITY_MIGRATION_NOTES.md):
  migration risks and required controls.
- [`COLLECTION_COMPLETENESS.md`](collection/COLLECTION_COMPLETENESS.md):
  decompilation and IDA coverage.
- [`collection_validation.json`](collection/collection_validation.json):
  machine-readable counts, APK hashes, and validation result.
- [`TOOLCHAIN.md`](collection/TOOLCHAIN.md): tools used by the offline analysis.

## Per-app indexes

| App | Curated index |
|---|---|
| KARR BT Dealer 3.131 | [`apps/karr_bt_dealer_v3.131/WORKSPACE_README.md`](apps/karr_bt_dealer_v3.131/WORKSPACE_README.md) |
| KARR BT 2.15 | [`apps/karr_bt_v2.15/WORKSPACE_README.md`](apps/karr_bt_v2.15/WORKSPACE_README.md) |
| KARR Installer 1.39 | [`apps/karr_installer_v1.39/WORKSPACE_README.md`](apps/karr_installer_v1.39/WORKSPACE_README.md) |
| KARR SWAT DL 2.05 | [`apps/karr_swat_dl_v2.05/WORKSPACE_README.md`](apps/karr_swat_dl_v2.05/WORKSPACE_README.md) |

Each directory contains the app workspace overview; architecture, feature,
protocol, security, and completeness guides; and curated symbol, route,
call-graph, view, native, and provenance indexes.

The large all-dependency `managed_symbols.csv` files are intentionally omitted.
The smaller `first_party_managed_symbols.csv` and
`first_party_call_graph.csv` files provide the useful application-level index.

## Provenance and limits

The C# and Java representations described here are reconstructed decompiler
output, not the publishers' literal source repositories. Original comments,
source history, exact local names, and some compiler-lowered structure cannot
be recovered.

The complete offline evidence archive and its exact paths are documented in
[`docs/REPOSITORY_LAYOUT.md`](../REPOSITORY_LAYOUT.md).
