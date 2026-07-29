# Bluetooth identifier evidence

This scanner keeps every observed BLE address but separates KARR candidates
from unrelated nearby devices. A format match identifies an advertisement
family; it does not establish account ownership or authorize vehicle commands.

## Confirmed recovered formats

| Identifier | Treatment | Evidence |
|---|---|---|
| `DR ` or `QT ` plus 7-8 uppercase hexadecimal characters | Confirmed KARR format | Dealer 3.131 `DeviceModel.cs` exact regex |
| Valid 26-byte manufacturer record with an embedded `DR`/`QT` name | Confirmed KARR format | Dealer 3.131 `DeviceModel.cs` |
| Valid 26-byte manufacturer record with a 13-character Crockford-style serial | Confirmed KARR format | Dealer 3.131 `DeviceModel.cs` |

KARR BT 2.15, KARR Installer 1.39, and KARR Security 0.89 all accept both
`DR` and `QT` name prefixes. This is why `DR D029A54D` and `DR 0F9ACB56`
from the vehicle scan are KARR candidates; version 0.1.1 incorrectly omitted
the `DR` family.

The Dealer format requires nonzero unit-type bits. Dealer 3.131 knows enum
labels for QT6 CAN/Blue/Green/Red, DR1 CAN, VW Programmer, CIU, DR2 ESP, and
QT7 Green, but still accepts unknown nonzero values. The master scanner retains
those unknown values as Possible so later hardware is not silently lost.

## Possible candidates

| Identifier | Why it remains possible rather than confirmed |
|---|---|
| Other names beginning `DR` or `QT` | Accepted by the older apps, but too broad to prove the exact Dealer serial format |
| A shorter manufacturer record with valid Dealer unit-type bits | Dealer 3.131 accepts this structure, but it can collide with unrelated manufacturer data |
| `49535343-FE7D-4AE5-8FA9-9FAFD205E455` | The apps use it, but Microchip documents it as a generic Transparent UART service |
| Names beginning `KARR` or `SWDS` | Useful branding hints, but not a recovered radio-name requirement |
| A standalone 13-character Dealer serial-style name | It resembles the recovered embedded format but lacks its manufacturer-frame proof |

## Diagnostic behavior

- The running display reports both KARR candidates and all nearby BLE devices.
- Other nearby devices are retained behind **Show Other Nearby** and cannot be
  selected for connection.
- A later advertisement can promote an address from Other to Possible or
  Confirmed as stronger name, service, or manufacturer data arrives.
- Scanning never auto-connects and never sends a command.

The under-dash green button shown with the vehicle is documented as an
override/valet control in the public KARR BT user guide. The guide does not say
that pressing it enables Bluetooth advertising, so the scanner does not depend
on that button.

## Primary source locations

- `../karr_bt_v2.15/01_managed/projects/QTAP/QTAP/App.cs`
- `../karr_installer_v1.39/01_managed/projects/FRMSWTBLEInstaller/FRMSWTBLEInstaller/App.cs`
- `../karr_bt_dealer_v3.131/01_managed/projects/FRMBLEDealerApp/Quantam/Device/DeviceModel.cs`
- `../../karr_xapk_compare/karr_v0.89_original_decomp_ida/01_managed/projects/SWDSCustomer/SWDSCustomer/App.cs`
- <https://fccid.io/2ASRL-1067/User-Manual/User-manual-4521972.pdf>
- <https://onlinedocs.microchip.com/oxy/GUID-99E91F8E-E9F7-4C2C-B98A-E9662A2ABA50-en-US-3/GUID-689D93AA-77F4-4222-B593-631DF60C9F67.html>
