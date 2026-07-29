# KARR Master architecture

## Purpose

KARR Master is a clean-room native Android client that consolidates the useful
behavior recovered from KARR BT 2.15, KARR BT Dealer 3.131, and KARR Installer
1.39. It does not link the Xamarin applications, reuse their bundled
credentials, or copy their static device-authentication keys.

The sibling reverse-engineering workspaces remain the evidence source:

- `../karr_bt_v2.15` for customer workflows and legacy `0xAA` packets;
- `../karr_bt_dealer_v3.131` for dealer workflows and the newest packet codec;
- `../karr_installer_v1.39` for technician jobs and offline field workflows;
- `../CROSS_APP_CODE_REUSE.md` for the cross-application reuse decisions.

## Runtime flow

```text
Compose feature screen
        |
        v
RolePolicy: session + role + capabilities + live authorization
        |
        +---- denied ----> explanatory UI; no transport call
        |
        v
Typed repository / workflow adapter
        |
        +---- server operation ----> authorized backend adapter
        |
        +---- unit operation ------> device authentication + protocol session
                                          |
                                          v
                              serialized Android BLE controller
```

The UI never owns long-lived BLE callbacks or secret material. Customer,
Dealer, and Installer modules describe their role-specific surfaces; shared
state and enforcement remain in `core`, `data`, `protocol`, and `ble`.

## Trust boundaries

### Server session

A production `Session` must be returned by an authorized backend. It carries
roles, capabilities, account scope, expiry, and a live-authorization flag.
Demo sessions are marked explicitly and cannot authorize safety-sensitive
features.

### Device session

The BLE controller only discovers, explicitly connects, enables the recovered
transparent UART endpoint, receives notifications, and serializes writes.
Device challenge response belongs behind `DeviceAuthenticator`; live workflows
must not send a command until that step succeeds for the selected unit and
purpose.

### Storage

Tokens and offline payloads are encrypted using an Android Keystore AES-GCM
key. The offline SQLite queue stores ciphertext, idempotency keys, state, and
timestamps. It does not store legacy APK passwords or static KARR keys.

### High-risk operations

Sale/unwind, fitment, removal, repair-mode changes, CAN transfer, bootloading,
and key management require current server scope, device authentication,
explicit user confirmation, and auditable result handling. Their production
adapters remain disabled until current contracts and authorized fixtures are
provided.

## Protocol compatibility

`QtProtocolCodec` implements:

| Marker | Mode | Compatibility source |
|---:|---|---|
| `0xAA` | Original length/command/payload/checksum | BT and Installer |
| `0xBB` | Counter packet | Dealer 3.131 |
| `0x55` | AES-CBC wrapped counter packet | Dealer 3.131 |

The command catalog includes values `0..78` plus `253`, `254`, and `255`.
Only packet framing is treated as established. Command-specific payload schemas
must be promoted individually from recovered callers and then checked against
authorized packet captures or a physical unit before production use.

## Feature ownership

| Surface | Primary recovered source | Master module |
|---|---|---|
| Vehicle controls, alerts, geofences, logs, users | KARR BT 2.15 | `feature:customer` |
| Inventory, sales/unwind, bookings, service, CAN/firmware | Dealer 3.131 | `feature:dealer` |
| Jobs, install, repair/replacement, removal, offline queue | Installer 1.39 | `feature:installer` |
| Roles, scopes, capabilities, authorization | consolidated model | `core` |
| Login/data/offline persistence | consolidated adapter boundary | `data` |
| Packet framing and authentication interfaces | all three BLE apps | `protocol` |
| Scan/connect/UART/notify/write lifecycle | all three BLE apps | `ble` |

## Production completion criteria

The current APK is a working integration shell and safe hardware diagnostic
client. It becomes a production operational client only after all of these are
available and validated:

1. current server API schema and authenticated role/capability responses;
2. authorized customer, dealer, and installer test accounts;
3. a scoped per-unit device-key/challenge service;
4. test units covering legacy and current firmware/protocol modes;
5. golden packet captures for every command payload that will be sent;
6. signed CAN/firmware artifact metadata and transfer rules;
7. production application ID, signing, maps, push, audit, and privacy setup.
