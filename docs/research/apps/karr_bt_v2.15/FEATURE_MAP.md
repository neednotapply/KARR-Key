# Feature map

## Navigation and account

- login and verification-code flow;
- user list, single-user detail, and user editing;
- customer vehicle selection and main/master navigation;
- instructions, help, and contact surfaces.

## Direct vehicle surface

`StartPage.cs` is the central customer control page. Its observed paths include:

- BLE connection and favorite-device auto-connect;
- lock/arm and unlock/disarm;
- silent lock and silent unlock;
- trunk release;
- valet/return-valet;
- immobilizer state;
- remote learning and device settings;
- local status/diagnostic feedback.

These operations must be treated as safety-sensitive. Their presence in code
does not imply every account or unit is entitled to every action.

## Server-backed customer features

- alerts and alert configuration;
- vehicle and user configuration;
- mapping and vehicle locations;
- geofence list and single-geofence editing;
- logs, trips/violations, and remote request history;
- remote diagnostics, acknowledgement, and server message handling.

## Main recovered views

`Login`, `VerificationCode`, `Users`, `UserDetails`, `SingleUser`,
`BluetoothScan`, `StartPage`, `Settings`, `DiagnosticMode`, `Alerts`,
`AlertConfig`, `Config`, `Geofences`, `SingleGeofence`, `Mapping`,
`MappingPage`, `Logs`, `Violations`, `Instructions`, `KarrHelp`, and
`ContactUs`.

See `view_inventory.csv` for resource/class provenance and
`first_party_call_graph.csv` for method-level navigation.
