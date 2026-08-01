# Development

## Architecture

KARR Key keeps UI, local data, protocol, and BLE lifetimes separate:

```text
Compose operation
  -> local role/capability policy
  -> recovered device workflow
  -> authenticated BLE session
```

| Module | Responsibility |
|---|---|
| `app` | Process entrypoint, Bluetooth-first shell, navigation, shared UI |
| `core` | Domain models, local roles, capabilities, and device policy |
| `data` | Keystore-backed access state and local operation journal |
| `protocol` | Packet framing, parsing, commands, authentication interface |
| `ble` | Serialized scan/connect/discover/subscribe/write session |

Operation screens do not own long-lived BLE callbacks or secret material.
Transport state is the source of device readiness. Provisioned Customer
material exposes the Customer catalog; provisioned Dealer material exposes
Dealer and Installer catalogs. Catalog access never substitutes for a live
unit's KARR application authentication.

`KarrApplication` owns the process-scoped BLE controller. User-started scan,
connect, and reconnect operations start `BluetoothSessionService`, declared as
Android's `connectedDevice` foreground-service type. The service mirrors BLE
state into a private ongoing notification, alerts only for newly discovered
connectable confirmed-format devices, and tears down Bluetooth work if the
foreground notification can no longer be maintained.

## Required invariants

- A BLE candidate classification identifies a radio family, not ownership.
- Possible or broadcast-only advertisements remain diagnostic-only; connection
  requires a connectable `CONFIRMED_FORMAT` result.
- UART subscription confirms transport, not authenticated readiness.
- An 8-byte `REQUEST_AUTH` challenge triggers `READ_MODE`, selects the
  matching locally provisioned Customer or Dealer/Installer material, sends
  `PROVIDE_AUTH`, retains the returned unit model, and still waits for the
  unit's `AUTH_SUCCESS`.
- `Ready` requires a valid authentication-success notification for the current
  connection generation.
- Disconnect invalidates authentication, counters, and outstanding responses.
- Writes remain serialized and correlated with expected responses/timeouts.
- A provisioned key grants only its recovered local role surface. Device
  mutations still require the current connection to reach `Ready`.
- Safety-sensitive actions require an authenticated BLE session, local role
  authorization, and a compatible identified unit model.
- Recovered universal credentials are never compiled into the application.
- Every visible operation sends one distinct recovered device packet. Scanning, connection,
  access keys, and transport diagnostics stay in the Device center. Inventory,
  jobs, accounts, imported records, external artifacts, support links, maps,
  remote history, transactions, and submissions stay excluded.
- The application manifest must not request `INTERNET` or
  `ACCESS_NETWORK_STATE`; `preBuild` enforces this contract.

## BLE and protocol compatibility

The scanner requests every Android BLE result without a name, address, or
service filter. Recovered KARR evidence includes `DR`/`QT` identifiers and
Dealer manufacturer frames with either an embedded serial or a status-only
payload. Android separates the first two manufacturer bytes into an integer
identifier, so the BLE module reconstructs the complete raw AD field before
applying the recovered Dealer parser. Possible candidates remain visible but
diagnostic-only. The transparent-UART service is
`49535343-FE7D-4AE5-8FA9-9FAFD205E455` with characteristic
`49535343-1E4D-4BD9-BA61-23C647249616`; because that is a shared Microchip
service, it is supporting evidence rather than KARR identity by itself. A MAC
OUI is likewise not a KARR identifier: public addresses identify an interface
vendor, while BLE also permits static and private random addresses.

`CONFIRMED_FORMAT` means that the identifier and parser are explicit in the
recovered KARR source material. It is intentionally separate from physical
hardware validation.

Real-hardware validation currently covers a unit advertising as `DR`. Treat
the other recovered families and formats as source- and fixture-backed until
each is separately observed and exercised on physical hardware.

`QtProtocolCodec` supports Original `0xAA`, Counter `0xBB`, and Encrypted
`0x55` packets. Packet framing is established compatibility behavior;
command-specific payload schemas must be confirmed individually from callers,
authorized captures, and physical fixtures. Authentication must negotiate the
supported unit mode without silent downgrade.

`RecoveredWorkflowCatalog` contains only packets backed by recovered managed
callers. The user-facing catalog groups identical command/payload pairs into one
operation while retaining every role, model, and preflight binding. Operation
pages distinguish GATT write acceptance from a matching unit notification,
execute enabled mutating packets directly, and retain command
metadata in the encrypted local operation journal without recording payload
contents. Read results display only the packet correlated with the active
command; later unsolicited notifications do not replace it. Settings and
violation-inhibit reads use recovered model-specific layouts, while unmapped
flags remain explicitly labeled instead of receiving guessed meanings. The
single expandable Technical details section is always the final box and includes
raw protocol evidence after a response.

The `READ_MODE` response received during authentication is also the model
discovery source. `Ready` retains the recovered QT6, DR1, DR2, QT7, CIU, or VW
programmer identity without sending a second request. Action metadata is
evaluated against that model before a command can be reviewed or sent. An
unrecognized or missing model leaves restricted commands unconfirmed and
disabled; it is not treated as evidence of support.

The customer trunk action retains the recovered `VEHICLE_CONTROL` selector
`10`, but it is not a universal KARR output. Both recovered customer callers
show the control only for `QTRED`/`RED` products. The offline workflow therefore
reads the unit mode/type first and sends `AA 00 02 18 0A DC` only to a QT6 RED
unit in User or Valet mode. Other units are blocked locally rather than being
sent a command their firmware rejects with `ACK_ERROR Invalid_Mode`.

Silent Lock/Unlock are also capability-dependent. The recovered KARR BT
customer app exposes the dedicated commands only for compatible product and
tone-setting combinations; when arm/disarm tones are already off, it hides
the dedicated controls and uses ordinary Lock/Unlock, which are silent under
that setting. The offline workflow reads mode, unit type, and settings first,
then either sends the dedicated `SILENT_LOCK`/`SILENT_UNLOCK` frame, routes to
`LOCK_DOORS`/`UNLOCK_DOORS` when tones are off, or blocks an unsupported
configuration with a local explanation.

Customer Lock/Unlock actions are state-reconciled when the unit does not return
a matching command response. The app does not resend the mutating command. It
instead performs a fresh `READ_MODE` and treats the operation as satisfied only
when the returned Armed/Disarmed state matches the requested action. A
contradictory or unavailable state remains a failure, and the UI continues to
state that the physical door position is not confirmed.

Successful diagnostic reads are decoded locally before display. Battery and
state voltage fields are little-endian millivolts; vehicle state uses the
recovered door, ignition, armed, inhibit, and optional extended-position
layout; violation history uses a four-byte unit clock plus twelve five-byte
timestamp/source slots. The raw protocol frame remains available under an
expandable technical section so decoded labels never replace capture evidence.

The Installer immobilizer-output operation is explicitly experimental. Its hidden
installer handler supplies the candidate `VEHICLE_CONTROL` output selector
`2`, producing the Original frame `AA 00 02 18 02 E4`. The operation first requires
a QT-family model plus a `READ_STATE` response with ignition off, sends the
candidate exactly once, and displays TX/RX evidence. DR1 and DR2 models are
blocked before review. GATT acceptance, protocol acknowledgement, and physical
immobilizer behavior are separate observations; only the first two can be
reported by the app without an authorized vehicle fixture.

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

- remaining command payload schemas not yet exercised by a managed caller;
- representative units and byte-exact golden BLE captures;
- the newer 32-byte/AES path with unit-scoped provisioning material;
- production application ID, signing, privacy, and backup configuration.

Do not introduce a network client to fill a compatibility gap. Record the gap,
return to the preserved artifacts and authorized fixtures, and add byte-exact
tests before enabling the affected command.
