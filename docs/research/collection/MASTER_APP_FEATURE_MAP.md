# Master-app feature map

## Executive summary

The four applications cover four different trust roles:

| Capability family | BT Dealer | BT | Installer | SWAT DL |
|---|:---:|:---:|:---:|:---:|
| Authenticated account/session | Yes | Yes | Yes | Yes |
| Dealer inventory and vehicle search | Yes | Limited/current account | Install target lookup | Yes |
| Vehicle map/location | Yes | Yes | Workflow location | Yes |
| Direct BLE unit connection | Yes | Yes | Yes | No |
| Local vehicle controls | Dealer/service | Customer | Test/setup | No |
| Unit settings and diagnostics | Extensive | Customer subset | Installation subset | No |
| CAN file selection/download | Yes | Support lookup | Yes | No |
| Firmware bootload/update | Yes | Protocol support, limited UI | Yes | No |
| Sale/unwind/booking workflow | Yes | No | No | No |
| Fitment/install/repair/removal | Shared service support | No | Yes | No |
| Remote alerts, logs, geofences | No | Yes | No | No |
| Offline operational queue | Sales/location/sync | Cached operational data | Fitment push queue | No clear app queue |
| Push/background messaging | Yes | Yes | No | No |
| Fleet battery/status overview | Inventory status | Single/customer context | Test context | Yes |

No current app is a safe base to which the other three should simply be
copied. KARR BT Dealer has the newest Android target and newest BLE protocol,
but it also mixes UI, global static state, storage, networking, background
work, and device transport. A master app should extract those concerns behind
role-aware modules.

## Feature ownership

### Dealer operations module

Source: KARR BT Dealer 3.131.

- account/dealer selection and multi-dealer state;
- vehicle inventory, serial search, status, and location;
- sale, pending sale, unwind, BCA/booking, return, and offline sale queues;
- tent and snow-mode workflows;
- unit settings, mode/state, battery/ignition, remotes, and violations;
- CAN and firmware update workflow;
- background retrieval, push notifications, and offline synchronization.

### Customer vehicle module

Source: KARR BT 2.15.

- customer login, verification, user management, and vehicle selection;
- BLE favorite/auto-connect;
- arm/lock, disarm/unlock, silent variants, trunk, valet, immobilizer, and
  related vehicle actions;
- alerts and alert configuration;
- geofences, mapping, vehicle logs, trips/violations, configuration, and
  remote request/diagnostic history;
- help, instructions, and contact surfaces.

All control commands must remain gated by authenticated server identity,
authorized vehicle ownership, successful device authentication, unit state,
and explicit user confirmation where appropriate.

### Installer/technician module

Source: KARR Installer 1.39.

- technician summary and schedules;
- account, product, serial, VIN, and vehicle selection;
- barcode/ZXing and camera-assisted entry;
- CAN compatibility/file selection and transfer;
- installation checks, test cases, alarm events, and completion;
- install, repair, replacement/swop, removal, and battery-removal paths;
- offline fitment queue and later push;
- unit settings, diagnostics, and bootloading needed during an authorized job.

### Dealer locator module

Source: KARR SWAT DL 2.05.

- dealer login and account context;
- active/all vehicle list;
- VIN, stock, make, model, color, year, and dealer-scope filtering;
- map/list presentation and marker placement;
- battery/status categories and totals.

SWAT DL is a server/map client. It does not discover, connect to, authenticate
with, or control BLE devices.

## Shared protocol boundary

KARR BT Dealer, KARR BT, and KARR Installer use the same transparent UART BLE
service and characteristic. The older BT and Installer clients use the legacy
`0xAA` packet format. Dealer 3.131 supports that format plus counter (`0xBB`)
and encrypted (`0x55`) packets and a larger command set.

The master app should negotiate protocol capabilities after authentication
instead of choosing a parser based on app role. A single transport session
should expose typed operations, while policy decides which roles may invoke
them.

## Suggested delivery sequence

1. Build identity/session, role policy, server API, and secure storage.
2. Build the BLE session state machine and protocol compatibility tests.
3. Port read-only vehicle inventory, map, and unit-state surfaces.
4. Add customer controls with explicit authorization and confirmations.
5. Add dealer operations and offline transaction queues.
6. Add installer workflows behind technician entitlements and job context.
7. Add firmware/CAN transfer last, with signed artifacts, rollback handling,
   battery/power preconditions, resumable progress, and audit events.

This order produces a useful client before enabling the highest-risk device
mutation paths.
