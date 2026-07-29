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

Prebuilt helper executables and `Mono.Cecil.dll` are intentionally omitted.
Build helper sources locally and obtain dependencies from their authoritative
distribution rather than committing binaries.
