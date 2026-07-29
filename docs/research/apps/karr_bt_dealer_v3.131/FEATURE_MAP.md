# Feature map

## User-facing surfaces

| Surface | Behavior |
|---|---|
| `Login` | Credentials, authentication, remembered state, initial synchronization |
| `AccountsSelection` | Dealer/account selection and multi-dealer context |
| `DownloadData` | Initial and refreshed reference/inventory data |
| `Bluetooth` / `ScanPage` | BLE discovery, device selection, account/vehicle association |
| `MapPage` | Dealer vehicle/location view |
| `UnitSettings` | Legacy unit service/settings surface |
| `Pages.UnitSettingsRefreshed` | Current unit state, settings, controls, firmware/CAN workflows |
| `USerSettings` | User-configurable application state |

`view_inventory.csv` is the complete compiled-XAML inventory.

## Dealer operations

- vehicle and serial search;
- account changes and dealer inventory;
- vehicle products and status;
- sales, pending sales, unwind, offline sale submission;
- BCA/booking creation and return;
- tent-mode and snow-mode data/workflows;
- vehicle statistics and location upload;
- offline data refresh and synchronization.

## Unit operations

The refreshed unit page contains the richest direct-device behavior in this
collection:

- read state, mode, serial, VIN, firmware, battery, ignition, settings;
- write supported settings and modes;
- lock/unlock and related test/service controls;
- learn, read, clear, and test remotes;
- violations and diagnostic state;
- CAN file lookup/download/transfer;
- main/PIC bootload and firmware progress;
- dealer workflow actions tied to the selected unit and vehicle.

These are capabilities observed in code, not an authorization promise. A
combined client must apply server role, account, vehicle, and job policy before
presenting or executing them.

## Background and offline behavior

The app contains a dedicated background data retrieval service, Firebase
messaging, connectivity handlers, location batching, and offline operational
lists. A migration should turn these into explicit WorkManager jobs and
versioned idempotent queue records rather than preserving static background
loops.

## Indexes

- `view_inventory.csv`: compiled UI resources/classes.
- `api_route_index.csv`: literal API routes and source locations.
- `first_party_managed_symbols.csv`: types, methods, fields, properties.
- `first_party_call_graph.csv`: managed call edges.
