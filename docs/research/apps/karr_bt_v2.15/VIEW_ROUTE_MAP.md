# View and route map

```text
Login
  -> VerificationCode (when required)
  -> MainPageMenu / MasterPage
       -> StartPage
            -> BluetoothScan
            -> Settings / DiagnosticMode
       -> Mapping / MappingPage
       -> Alerts -> AlertConfig
       -> Config
       -> Geofences -> SingleGeofence
       -> Logs / Violations
       -> Users -> SingleUser / UserDetails
       -> Instructions / KarrHelp / ContactUs
```

The exact route varies with account/vehicle/access state. Navigation is
page-based rather than a single declarative route table. Use
`view_inventory.csv`, then follow `MainPage` assignments and navigation calls
in each page.
