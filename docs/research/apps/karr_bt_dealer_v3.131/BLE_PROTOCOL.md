# BLE and unit protocol

## Transport

The app uses Plugin.BLE and a transparent UART-like GATT endpoint:

- service: `49535343-FE7D-4AE5-8FA9-9FAFD205E455`
- characteristic: `49535343-1E4D-4BD9-BA61-23C647249616`

Discovery is visible in `FRMBLEDealerApp/App.cs:511-514`.
`TransparentData.cs` owns the active device, characteristic, notification
buffering, and command/response plumbing.

## Packet modes

`WebhouseStandard/Quantam/Protocol/QtProtocolParser.cs` implements three modes:

| Marker | Mode | Shape |
|---:|---|---|
| `0xAA` | Original | marker, big-endian command+payload length, command, payload, checksum |
| `0xBB` | Counter | marker, counter, length, command, payload, checksum |
| `0x55` | Encrypted | marker, 16-byte IV, AES-CBC encrypted counter packet |

The checksum is additive two's complement: the covered packet bytes plus the
checksum sum to zero modulo 256. Encrypted mode derives a 128-bit AES key from
the first 16 bytes of the supplied per-unit key and encrypts the counter packet
with zero padding.

The parser checks packet type, minimum size, encoded length, checksum, encrypted
block alignment, and key presence. A master implementation should add bounded
maximum lengths, strict trailing-padding checks, counter replay policy, and
session-generation correlation.

## Authentication

`AuthService` accepts two observed challenge lengths:

- 8-byte legacy challenge using customer/dealer key material;
- 32-byte newer challenge using AES-based processing.

`Webhouse.Data.DeviceData` retrieves authorized key material from device
security APIs and stores it in `SecureStorage`. Exact keys and credential-like
constants are intentionally not repeated here.

### 2026-07-28 capture confirmation

The observed vehicle units advertised as `DR D029A54D` and `DR 0F9ACB56`.
After UART subscription, a valid Original-mode `0xAA` packet carried command
`0x0E` (`RequestAuth`) and an eight-byte challenge. GATT status 19 was the
peer ending the link after that application-authentication request remained
unanswered. Dealer's backend/per-unit key flow, not Android bonding or a
single numeric pairing code, is the relevant current authorization boundary.

## Command families

`EnumQtCommand` defines command values `0..78`, plus `253`, `254`, and `255`.
Families include:

- settings, mode, GPIO, state, VIN, serial, firmware and reset cause;
- CAN file metadata/download/chunks/delete and bus diagnostics;
- lock/unlock, silent variants, secure arm/disarm, and vehicle control;
- authentication and secure-key handling;
- firmware/PIC bootload;
- violations, battery, doors, bump/tilt/movement, alarm events;
- remote learn/read/clear/test;
- snow/elite mode and mode history.

Use the enum and each command handler in `UnitSettingsRefreshed.cs` for exact
payload interpretation. Do not infer a payload schema from the enum name alone.

## Master-app rules

- negotiate supported packet mode and firmware capability per connected unit;
- never silently downgrade authentication or encryption;
- serialize writes and correlate responses;
- invalidate auth/counters on disconnect;
- enforce command-specific timeouts and expected response commands;
- gate vehicle mutation, bootload, key, and enrollment paths by current server
  authorization and explicit workflow state;
- create byte-exact golden tests from authorized device captures before
  changing any packet or payload behavior.
