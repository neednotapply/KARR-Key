# BLE and unit protocol

## Transport

Plugin.BLE discovers the same transparent data endpoint used by Dealer and
Installer:

- service: `49535343-FE7D-4AE5-8FA9-9FAFD205E455`
- characteristic: `49535343-1E4D-4BD9-BA61-23C647249616`

The discovery calls are in `QTAP/App.cs:337-340`. `TransparentData.cs` owns
notification buffering and characteristic access.

## Legacy packet

`QT_Protocol.cs` implements one packet form:

```text
0xAA | length_hi | length_lo | command | payload... | checksum
```

The big-endian length covers command plus payload. The checksum is additive
two's complement; the bytes after the marker, including checksum, sum to zero
modulo 256. `Parse` verifies marker and checksum before exposing command/data.

Unlike Dealer 3.131, this client does not implement the `0xBB` counter or
`0x55` encrypted packet wrappers.

## Commands

`EnumQTCommand.cs` defines the legacy families:

- settings, mode, GPIO, state, VIN, serial, battery;
- CAN file transfer and diagnostics;
- lock/unlock, silent variants, and vehicle control;
- request/provide authentication;
- bootload and PIC2 bootload;
- violations, doors, bump/tilt, and bus state;
- remote learn/read/clear/test;
- disconnect, repair mode, reset/default, and error/ack values.

Payload semantics live in the callers, especially `StartPage.cs`,
`DiagnosticMode.cs`, and `TransparentData.cs`.

## Authentication boundary

The older `QTAuth.cs` includes static legacy customer/dealer key material and a
challenge transformation. Exact values are intentionally not repeated. A
master app should not carry these constants forward. It should use current,
server-authorized per-device key distribution, Keystore-backed storage, and
the newer protocol where the unit supports it.

## Compatibility implementation

Preserve byte compatibility through golden tests, but wrap it behind:

- a cancellable BLE session state machine;
- serialized command dispatch;
- expected-response and timeout rules;
- connection/session generation checks;
- authorization policy above transport;
- explicit protocol negotiation with no silent security downgrade.
