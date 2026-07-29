# Completeness

- Preserved APK: 57,171,175 bytes; SHA-256 in `artifact_provenance.csv`.
- Managed assemblies/C# projects: 196/196; decompile failures: 0.
- The 196 count includes localized satellite assemblies.
- JADX Java files: 2,815.
- Exact smali files: 4,956.
- ARM64 libraries/IDA databases: 8/8.
- All discovered IDA functions repeatably commented: yes.
- Symbols, call graph, routes, UI inventory, hashes, native index, and
  provenance are present.

JADX returned usable output with reported method errors; exact smali is the
fallback. Recovered C# is faithful decompiler output from shipped IL, not the
literal publisher repository or a guaranteed historical Xamarin rebuild.
