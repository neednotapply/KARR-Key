# BLE and unit protocol

## Transport

The Installer uses Plugin.BLE and the shared transparent UART endpoint:

- service: `49535343-FE7D-4AE5-8FA9-9FAFD205E455`
- characteristic: `49535343-1E4D-4BD9-BA61-23C647249616`

Discovery occurs in `FRMSWTBLEInstaller/App.cs:435-438`.
`TransparentData.cs` carries notification/command state.

## Packet format

`QT_Protocol.cs` is semantically the same legacy codec found in KARR BT:

```text
0xAA | length_hi | length_lo | command | payload... | checksum
```

Length covers command plus payload. The two's-complement checksum makes the
covered bytes sum to zero modulo 256. The parser verifies marker and checksum.
There is no Dealer 3.131-style counter or encrypted wrapper in this main
protocol class.

## Commands and installer use

The legacy enum includes settings, mode, CAN transfer, GPIO, state, VIN,
serial, vehicle controls, authentication, firmware/PIC bootload, violations,
battery, doors, bump/tilt, remote learning, repair mode, and related ack/error
values.

Installer-specific callers concentrate on:

- authentication and unit identity;
- settings/state reads needed for job validation;
- CAN selection and chunk transfer;
- bootload progress;
- installation test actions and result collection;
- authorized repair/removal state.

Payloads must be read from their individual handlers. The command enum is not a
complete schema.

## Legacy authentication

`Settings/QTAuth.cs` contains embedded static customer/dealer authentication
material and the legacy challenge transform. Exact values are redacted. Do not
copy them into a combined client.

The master app should use authenticated server distribution of scoped
per-device/job keys, hardware-backed storage, session invalidation on
disconnect, and protocol negotiation. It must not fall back to static keys
when the network or authorization service is unavailable.

### 2026-07-28 capture confirmation

Installer's managed flow confirms
`RequestAuth(14) -> Read_Mode(34) -> ProvideAuth(15) -> AuthSuccess(16)`.
The Pixel capture of command `0x0E` with an eight-byte challenge is consistent
with this legacy path. Android GATT status 19 followed because the peer ended
the link after the request went unanswered. Optional `Bond51` defaults false;
bonding does not replace application authentication or technician-job scope.

## Transfer safety

For CAN and firmware transfers:

- verify signed artifact identity, unit/product compatibility, length, and
  hash before the first chunk;
- check power/battery/ignition requirements;
- serialize chunks and validate acknowledgements;
- support cancellation only at protocol-safe points;
- re-read installed version/state after completion;
- persist an audit event without secrets or raw challenges.
