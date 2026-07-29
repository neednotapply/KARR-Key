# Cross-app code reuse map

## Reuse conclusions

The most valuable reuse is conceptual and protocol-level, not direct project
merging. All four apps are Xamarin-era codebases with duplicated static state,
page code-behind, endpoint construction, and storage patterns.

| Area | Canonical reference | Reuse approach |
|---|---|---|
| Newest BLE packet implementation | Dealer `Quantam.Protocol` | Port behind tests; do not copy globals |
| Legacy BLE compatibility | BT and Installer `QT_Protocol` | Golden-vector compatibility codec |
| BLE UART discovery | Dealer/BT/Installer `App` + `TransparentData` | Replace with one session service |
| Dealer workflows | Dealer main project | Re-model as domain use cases |
| Customer controls | BT main project | Re-model with role/vehicle policy |
| Installer workflows | Installer main project | Re-model around authorized job state |
| Locator UI/queries | SWAT DL main project | Reuse behavior; modernize map/list UI |
| Shared API/data contracts | `WebhouseStandard` variants | Compare and consolidate DTOs |
| Native runtime | Xamarin/Mono `.so` libraries | Do not reuse in a new modern app |

## Shared BLE behavior

Three apps use:

- Plugin.BLE;
- service UUID `49535343-FE7D-4AE5-8FA9-9FAFD205E455`;
- characteristic UUID `49535343-1E4D-4BD9-BA61-23C647249616`;
- a transparent data accumulator/notification callback;
- command/response packets with a command byte and additive two's-complement
  checksum.

The Installer and BT implementations are semantically the same legacy packet
codec even where namespace or compiler syntax differs. Dealer 3.131 is the
forward-compatible superset.

## Shared server code

`WebhouseStandard.dll` appears in Dealer, Installer, and SWAT DL, but the
versions and reachable subsets differ. A bundled route or DTO is not proof the
main application calls it. Use:

- `first_party_call_graph.csv` to follow actual managed call edges;
- `api_route_index.csv` for literal route discovery;
- the main project's `Shared.cs` and page methods for app-owned orchestration.

The master app should consolidate DTOs only after comparing field names,
nullability, endpoint version segments, and behavior across all variants.

## Code that should not be copied

- embedded API/service/storage credentials;
- static legacy device authentication keys;
- plaintext password preference storage;
- global static selected-account/vehicle/device state;
- broad `catch` blocks that erase error detail;
- string-concatenated URLs carrying PII;
- page-owned BLE event subscriptions;
- fixed delays used as protocol synchronization;
- duplicate background loops and reconnect handlers;
- Android `allowBackup=true` for stores holding account or device secrets.

## Practical source reading

For each behavior:

1. Find the user action in `view_inventory.csv` or the relevant page class.
2. Follow its methods in the main C# project.
3. Use `first_party_call_graph.csv` to cross into shared libraries.
4. Locate server routes in `api_route_index.csv`.
5. Follow the packet command into `TransparentData`, the packet codec, and the
   command enum.
6. Enter IDA only for JNI/runtime/TLS/SQLite behavior; the KARR business logic
   is managed C#.
