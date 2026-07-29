# Connection and authentication evidence

## Confirmed cause of the observed disconnect

The vehicle screenshots show that the phone:

1. established a BLE link;
2. discovered the transparent UART service;
3. enabled notifications;
4. received `AA 00 09 0E ...`.

The packet is a valid Original-mode frame. Command `0x0E` is `RequestAuth` and
its payload is an eight-byte challenge. Android GATT status 19 is
`GATT_CONN_TERMINATE_PEER_USER`: the peripheral ended the link.

The previous master build called the session `Ready` after notification setup,
but it never performed the KARR challenge-response exchange. The unit
eventually terminated that unauthenticated session. Version 0.1.3 now reports
the UART-subscription and authentication states separately and explains status
19 in that context.

## Recovered legacy handshake

KARR BT 2.15 performs this sequence:

1. connect with BLE transport;
2. discover service `49535343-FE7D-4AE5-8FA9-9FAFD205E455`;
3. subscribe to characteristic `49535343-1E4D-4BD9-BA61-23C647249616`;
4. receive `RequestAuth`;
5. issue the read-mode request;
6. calculate `ProvideAuth` from the challenge, unit mode, serial, and
   application authentication key;
7. treat the link as usable only after `AuthSuccess`.

Dealer 3.131 supports both the legacy eight-byte challenge and a newer
32-byte/AES path. It obtains customer, dealer, master, and per-unit key
material through its data/backend layer. It also has an optional Android
bonding setting, but that setting defaults off.

Installer 1.39 uses the same legacy command sequence:
`RequestAuth(14) -> Read_Mode(34) -> ProvideAuth(15) -> AuthSuccess(16)`.
Its optional Android bonding setting also defaults off, and its live workflow
additionally depends on an authorized technician job.

## Pairing-code finding

There is no evidence of one numeric Bluetooth PIN used by every KARR unit.
The older customer APK instead contains shared application-layer customer and
dealer key material. That is why it can answer legacy challenges without a
Bluetooth pairing dialog. The newer Dealer app retrieves key material and may
use a per-unit custom key.

Shared legacy key material is deliberately not copied into KARR Master. It
would turn ownership-neutral BLE discovery into a universal authentication
bypass. The existing `DeviceAuthenticator` boundary expects a production
adapter that obtains an authorized, account- and unit-scoped challenge response
without exposing long-lived keys to the UI or logs.

## Required production input

Maintaining an authenticated session requires one of:

- the current authorized KARR backend challenge-response contract and a test
  account scoped to the vehicle; or
- a vendor-issued per-unit integration key and its documented protocol mode.

Retrying GATT, changing connection priority, or Android bonding cannot replace
the application authentication response demonstrated by the capture.

## Recovered source locations

These managed-code paths are in the full offline evidence archive; curated
findings and annotated native IDA projects are tracked here:

- `karr_bt_v2.15/01_managed/projects/QTAP/QTAP/App.cs`
- `karr_bt_v2.15/01_managed/projects/QTAP/QTAP/TransparentData.cs`
- `karr_bt_v2.15/01_managed/projects/QTAP/QTAP/QTAuth.cs`
- `karr_bt_dealer_v3.131/01_managed/projects/FRMBLEDealerApp/FRMBLEDealerApp/App.cs`
- `karr_bt_dealer_v3.131/01_managed/projects/FRMBLEDealerApp/FRMBLEDealerApp/TransparentData.cs`
- `karr_bt_dealer_v3.131/01_managed/projects/WebhouseStandard/Quantam/Protocol/AuthService.cs`
- `karr_installer_v1.39/01_managed/projects/FRMSWTBLEInstaller/FRMSWTBLEInstaller/TransparentData.cs`
- `docs/research/apps/*/BLE_PROTOCOL.md`
- `research/artifacts/ida/*/*.i64`
