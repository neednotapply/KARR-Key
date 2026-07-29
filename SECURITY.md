# Security policy

## Supported state

KARR Master is a research prototype. Only the current `main` branch is expected
to receive security fixes.

## Reporting

Do not open a public issue containing:

- account credentials, API tokens, cloud keys, or signing material;
- vehicle-identifying information;
- raw device-authentication secrets;
- universal or master keys recovered from an APK;
- instructions that bypass enrollment, ownership, role, pairing, firmware, or
  vehicle-control authorization.

Use a private maintainer channel or GitHub private vulnerability reporting once
the repository is hosted. Include the affected commit, file, reproduction
conditions, and the smallest redacted evidence that demonstrates the issue.

## Repository security boundary

This private repository includes a reviewed Git LFS evidence set containing
the four untouched source APKs and annotated native IDA databases under
`research/artifacts`. Those paths are the only exceptions to the generic
binary ignore rules. Do not make the repository public without an explicit
legal, licensing, and secret-exposure review.

The tracked repository must not contain full reconstructed vendor source,
extracted managed binaries, recovered secrets, raw account/vehicle captures,
or arbitrary APK/IDA/native-binary additions. Annotation notes must describe
protocol behavior without reproducing key material.

Production integrations must:

- keep privileged credentials and device keys out of the client;
- authorize account, role, vehicle, and technician-job scope on the server;
- use short-lived, revocable credentials;
- use per-device or per-installation authentication material;
- redact personal, vehicle, challenge, key, and command data from telemetry;
- require explicit confirmation for safety-sensitive actions.

Successful BLE discovery or connection is never sufficient authorization.
