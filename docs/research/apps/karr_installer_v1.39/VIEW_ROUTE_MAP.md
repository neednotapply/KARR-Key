# View and route map

```text
Login
  -> InstallerSummary
  -> VehicleDetails
       -> scan/capture: BarCodeScanner / ScanPageZxing / zxingScanPage
       -> SelectUnit
            -> Bluetooth
            -> BTConnectedSettings
       -> SelectVehicleCan
            -> CanSettings / CanFeatures
       -> InstallationChecks
       -> InstallationComplete
       -> Repair
       -> Removal
       -> PushOffline
       -> UnitPowered
```

`VehicleDetails` changes visible controls and subsequent pages based on the
chosen job type, so the workflow is a stateful branch graph rather than a
simple menu. Use `view_inventory.csv` and follow the job-type click handlers
and `Navigation.PushAsync` calls in `VehicleDetails.cs`.
