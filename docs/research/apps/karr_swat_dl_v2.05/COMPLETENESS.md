# Completeness

- Preserved APK: 24,869,685 bytes; SHA-256 in `artifact_provenance.csv`.
- Managed assemblies/C# projects: 70/70; decompile failures: 0.
- JADX Java files: 3,144.
- Exact smali files: 6,169.
- ARM64 libraries/IDA databases: 6/6.
- All discovered IDA functions repeatably commented: yes.
- Symbols, call graph, routes, compiled-XAML inventory, hashes, native index,
  and provenance are present.

JADX returned usable output with reported method errors; exact smali is the
fallback. Recovered C# is a faithful IL reconstruction, not the literal
publisher repository or a guaranteed historical Xamarin rebuild.
