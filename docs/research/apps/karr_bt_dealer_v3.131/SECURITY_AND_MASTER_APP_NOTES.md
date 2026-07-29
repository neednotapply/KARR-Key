# Security and master-app notes

## Confirmed source observations

- `FRMBLEDealerApp/SettingsSave.cs:30-32` assigns the same preference literal
  to `LastFirmwareCheckKey` and `MultiDealerModeKey`.
- `SettingsSave` persists a username/password-like value in ordinary
  preferences.
- `FRMBLEDealerApp/App.cs` contains a cloud storage credential in the firmware
  retrieval path. Its value is redacted here and must be rotated.
- `WebhouseStandard/Webhouse/Data/DeviceData.cs` uses `SecureStorage` for
  server-provided customer/dealer/master/per-unit device keys.
- API/service token-like constants and third-party keys are present in shipped
  managed code.
- The Android manifest sets `allowBackup="true"` while the app stores account
  and device-related state.

## Migration actions

- Fix the preference collision with distinct names and an explicit migration.
- Stop storing passwords; retain only short-lived access state and a
  Keystore-protected refresh credential.
- Remove cloud account credentials from the client and rotate them. Obtain
  signed, short-lived firmware/CAN URLs after server authorization.
- Keep per-unit keys in hardware-backed encrypted storage; add deletion on
  logout/account removal and scope every key to its server identity.
- Define Android data extraction/backup rules that exclude sensitive stores.
- Replace query-string PII and concatenated URLs with typed request bodies.
- Make every offline sale/booking/location record idempotent and account scoped.
- Treat background messages as hints; re-authorize and fetch current state
  before acting.

## Role boundary

Dealer access does not imply installer, customer, master-key, or unrestricted
vehicle-control access. The master app should render and execute capabilities
from server-issued entitlements and selected-account/vehicle scope, with
server enforcement on every mutation.
