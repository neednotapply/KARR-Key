# Completeness

- Preserved APK: 30,836,808 bytes; SHA-256 in `artifact_provenance.csv`.
- Managed assemblies/C# projects: 80/80; decompile failures: 0.
- JADX Java files: 3,301.
- Exact smali files: 6,119.
- ARM64 libraries/IDA databases: 6/6.
- All discovered IDA functions repeatably commented: yes.
- Symbols, call graph, route index, UI inventory, hashes, native index, and
  provenance are present.

JADX returned usable output with reported method errors; exact smali is the
fallback. C# is reconstructed from shipped IL and is not the original
source-control tree or a guaranteed historical Xamarin rebuild.
