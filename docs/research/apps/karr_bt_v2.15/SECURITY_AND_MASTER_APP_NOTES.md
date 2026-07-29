# Security and master-app notes

## Confirmed source observations

- `QTAP/QTAuth.cs` contains embedded legacy authentication key arrays. Their
  values are redacted here and must not be reused in a new client.
- `QTAP/SettingsSave.cs` stores a password-like value using ordinary
  Plugin.Settings preferences.
- API and third-party token-like constants are present in shipped code.
- The older networking code builds many URLs through string concatenation,
  including account, user, vehicle, and location parameters.
- The app targets Android 30 and lacks Android 12+ BLE runtime permissions.
- The manifest permits Android backup.

## Master-app actions

- Rotate/remove all shipped credentials and legacy static keys.
- Never persist an account password.
- Move refresh credentials and authorized per-device keys to
  Keystore-backed encrypted storage.
- Modernize runtime permissions and permission-denial handling.
- Send sensitive/PII fields in authenticated request bodies rather than URLs.
- Re-check vehicle ownership/capability for each customer control.
- Require clear user intent and result confirmation for lock, unlock, trunk,
  valet, and immobilizer operations.
- Invalidate the local authenticated-device state on every disconnect.
- Treat push and remote requests as untrusted hints until refreshed through an
  authenticated server call.

The recovered code is useful for compatibility, not as a security baseline.
