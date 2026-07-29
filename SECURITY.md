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

The tracked repository must not contain original APKs, full reconstructed
vendor source, IDA databases, native binaries, or recovered secrets. The
`.gitignore` intentionally blocks the common payload formats and workspace
directories.

Production integrations must:

- keep privileged credentials and device keys out of the client;
- authorize account, role, vehicle, and technician-job scope on the server;
- use short-lived, revocable credentials;
- use per-device or per-installation authentication material;
- redact personal, vehicle, challenge, key, and command data from telemetry;
- require explicit confirmation for safety-sensitive actions.

Successful BLE discovery or connection is never sufficient authorization.
