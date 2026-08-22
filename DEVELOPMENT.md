# Development

## Architecture

KARR Key keeps UI, local data, protocol, and BLE lifetimes separate:

```text
Compose operation
  -> local role/capability policy
  -> recovered device workflow
  -> authenticated BLE session
```

`shared/*` is plain Kotlin/JVM with no Android SDK on its classpath, so the
domain and protocol layers can back a desktop host as well as the app.
`android/*` holds everything that touches the Android SDK.

| Module | Responsibility |
|---|---|
| `shared/core` | Domain models, local roles, capabilities, and device policy |
| `shared/protocol` | Packet framing, parsing, commands, authentication interface |
| `android/app` | Process entrypoint, Bluetooth-first shell, navigation, shared UI |
| `android/data` | Keystore-backed access state and local operation journal |
| `android/ble` | Serialized scan/connect/discover/subscribe/write session |

Keeping `shared/*` platform-free is an invariant, not a convention: adding an
`android.*` import there silently forecloses the desktop host. Anything needing
a platform API takes it as an interface the host installs, the way `KarrTrace`
takes its log sink.

Operation screens do not own long-lived BLE callbacks or secret material.
Transport state is the source of device readiness. Provisioned Customer
material exposes the Customer catalog; provisioned Dealer material exposes
Dealer and Installer catalogs. Catalog access never substitutes for a live
unit's KARR application authentication.

`KarrApplication` owns the process-scoped BLE controller. User-started scan,
connect, and reconnect operations start `BluetoothSessionService`, declared as
Android's `connectedDevice` foreground-service type. The service mirrors BLE
state into a private ongoing notification, reports connectable KARR candidates,
and tears down Bluetooth work if the foreground notification can no longer be
maintained.

## Required invariants

- A BLE candidate classification identifies a radio family, not ownership.
- Connectable possible advertisements allow a manual diagnostic GATT probe but
  remain possible until transport and application authentication provide stronger
  evidence. Broadcast-only advertisements remain diagnostic-only, and automatic
  reconnect remains limited to confirmed-format devices with a stable serial.
- UART subscription confirms transport, not authenticated readiness.
- An 8-byte `REQUEST_AUTH` challenge triggers `READ_MODE`, selects the
  matching locally provisioned Customer or Dealer/Installer material, sends
  `PROVIDE_AUTH`, retains the returned unit model and operating mode, and still
  waits for the unit's `AUTH_SUCCESS`.
- `Ready` requires a valid authentication-success notification for the current
  connection generation.
- Disconnect invalidates authentication, counters, and outstanding responses.
- Writes remain serialized and correlated with expected responses/timeouts.
- Every valid `READ_MODE` notification received in `Ready` replaces the stored
  model and operating mode before command observers are notified. Restricted
  operations therefore follow the unit's most recently read mode.
- A provisioned key grants only its recovered local role surface. Device
  mutations still require the current connection to reach `Ready`.
- Safety-sensitive actions require an authenticated BLE session, local role
  authorization, and compatible identified unit model and operating mode.
- Recovered universal credentials are never compiled into the application.
- Every visible operation sends one distinct recovered device packet. Scanning,
  connection, access keys, and transport diagnostics stay in the Device center.
  Inventory, jobs, accounts, imported records, external artifacts, remote
  history, transactions, and submissions stay excluded.
- Map may request viewed OpenStreetMap tiles, and an explicit VIN-decode action may send the
  collected VIN to NHTSA vPIC automatically, with a manual retry when that lookup fails. No other
  surface may add a backend, account system, tile prefetch, or remote history.
- The map renderer requires `ACCESS_NETWORK_STATE` to detect connectivity before
  it requests raster tiles. `preBuild` checks that this and Map's `INTERNET`
  permission, foreground location permissions, and Bluetooth scan
  `neverForLocation` declaration are explicit.

## BLE and protocol compatibility

The scanner requests every Android BLE result without a name, address, or
service filter. Recovered KARR evidence includes `DR`/`QT` identifiers and
Dealer manufacturer frames with either an embedded serial or a status-only
payload. Android separates the first two manufacturer bytes into an integer
identifier, so the BLE module reconstructs the complete raw AD field before
applying the recovered Dealer parser. A status-only match requires both the
recovered unit-type bit layout and the parser's numeric five-character VIN-tail
shape; unit-type bits by themselves collide with unrelated manufacturer data.
Possible candidates remain visibly labeled and offer a manual connection probe
only when Android reports the advertisement as connectable. The transparent-UART
service is
`49535343-FE7D-4AE5-8FA9-9FAFD205E455` with characteristic
`49535343-1E4D-4BD9-BA61-23C647249616`; because that is a shared Microchip
service, it is supporting evidence rather than KARR identity by itself. A MAC
OUI is likewise not a KARR identifier: public addresses identify an interface
vendor, while BLE also permits static and private random addresses.

Manual and reconnect discovery explicitly use `SCAN_MODE_LOW_POWER`,
`CALLBACK_TYPE_ALL_MATCHES`, and immediate delivery with no report batching.
The manual scan continues until the user stops it, but the app does not keep the
screen awake. The result classifier and 10-second freshness limit remain
unchanged; retaining a stale result is not a range improvement.

After a GATT connection opens, the controller keeps Android's default connection
priority and reads the negotiated transmit/receive PHY on Android 8 and later.
KARR Key reports the observed PHY in Device diagnostics but does not request LE
Coded PHY until a physical unit has demonstrated support.

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
operation while retaining every role, model, operating-mode, and preflight
binding. Operation pages distinguish GATT write acceptance from a matching unit
notification,
execute enabled mutating packets directly, and retain command
metadata in the encrypted local operation journal without recording payload
contents. Read results display only the packet correlated with the active
command; later unsolicited notifications do not replace it. Settings and
violation-inhibit reads use recovered model-specific layouts, while unmapped
flags remain explicitly labeled instead of receiving guessed meanings. The
single expandable Technical details section is always the final box and includes
raw protocol evidence after a response.

The `READ_MODE` response received during authentication is the initial model and
operating-mode discovery source. `Ready` retains the recovered QT6, DR1, DR2,
QT7, CIU, or VW programmer identity and its current Installer, Dealer, User, or
other reported mode without sending a redundant request. Later `READ_MODE`
responses reconcile those same fields immediately. Action metadata is evaluated
against the latest values before a command can be reviewed or sent. Missing
compatibility details leave restricted commands unconfirmed and disabled; they
are not treated as evidence of support.

An authenticated DR1 CAN observation returned `ACK_ERROR Invalid_Command (01)`
for `READ_REMOTES`, so that read is limited to the QT6 products recovered from
the legacy caller and blocked locally for other units. `BUMP_TEST` with its
active payload returned `ACK_ERROR Invalid_Mode (03)` outside the permitted
state; the recovered Installer caller covers QT6 and DR1 CAN products but
reaches the active bump-test workflow only in Installer mode. KARR Key retains the
authenticated mode, labels this as a `Unit mode` restriction, and keeps the
detail selectable while the send button remains disabled. If the current role
has a recovered transition path, the detail offers the appropriate switch or
first step. A hardware mismatch remains labeled `Hardware` and blocks navigation.

`CHANGE_MODE` is modeled as a state transition, not as a successful write. The
Customer User-to-Valet and Valet-to-User actions and every service mode action
send the mutation once, wait for its acknowledgement, then issue a
fresh `READ_MODE`. Only an exact target-mode read-back succeeds. A different,
malformed, rejected, timed-out, or unavailable read-back is retained as a
failure and never causes the mutating command to be retried. The fresh response
also updates `Ready`, so all model/mode restrictions are recomputed immediately.

The Installer mode-switch section exposes only ordinary transitions recovered
from managed callers: Dealer to Installer, No sale, or Borrowed Car Agreement
(BCA); User, Installer, No sale, or Borrowed Car Agreement back to Dealer; and
Dealer or No sale to User. Valet follows the recovered staged path through User
before Dealer. Bootload uses its dedicated entry command rather than
`CHANGE_MODE`, and no ordinary transition into Unconfigured was recovered, so
neither value is offered. The transitions into Installer, Dealer, User, No sale,
and Borrowed Car Agreement have been confirmed by fresh `READ_MODE` on
authorized hardware and are no longer experimental. The app reports protocol
read-back separately from any broader vehicle effect. The Device Operations
catalog retains compact transition summaries. The selected command page adds a
source-based **Mode effect** descriptor: Installer setup and commissioning,
Dealer lot protection, User consumer security, No sale deactivation, BCA
extended-drive behavior, or Valet's temporary consumer-security bypass. These
descriptions explain unit behavior and do not grant a role, capability, or
authentication state.
Because User/Valet use Customer material while service modes use Dealer material,
a later reconnect authenticates with the key family selected by the newly read
mode.

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
reported by the app without an authorized vehicle fixture. The preserved
installer sequence uses `UNLOCK_DOORS` with payload `0` and then instructs the
technician to confirm that the vehicle is not immobilized. This is a separate
operation, not part of the immobilizer packet. The app exposes it as **Restore
drivable state** beside the candidate
and keeps the candidate disabled unless the recovery operation is authorized
and compatible. The technician must still verify physical drivability before
testing; a protocol acknowledgement is not enough.

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

The Gradle build pins its compilation toolchain to JDK 17, so a JDK 17 must be
installed or the build fails fast with a "No compatible toolchains" error rather
than compiling on the wrong JDK. See the environment prerequisites under
[Building the app](README.md#building-the-app). While iterating, build with the
default Gradle daemon (for example `.\gradlew.bat :android:app:assembleDebug`); reserve
`--no-daemon` for the complete pre-handoff check below, which matches CI:

```powershell
.\gradlew.bat --no-daemon :shared:core:test :shared:protocol:test testDebugUnitTest lintDebug assembleDebug
```

`shared/*` are Kotlin/JVM modules, so their tests run under `test`; the
Android-only `testDebugUnitTest` does not cover them.

- Protocol changes require byte-exact positive and negative vectors.
- BLE-classification changes require confirmed, possible, and unrelated
  advertisement cases.
- Device-policy changes require unauthenticated, disconnected, and wrong-role
  cases.
- Android lifecycle or UI changes require an emulator or authorized-device
  smoke test.
- Do not run Gradle's `connectedDebugAndroidTest` task on a Pixel containing
  provisioned KARR Key data. Its cleanup uninstalls the debug target and deletes
  that app data. Use a disposable emulator, or install the assembled app and
  `app-debug-androidTest.apk` directly and invoke
  `com.karr.key.test/androidx.test.runner.AndroidJUnitRunner` so the target app
  remains installed afterward.
- Top-level sections are switched only from the navigation bar/rail. Feature
  details consume the Android back action to return to their parent section.
- Never attach credentials, device keys, raw authentication material,
  vehicle-identifying captures, or arbitrary proprietary binaries.

This repository no longer stores research binaries, so a clone is source-only.
The APKs and IDA databases live in the private evidence repository described in
[research/README.md](research/README.md).

## Live BLE/protocol tracing

Debug builds emit a structured trace of the live BLE, protocol, and
authentication pipeline to logcat, so a connected developer can follow a
real-hardware session — including scanning — without touching the phone. It is
implemented by `KarrTrace` (`shared/core`) and enabled automatically for any
debuggable build by `KarrApplication`; it never runs in a release APK. Because
`shared/core` is platform-free, `KarrTrace` writes through a `Sink` that
`KarrApplication` installs over `android.util.Log` at startup; the default sink
discards everything, so an uninitialised process stays silent. No key material is
logged: the `PROVIDE_AUTH` response payload is redacted at the single wire choke
point regardless of caller.

View it over `adb logcat`, filtering to the KARR tags:

```powershell
adb logcat -s KARR/Ble KARR/Cmd KARR/Auth KARR/Proto KARR/Policy KARR/Sweep KARR/Map KARR/Compat
```

| Tag | Covers |
|---|---|
| `KARR/Ble` | GATT state, connection, and TX/RX packets |
| `KARR/Cmd` | Command exchange requests, responses, and timeouts |
| `KARR/Auth` | Challenge/response authentication |
| `KARR/Proto` | Protocol-level decoding |
| `KARR/Policy` | Local role/capability allow/block decisions |
| `KARR/Sweep` | Auto-sweep scan/connect/read loop |
| `KARR/Map` | Map sightings recorded during a scan |
| `KARR/Compat` | One line per raw command-console send: unit model + command + outcome |

`KARR/Sweep` and `KARR/Map` are the tags to watch during a scan.

### Command compatibility log

Every send from the debug **Command console** emits one `KARR/Compat` line pairing the
connected unit's model with the command and the unit's response, so a testing pass builds a
per-model command-support picture without persisting anything to the app database. Filter to
just that channel:

```powershell
adb logcat -s KARR/Compat
```

Each line reads `<model> | <COMMAND>(<code>) | <outcome> | tx=<hex>`, for example:

```text
DR1 CAN | READ_VIN(0x23) | ACCEPTED rx=READ_VIN 17B | tx=-
DR1 CAN | SET_SECURE_KEY(0x4E) | REJECTED code=0x05 Invalid state | tx=01 02 03
DR1 CAN | BOOTLOAD_ENTER(0x19) | NO_RESPONSE | tx=-
```

Outcomes: `ACCEPTED` / `ACK_OK` (unit recognized and accepted it), `REJECTED code=..`
(recognized but refused — the code says why), `NO_RESPONSE` (no reply within the 5 s window —
often an unrecognized or no-reply command), and `LINK_FAIL` (the BLE write itself failed, so the
result is inconclusive). Grep by model (`DR1 CAN`) or command to see which commands each model
supports.

## Remaining local compatibility work

Completing device workflows requires only local evidence and authorized test
hardware:

- remaining command payload schemas not yet exercised by a managed caller;
- representative units and byte-exact golden BLE captures;
- the newer 32-byte/AES path with unit-scoped provisioning material;
- production application ID, signing, privacy, and backup configuration.

Do not introduce a non-Map network client to fill a compatibility gap. Record
the gap, return to the preserved artifacts and authorized fixtures, and add
byte-exact tests before enabling the affected command.
