# Original package and IDA evidence

This private Git LFS archive preserves the review inputs used for KARR Master:

- four untouched source APKs, one for each recovered application;
- 26 native IDA 9.1 databases copied from the original analysis workspace,
  covering 55,374 functions with the existing function-comment validation
  complete;
- apply/reopen validation reports for the 2026-07-28 research-note update;
- `ARTIFACT_MANIFEST.csv`, recording sizes and source/repository SHA-256
  values.

## Layout

```text
research/artifacts/
  apks/<app>/<original filename>.apk
  ida/<app>/<native library>.i64
  ida/<app>/recent_findings/*.json
  ARTIFACT_MANIFEST.csv
```

Run `git lfs pull` after cloning to materialize the binary files. The combined
APK and pre-update IDA evidence is about 0.475 GiB.

## Provenance

The source archive remains:

```text
C:\Users\mrnic\Documents\Playground\karr_four_app_reverse
```

The repository APKs are byte-for-byte copies. IDA databases were copied before
the repository-specific annotation update, so the manifest records both their
source hash and their resulting repository hash.

The full reconstructed C#, Java, smali, extracted libraries, caches, and logs
remain offline. They are not “original source code”; they are reconstructed
analysis output and can expose credential-like constants.

## Annotation update

`tools/reverse-engineering/Update-IdaRecentFindings.ps1` invokes IDA in batch
mode for every database, applies a persistent note, saves the database, opens
it again, and validates the note. The marker is:

```text
[KARR_MASTER_RESEARCH_UPDATE_2026_07_28]
```

The note records the observed `DR` identifiers, the `0x0E` authentication
challenge, the meaning of GATT status 19, each app's recovered authentication
boundary, and SWAT DL's lack of a BLE flow. It also states that the meaningful
protocol logic is managed C# and these native databases primarily cover
Xamarin/Mono runtime or native glue. No credential or challenge response is
included.
