# KARR Key desktop — Windows BLE helper

A small native process that gives the desktop host real Bluetooth LE on Windows.
The JVM cannot reliably drive BLE cross-platform, so the desktop host talks to
this WinRT helper over a line-delimited JSON protocol on stdio. The helper does
**raw transport only** (scan / connect / discover the UART service / subscribe /
write / notify); the KARR protocol and the legacy authentication handshake live
in the JVM `WindowsBleController`, which is unit-tested against a fake transport.

## Build

Requires the **.NET 8 SDK** and **Windows 10 build 19041+** (for the BLE APIs).

```
dotnet publish src/desktop/blebridge/KarrBleBridge.csproj -c Release -r win-x64 --self-contained false
```

Place the resulting `KarrBleBridge.exe` in a `blebridge/` folder next to the
desktop app (or point `KARR_BLE_HELPER` at it).

## Run the desktop app against real hardware

```
setx KARR_BLE windows
```

(or set it for the session). With `KARR_BLE=windows` — or by default once this
helper is built and present — the host uses `WindowsBleController`. If the helper
is missing or fails to start, the host logs a warning and scanning is disabled
(no phantom unit); set `KARR_BLE=mock` to use the built-in simulated unit for
UI/terminal work without hardware.

## Protocol

JVM → helper (one JSON object per line):

| line | meaning |
|---|---|
| `{"cmd":"startScan"}` | begin active advertisement scanning |
| `{"cmd":"stopScan"}` | stop scanning |
| `{"cmd":"connect","address":"AA:BB:CC:DD:EE:FF"}` | connect + discover UART + subscribe |
| `{"cmd":"disconnect"}` | drop the connection |
| `{"cmd":"write","data":"<hex>"}` | write bytes to the UART characteristic |
| `{"cmd":"shutdown"}` | disconnect and exit |

helper → JVM:

| line | meaning |
|---|---|
| `{"event":"scan","address":..,"name":..,"rssi":..,"connectable":..,"services":[..],"manufacturerData":["<hex>"]}` | one advertisement; `manufacturerData` entries are complete records (2-byte company id + data) |
| `{"event":"connected","address":..}` | GATT connected |
| `{"event":"uartReady"}` | UART characteristic subscribed — ready for the handshake |
| `{"event":"notification","data":"<hex>"}` | a notification frame |
| `{"event":"disconnected","status":N}` | link closed |
| `{"event":"error","message":..}` | a recoverable error |

## Status

Source-backed, pending hardware validation. The JVM orchestration (classification,
the REQUEST_AUTH → READ_MODE → PROVIDE_AUTH → AUTH_SUCCESS handshake, patched-unit
detection) is verified by `WindowsBleControllerTest`; this helper's WinRT calls
have not yet been exercised against a physical Windows machine and a KARR unit.
Treat it as the recovered families are treated elsewhere: confirmed in source,
validated on hardware separately.
