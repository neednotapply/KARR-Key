# Master-app architecture

## Target shape

Use one Android shell with role-gated feature modules and shared infrastructure:

```text
Android application shell
  -> identity and session
  -> role/capability policy
  -> navigation composition
  -> vehicle/inventory domain
  -> server API clients
  -> offline operation queues
  -> maps/location
  -> notifications/background work
  -> BLE device gateway
       -> scan and connect
       -> service discovery
       -> authentication
       -> protocol negotiation
       -> typed command dispatcher
       -> legacy 0xAA codec
       -> counter 0xBB codec
       -> encrypted 0x55 codec
       -> CAN/firmware transfer coordinator
```

Role policy belongs above transport. The BLE layer should know how to perform
an operation, not whether a customer, dealer, or technician is permitted to
request it.

## Recommended modules

| Module | Responsibility |
|---|---|
| `Identity` | Login, refresh/session tokens, account selection, logout, expiry |
| `Authorization` | Server-derived roles, capabilities, vehicle/job scope |
| `Vehicles` | Canonical vehicle/unit/account models and cached read state |
| `DealerOps` | Inventory, sale/unwind/booking, tent/snow operations |
| `Customer` | Controls, alerts, logs, geofences, remote requests |
| `Installer` | Job context, fitment, checks, CAN, repair/removal |
| `Locator` | Search, list, map, marker clustering, status/battery summaries |
| `DeviceGateway` | BLE lifecycle, authentication, protocol codecs, commands |
| `ArtifactTransfer` | Signed CAN/firmware download, verification, transfer |
| `OfflineQueue` | Durable, idempotent, per-operation retry and conflict state |
| `Audit` | Security-sensitive local and server event correlation |
| `Platform` | Permissions, notifications, location, background scheduling |

## BLE session state machine

Use one explicit, cancellable state machine:

```text
Idle
  -> PermissionReady
  -> Scanning
  -> Connecting
  -> DiscoveringUART
  -> Authenticating
  -> NegotiatingProtocol
  -> Ready
  -> TransferringArtifact (optional)
  -> Disconnecting
  -> Idle
```

Every transition needs a timeout, cancellation path, and a typed failure.
Unexpected disconnects must invalidate authentication and in-flight command
state. Responses should be correlated with command, counter where present, and
session generation so stale callbacks cannot satisfy a new request.

## Data and API migration

The recovered clients duplicate endpoint construction and transport logic.
Replace that with generated or strongly typed API clients:

- one authenticated handler adds short-lived tokens;
- base URLs and public client configuration come from signed environment
  configuration, not compiled secrets;
- endpoint calls return typed success/error results;
- request cancellation and retry rules are explicit;
- mutating calls use idempotency keys;
- PII is excluded from URLs and logs;
- offline operations persist a versioned payload, account/vehicle scope,
  attempt state, server idempotency key, and last error.

The `api_route_index.csv` in each app is a migration inventory, not a claim
that every route in a bundled shared library is reachable from that app.

## Storage

Use Android Keystore-backed encrypted storage for refresh tokens, device keys,
and other credential material. Store ordinary UI preferences separately.
Create a small encrypted application database for vehicles, jobs, maps, alerts,
and durable operation queues. Add a schema version and migrations from day one.

Do not copy the existing plaintext username/password preference pattern or the
shared static global state.

## Firmware and CAN artifacts

The master app should never contain a cloud storage account key. The server
should issue short-lived signed URLs or stream authorized artifacts. Before a
transfer:

- verify signed metadata, expected unit/product, version, length, and SHA-256;
- enforce battery/ignition and authorized-job preconditions;
- persist resumable transfer state where the unit protocol permits it;
- never mark success until the unit reports completion and the app re-reads
  the installed version/state;
- record an audit event without logging key material or raw authentication
  challenges.

## UI composition

After login, request roles and capabilities from the server, then compose the
navigation graph. Hidden screens are not an authorization boundary: every
view model/use case and every server mutation must re-check capability and
scope.

Keep shared read-only surfaces—vehicle picker, search, map, unit summary—as
common components. Keep customer controls, dealer transactions, and installer
mutations separate so their confirmation, validation, and audit rules remain
clear.

## Test seams

- byte-exact golden vectors for all three packet modes;
- parser fuzzing for length, checksum, padding, and truncated messages;
- simulated BLE adapter for disconnect/reconnect/race tests;
- contract tests for every migrated API route;
- offline queue retry/idempotency/conflict tests;
- role matrix tests proving forbidden screens and operations remain forbidden;
- firmware/CAN transfer tests with power loss, wrong artifact, timeout, and
  version verification;
- Android permission tests from API 21 through the current target.
