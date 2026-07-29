# Toolchain

| Tool | Version | Use |
|---|---|---|
| Apktool | 2.9.0 | Exact resources, manifest, and smali decode |
| JADX | 1.5.6 | Java reconstruction from DEX |
| ILSpy command line | 9.1.0.7988 | Managed C# project and diagram export |
| pyxamstore | local preserved tool copy | Xamarin assembly-store extraction |
| IDA Professional | 9.1.25.0226 | ARM64 analysis and `.i64` databases |
| Python | 3.10.10 | Xamarin extraction and IDA automation |
| Java | 1.8.0_471 | JADX/Apktool runtime |
| Mono.Cecil | local tool dependency | Managed symbols and call graph |

The exact regeneration paths and arguments are recorded under `scripts`.
`Initialize-Workspaces.ps1` preserves APKs and performs the decompilation
layers. `Generate-ManagedIndexes.ps1` exports managed symbols/calls/diagrams.
`Run-IDA.ps1` creates/reopens, annotates, reports, and validates native
databases. `Generate-DocumentationIndexes.ps1` creates the safe route, view,
hash, provenance, and native indexes.

IDA logs may mention unavailable optional BinDiff/BinExport plugins. Those
plugins are not used by this collection and their absence does not affect the
databases or annotation validation.
