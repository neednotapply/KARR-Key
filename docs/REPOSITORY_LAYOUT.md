# Repository layout and artifact boundary

## Why this is not a byte-for-byte copy of the Playground workspace

The original reverse-engineering workspace contains approximately 119,000 files
and 3 GiB of data. It includes:

- four untouched third-party APKs;
- 536 recovered Xamarin assemblies and reconstructed C# projects;
- 14,460 JADX Java files;
- 27,120 exact smali files;
- 26 native libraries and annotated IDA databases;
- generated indexes, logs, caches, and tool work directories.

Several files exceed GitHub's ordinary file-size limit. More importantly, the
reconstructed applications expose credential-like constants and legacy
authentication material that must not enter normal Git history.

## Tracked repository

The tracked repository contains:

- the independently written Android application and tests;
- architecture, migration, BLE, authentication, and validation documents;
- curated cross-app research documents;
- non-secret symbol, route, call-graph, provenance, and IDA-validation indexes;
- source scripts used to generate the offline workspace;
- GitHub workflow and contribution/security metadata.

## Offline evidence archive

The complete local evidence set remains at:

```text
C:\Users\mrnic\Documents\Playground\karr_four_app_reverse
```

It is the authoritative source for untouched APK hashes, full reconstructed
projects, exact smali, native libraries, and IDA databases. The tracked
`docs/research/collection/collection_validation.json` records the collection
counts and APK hashes without redistributing the packages.

If the archive is moved later, update this document and preserve the original
hash/provenance files. Do not copy it wholesale into this repository.

## Optional private archival strategy

If remote backup of the complete evidence set becomes necessary, use a
separate access-controlled archive or private Git LFS repository with explicit
secret review, legal review, retention limits, and restricted membership. Do
not mix the evidence archive with the application repository's history.
