# Feature map

## Authentication and role state

- dealer login;
- current account name and user type;
- `AccessDealers` capability controlling wider dealer scope;
- logout and local state reset.

## Vehicle search

Observed filters include:

- VIN;
- stock number;
- make;
- model;
- color;
- year;
- local versus other-dealer scope;
- user latitude/longitude;
- active/status restriction.

## Results and presentation

- active vehicle retrieval;
- explicit vehicle search;
- vehicle list with stock/VIN summary;
- map marker placement and calculated map bounds;
- selected vehicle detail;
- distance calculation;
- battery/status categories and totals;
- navigation among locator, list, search, and battery/status screens.

## Recovered views

`App`, `LoginPage`, `MainPage`, `MasterPage`, `MenuList`, `VehiclesSearch`,
`ListAllVehicles`, and `BatteryStatus`.

These XAML resources were compiled into generated C# rather than emitted as
standalone `.xaml` files by ILSpy. `view_inventory.csv` records their original
XAML source paths and recovered classes.

## Explicit non-features

No BLE adapter, scan, GATT UUID, transparent characteristic, device challenge,
packet codec, CAN transfer, firmware transfer, or local vehicle control appears
in the SWAT DL main app. Those belong to the other KARR clients.
