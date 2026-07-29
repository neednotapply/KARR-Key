# Migration status

## Implemented and locally testable

- consolidated project and role-aware navigation;
- complete feature inventory for Customer, Dealer, and Installer;
- authorization decisions based on session, role, capability, and live
  authorization state;
- BLE permissions, recovered identifier/manufacturer-frame classification,
  separate live KARR-candidate and all-nearby counts, an optional diagnostic
  list for unrelated advertisements, explicit candidate-only connect, UART
  discovery, notifications, protocol-authentication state detection,
  human-readable GATT disconnect handling, and serialized writes;
- original, counter, and encrypted packet construction/parsing;
- checksum, truncation, length, encryption, IV, and bad-packet tests;
- secure token/key-value primitive backed by Android Keystore;
- encrypted durable offline operation queue;
- safe demo repository and sample account/vehicle/job state;
- debug APK build and unit-test entry points.

## Integration-dependent

The APKs do not provide an authorized development backend account, current API
contract, signing configuration, device-key service contract, or physical unit
test fixture. Therefore these paths are represented by typed interfaces and
disabled in demo mode:

- production login and token refresh;
- server role/capability retrieval;
- live dealer sale/unwind/booking submission;
- live customer vehicle mutations;
- installer fitment/repair/removal submission;
- server-authorized device key retrieval and challenge response;
- signed CAN/firmware artifact download;
- physical-unit command payload confirmation;
- push messaging and production maps.

## Next integration inputs

- current backend base URL and API schema;
- authorized test credentials for each role;
- backend-provided capability vocabulary;
- test vehicles/units and expected protocol versions;
- authorized packet captures for payload golden vectors;
- firmware/CAN signing and download contract;
- app ID, signing identity, maps key, and push configuration.

No embedded value from the recovered APKs should be used as a substitute.
