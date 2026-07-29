# View and route map

```text
Login
  -> AccountsSelection (when account choice is required)
  -> DownloadData
  -> FRMBLEDealerAppPage / primary dealer surface
       -> Bluetooth / ScanPage
            -> UnitSettings or Pages.UnitSettingsRefreshed
       -> MapPage
       -> USerSettings
       -> dealer sale/unwind/booking actions within the active page flow
```

Navigation is predominantly page construction/push/pop and global shared state,
not a centralized route registry. Use `view_inventory.csv` for all compiled
XAML classes, then follow constructors and `Navigation.PushAsync`,
`PopAsync`, or `MainPage` assignments in the C# project.

`UnitSettingsRefreshed` is the current high-density unit workflow; retain the
older `UnitSettings` as a compatibility reference rather than assuming both
paths have identical behavior.
