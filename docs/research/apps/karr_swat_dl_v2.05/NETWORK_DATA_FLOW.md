# Network and data flow

## Login

`LoginPage` calls `Shared.DoLogin`, which delegates credential submission to
the shared login helper. The returned user token is kept in static memory.
`LoadUserType` then retrieves current account, user type, and dealer-access
state.

## Vehicle requests

The SWAT DL-owned `Shared.cs` uses three route families:

- user-type/account context;
- active vehicle list with restriction/search parameters;
- explicit VIN/stock vehicle search.

`api_route_index.csv` also lists literals from the bundled
`WebhouseStandard` project. Use its `project` column to distinguish shared
library inventory from routes found in `SWATDealer`.

## Response mapping

The vehicle response loop:

1. parses latitude/longitude using locale-sensitive normalization;
2. expands upper/lower map bounds;
3. builds a display title and stock/VIN suffix;
4. creates `CarPlacement` records for non-zero coordinates;
5. updates status totals used by battery/status views.

## Master-app migration

- replace synchronous `WebClient`/`WebRequest` calls with cancellable async
  typed clients;
- move PII/search parameters from concatenated URLs where the server supports
  request bodies;
- use invariant-culture numeric parsing and handle invalid/missing coordinates;
- paginate all result sets rather than relying on a fixed first page;
- separate raw vehicle DTO, domain vehicle, map marker, and display row;
- cluster/virtualize markers and lists for large dealer fleets;
- cache only authorized account scope and expire it on logout or role change;
- do not infer wider dealer access locally—the server must filter results.
