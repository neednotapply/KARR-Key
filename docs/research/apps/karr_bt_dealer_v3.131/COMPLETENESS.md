# Completeness

- Preserved APK: 55,158,002 bytes, SHA-256 recorded in
  `artifact_provenance.csv`.
- Managed assemblies recovered: 190.
- ILSpy C# projects completed: 190; recorded failures: 0.
- JADX Java files: 5,200.
- Exact smali files: 9,876.
- ARM64 libraries/IDA databases: 6/6.
- All discovered IDA functions repeatably commented: yes.
- Managed hashes, symbols, call graph, UI inventory, API routes, native index,
  and provenance are present under `05_annotations`.

JADX produced usable output with reported method-reconstruction errors. Exact
smali is the fallback. Recovered C# is a decompiler reconstruction, not the
publisher's literal repository or a guaranteed drop-in Xamarin rebuild.
