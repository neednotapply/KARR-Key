# Feature map

## Technician context

- login and account/access state;
- technician summary and scheduled work;
- dealer/account and product selection;
- serial validation and unit lookup;
- consumables/reference data.

## Job intake

`VehicleDetails` is the central job router. Observed paths cover:

- new installation;
- repair;
- replacement/swop;
- product/unit removal;
- battery unit removal;
- VIN, stock number, unit serial, and replacement serial capture;
- barcode/ZXing and camera-assisted scanning;
- vehicle make/model/year and CAN support selection;
- job notes and installation options.

## Device and CAN work

- BLE scan/connect/authenticate;
- read unit state/settings/serial/firmware;
- select supported vehicle CAN definition;
- retrieve and transfer CAN files;
- configure relevant unit settings and features;
- main/PIC firmware bootload paths;
- battery/power and unit-ready checks.

## Installation validation

- installation test cases/suites;
- recorded test data and alarm/control-room events;
- per-step checks and validation results;
- completion submission;
- repair/removal validation;
- offline save and later `PushOffline`.

## Main views/layouts

`Login`, `InstallerSummary`, `VehicleDetails`, `VehicleDetailsLayout`,
`SelectUnit`, `Bluetooth`, `BTConnectedSettings`, `SelectVehicleCan`,
`CanSettings`, `CanFeatures`, `InstallationChecks`, `InstallationComplete`,
`Repair`, `Removal`, `PushOffline`, `UnitPowered`, `BarCodeScanner`,
`ScanPageZxing`, and `zxingScanPage`.

The exact compiled-XAML inventory is `view_inventory.csv`.

## Master-app boundary

Installer features must require a current technician entitlement and an
authorized job/account/vehicle context. Device discovery alone must never make
fitment, key, CAN, bootload, repair, or removal operations available.
