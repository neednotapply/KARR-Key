# Reverse-engineering support scripts

These are the source scripts used to create and validate the original local
four-app analysis workspace.

They are not required to build KARR Master. Several scripts contain
workstation-specific tool discovery or expect the original directory layout.
Review and parameterize paths before running them elsewhere.

Tracked:

- managed assembly and call-index generators;
- documentation-index generation;
- workspace initialization and collection validation;
- IDA batch orchestration, annotation, seed, and validation source.
- `Update-IdaRecentFindings.ps1` and
  `update_ida_recent_findings.py`, which apply and reopen-validate the
  2026-07-28 BLE/authentication research note in every archived IDA database.
- `Generate-ResearchArtifactManifest.ps1`, which compares each repository
  artifact with the original offline archive and records both SHA-256 values.

Prebuilt helper executables and `Mono.Cecil.dll` are intentionally omitted.
Build helper sources locally and obtain dependencies from their authoritative
distribution rather than committing binaries.

The update script deliberately records the managed-code boundary in the
native databases instead of assigning invented names to Xamarin/Mono runtime
functions. It records no credential, universal key, or challenge response.
