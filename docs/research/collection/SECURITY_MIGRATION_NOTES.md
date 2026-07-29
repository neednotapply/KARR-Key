# Security migration notes

## Confirmed patterns in the shipped clients

The recovered binaries contain several forms of embedded sensitive material:

- credential-like API and service constants;
- map/telemetry identifiers;
- a cloud storage credential in Dealer's firmware path;
- legacy static device-authentication material in the older BT client;
- plaintext username/password preferences in multiple apps;
- long-lived token-like values in preferences;
- broad Android backup eligibility.

Exact values are intentionally not duplicated in this document. They remain
visible in the preserved source reconstruction for authorized remediation.

Dealer 3.131 also contains a concrete preference-key collision:
`LastFirmwareCheckKey` and `MultiDealerModeKey` both use
`lastfwarecheckkey`. One setting can therefore overwrite or reinterpret the
other.

## Required migration

1. Rotate every credential or token shipped in any APK; assume extraction.
2. Remove privileged cloud credentials from clients. Use authorized,
   short-lived signed artifact URLs.
3. Replace embedded legacy device keys with server-authorized, per-device or
   per-installation key distribution and hardware-backed storage.
4. Store refresh tokens and device keys in Android Keystore-backed encrypted
   storage. Never persist account passwords.
5. Set backup/data-extraction rules explicitly and exclude sensitive stores.
6. Send authenticated data in request bodies/headers, not query strings.
7. Enforce role, account, vehicle, and technician-job scope on the server.
8. Add certificate/network security policy, but do not treat client pinning as
   a replacement for server authorization.
9. Redact tokens, PII, challenges, device keys, firmware URLs, and raw vehicle
   commands from logs and crash analytics.
10. Version every offline record and use idempotency keys for server mutations.

## BLE security boundary

Successful BLE discovery is not authorization. The master app must require:

- an authenticated application session;
- current role/capability data;
- authorized vehicle or installation-job scope;
- successful unit challenge/response;
- a fresh connection/session generation;
- protocol/counter validation;
- explicit confirmation for hazardous controls;
- server-side authorization for firmware, CAN, enrollment, sale, repair, and
  removal operations.

No feature should fall back to a customer, dealer, installer, or master key
because a network call fails.

## Remediation evidence locations

Use the per-app `SECURITY_AND_MASTER_APP_NOTES.md` files for source locations.
Use `artifact_provenance.csv` and `managed_binary_hashes.csv` to tie a finding
to an exact shipped binary.
