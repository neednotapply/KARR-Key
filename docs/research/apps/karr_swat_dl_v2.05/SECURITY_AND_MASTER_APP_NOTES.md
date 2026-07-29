# Security and master-app notes

## Confirmed source observations

- `SWATDealer/Shared.cs` contains a hardcoded API credential-like value.
  It is redacted here and should be rotated.
- `SWATDealer/SettingsSave.cs` stores a password-like value in ordinary
  Plugin.Settings preferences.
- The token, account, role, vehicle search, map bounds, and selected vehicle are
  global static state.
- Search fields and location are concatenated into URL query strings.
- Broad empty catch blocks hide authentication, parsing, and network failures.
- AppCenter identifiers are compiled into the app, and operational/PII data
  must be excluded from analytics/crash breadcrumbs.
- The manifest allows backup and retains obsolete external-storage permission.

## Master-app actions

- remove and rotate embedded API credentials;
- never persist a password;
- store refresh credentials in Keystore-backed encrypted storage;
- clear all account-scoped caches on logout, role change, or account switch;
- enforce dealer scope on the server and return only authorized vehicles;
- avoid PII/location in URLs and diagnostic logs;
- replace empty catches with typed errors and safe user feedback;
- remove obsolete storage permission and define backup/data-extraction rules;
- audit third-party telemetry for VIN, stock, exact location, user/account, and
  token leakage.

The locator should remain read-only. Adding the master app's BLE modules must
not turn a map/search result into authorization to connect to or control that
vehicle.
