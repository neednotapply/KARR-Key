# Development

## Architecture

KARR Master keeps UI, local data, protocol, and BLE lifetimes separate:

```text
Compose feature
  -> local role/capability workspace
  -> typed local repository or workflow boundary
  -> authenticated BLE session
```

| Module | Responsibility |
|---|---|
| `app` | Process entrypoint, Bluetooth-first shell, navigation, shared UI |
| `core` | Domain models, local roles, capabilities, and device policy |
| `data` | Local workspace, Keystore-backed storage, local operation journal |
| `protocol` | Packet framing, parsing, commands, authentication interface |
| `ble` | Serialized scan/connect/discover/subscribe/write session |
| `feature:customer` | Customer vehicle surface |
| `feature:dealer` | Dealer inventory/service surface |
| `feature:installer` | Technician job surface |

Feature screens do not own long-lived BLE callbacks or secret material.
Transport state is the source of device readiness. Local role selection never
substitutes for KARR application authentication.

## Required invariants

- A BLE candidate classification identifies a radio family, not ownership.
- Possible or broadcast-only advertisements remain diagnostic-only; connection
  requires a connectable `CONFIRMED_FORMAT` result.
- UART subscription confirms transport, not authenticated readiness.
- An 8-byte `REQUEST_AUTH` challenge triggers `READ_MODE`, selects the
  matching locally provisioned Customer or Dealer/Installer material, sends
  `PROVIDE_AUTH`, and still waits for the unit's `AUTH_SUCCESS`.
- `Ready` requires a valid authentication-success notification for the current
  connection generation.
- Disconnect invalidates authentication, counters, and outstanding responses.
- Writes remain serialized and correlated with expected responses/timeouts.
- Safety-sensitive actions require an authenticated BLE session and explicit
  confirmation.
- Firmware and CAN paths additionally require compatible signed local
  artifacts, power preconditions, acknowledged transfer, and verification.
- Recovered universal credentials are never compiled into the application.
- The visible feature catalog contains only local-record or direct-Bluetooth
  workflows; server accounts, push alerts, maps, remote history, transactions,
  and submissions stay excluded.
- The application manifest must not request `INTERNET` or
  `ACCESS_NETWORK_STATE`; `preBuild` enforces this contract.

## BLE and protocol compatibility

Recovered KARR candidates use `DR`/`QT` identifiers and the transparent-UART
service `49535343-FE7D-4AE5-8FA9-9FAFD205E455` with characteristic
`49535343-1E4D-4BD9-BA61-23C647249616`.

`QtProtocolCodec` supports Original `0xAA`, Counter `0xBB`, and Encrypted
`0x55` packets. Packet framing is established compatibility behavior;
command-specific payload schemas must be confirmed individually from callers,
authorized captures, and physical fixtures. Authentication must negotiate the
supported unit mode without silent downgrade.

The implemented legacy challenge transform is source-faithful for the
recovered 8-byte flow. Customer/Valet mode incorporates the recovered unit
serial positions; other legacy modes use Dealer/Installer material. Values
must be explicitly entered in the Device center and are encrypted with
Android Keystore. No research key is present in source, tests, resources,
BuildConfig, logs, or release artifacts. A 32-byte challenge is rejected until
an authorized per-unit AES provisioning path is implemented.

The local APKs, managed decompilations, smali, and IDA databases are the
compatibility source of truth. Managed C# callers are preferred for
application behavior; the IDA databases primarily explain Xamarin/Mono, JNI,
TLS, and startup boundaries. None of these research artifacts are runtime
dependencies.

## Validation

Run the complete check before handing off a change:

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug
```

- Protocol changes require byte-exact positive and negative vectors.
- BLE-classification changes require confirmed, possible, and unrelated
  advertisement cases.
- Device-policy changes require unauthenticated, disconnected, and wrong-role
  cases.
- Android lifecycle or UI changes require an emulator or authorized-device
  smoke test.
- Horizontal swipes change only primary sections. Feature details consume the
  Android back action to return to their parent section.
- Never attach credentials, device keys, raw authentication material,
  vehicle-identifying captures, or arbitrary proprietary binaries.

CI intentionally skips Git LFS because research artifacts are not build
inputs.

## Remaining offline compatibility work

Completing device workflows requires only local evidence and authorized test
hardware:

- exact command payload schemas traced from the decompiled managed callers;
- representative units and byte-exact golden BLE captures;
- the newer 32-byte/AES path with unit-scoped provisioning material;
- local vehicle/work-record schemas and import/export formats;
- signed firmware/CAN metadata, compatibility checks, and transfer rules;
- production application ID, signing, privacy, and backup configuration.

Do not introduce a network client to fill a compatibility gap. Record the gap,
return to the preserved artifacts and authorized fixtures, and add byte-exact
tests before enabling the affected command.
