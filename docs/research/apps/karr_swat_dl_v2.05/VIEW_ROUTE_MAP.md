# View and route map

```text
App
  -> LoginPage
  -> MainPage
       -> MasterPage drawer
       -> MenuList
            -> BatteryStatus
            -> ListAllVehicles
            -> VehiclesSearch
                 -> ListAllVehicles (filtered result)
       -> LoginPage (logout)
```

All eight XAML resources are reconstructed as compiled generated C# classes.
`view_inventory.csv` records their original XAML source attributes. Navigation
calls are concentrated in `LoginPage.cs`, `MainPage.cs`, `MasterPage.cs`,
`MenuList.cs`, and `VehiclesSearch.cs`.
