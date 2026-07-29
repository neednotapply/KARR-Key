# Development

## Architecture

KARR Master keeps UI, authorization, data, protocol, and BLE lifetimes
separate:

```text
Compose feature
  -> role/capability policy
  -> typed repository or workflow boundary
  -> authorized backend and/or authenticated BLE session
```

| Module | Responsibility |
|---|---|
| `app` | Process entrypoint, single-role demo/login shell, navigation, shared UI |
| `core` | Domain models, sessions, capabilities, and authorization policy |
| `data` | Backend boundary, Keystore-backed storage, offline operations |
| `protocol` | Packet framing, parsing, commands, authentication interface |
| `ble` | Serialized scan/connect/discover/subscribe/write session |
| `feature:customer` | Customer vehicle surface |
| `feature:dealer` | Dealer inventory/service surface |
| `feature:installer` | Technician job surface |

Feature screens do not own long-lived BLE callbacks or secret material.
Transport does not decide authorization. Demo sessions never authorize
safety-sensitive operations.

## Required invariants

- A BLE candidate classification identifies a radio family, not ownership.
- UART subscription confirms transport, not authenticated readiness.
- `Ready` requires a valid authentication-success notification for the current
  connection generation.
- Disconnect invalidates authentication, counters, and outstanding responses.
- Writes remain serialized and correlated with expected responses/timeouts.
- Customer controls require current vehicle scope; Dealer mutations require
  account/vehicle scope; Installer work requires an authorized technician job.
- Firmware, CAN, enrollment, key, sale, repair, and removal paths require
  server authorization, compatible signed artifacts where applicable, explicit
  confirmation, and auditable result handling.
- No workflow falls back to a recovered static key when network or
  authorization services are unavailable.

## BLE and protocol compatibility

Recovered KARR candidates use `DR`/`QT` identifiers and the transparent-UART
service `49535343-FE7D-4AE5-8FA9-9FAFD205E455` with characteristic
`49535343-1E4D-4BD9-BA61-23C647249616`.

`QtProtocolCodec` supports Original `0xAA`, Counter `0xBB`, and Encrypted
`0x55` packets. Packet framing is established compatibility behavior;
command-specific payload schemas must be confirmed individually from callers,
authorized captures, and physical fixtures. Authentication must negotiate the
supported unit mode without silent downgrade.

## Validation

Run the complete check before handing off a change:

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug
```

- Protocol changes require byte-exact positive and negative vectors.
- BLE-classification changes require confirmed, possible, and unrelated
  advertisement cases.
- Authorization changes require denied demo/expired/wrong-role cases.
- Android lifecycle or UI changes require an emulator or authorized-device
  smoke test.
- Never attach credentials, device keys, raw authentication material,
  vehicle-identifying captures, or arbitrary proprietary binaries.

CI intentionally skips Git LFS because research artifacts are not build
inputs.

## External integration requirements

Production behavior remains blocked on:

- current backend schemas and authorized test accounts for each role;
- server-issued role/capability and account/vehicle/job scope;
- a server-authorized per-unit challenge-response contract;
- representative units and golden packet captures;
- signed firmware/CAN metadata and transfer rules;
- production application ID, signing, maps, push, audit, privacy, backup, and
  telemetry configuration.

Do not substitute values recovered from the archived applications for these
requirements.
