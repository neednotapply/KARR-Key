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
- four untouched source APKs and 26 annotated native IDA databases under
  `research/artifacts`, stored with Git LFS;
- source scripts used to generate the offline workspace;
- GitHub workflow and contribution/security metadata.

The LFS artifact set is intentionally narrow: it preserves the original package
evidence and the reviewable IDA work products without copying the entire
decompiler workspace into the repository.

## Full offline evidence archive

The complete local evidence set remains at:

```text
C:\Users\mrnic\Documents\Playground\karr_four_app_reverse
```

It remains the authoritative source for full reconstructed projects, exact
smali, extracted native libraries, generated logs, and working files. The
repository's APK hashes and pre-update IDA hashes are checked against this
archive in `research/artifacts/ARTIFACT_MANIFEST.csv`.

If the archive is moved later, update this document and preserve the original
hash/provenance files. Do not copy the full archive wholesale into this
repository.

## Artifact access

The GitHub repository must remain private and access controlled. Run
`git lfs pull` after cloning to materialize the APK and IDA files. Do not move
the full reconstructed source, extracted credential-like values, raw captures,
or vehicle/account data into Git history.
