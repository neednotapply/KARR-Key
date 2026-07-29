# Security and master-app notes

## Confirmed source observations

- `FRMSWTBLEInstaller/Settings/QTAuth.cs` contains static legacy customer and
  dealer authentication key material. Values are redacted and should be
  rotated/retired.
- `FRMSWTBLEInstaller/SettingsSave.cs` stores username, password, user token,
  and device token in ordinary Plugin.Settings preferences.
- API/service credential-like constants exist in shipped managed assemblies.
- `VehicleDetails.cs:2633-2636` contains a production click handler that mutates
  the in-memory installer auth token and displays the device identifier. This
  debug/test behavior should be removed.
- Numerous API calls place identifiers and other fields in query strings.
- The manifest permits backup while account/job/token state is persisted.

## Master-app actions

- Remove the debug/test token mutation and audit equivalent hidden controls.
- Rotate/remove all embedded credentials and static device keys.
- Never persist an installer password.
- Store refresh and authorized device/job keys in Keystore-backed encrypted
  storage and clear them on logout/account removal.
- Bind every mutating action to a server-authorized technician job, account,
  vehicle, unit serial, and expected workflow step.
- Use short-lived signed firmware/CAN artifact access.
- Persist validation evidence and offline operations in an encrypted,
  versioned database with idempotency and conflict state.
- Exclude sensitive stores from Android backup/data extraction.
- Keep device IDs, VINs, account details, tokens, and key material out of
  analytics and logs.

Installer capability is the highest-privilege module in the proposed master
app and should not be enabled merely by hiding or showing navigation items.
