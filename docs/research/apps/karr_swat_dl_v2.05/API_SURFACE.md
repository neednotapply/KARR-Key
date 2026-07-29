# API surface

The SWAT DL main project has a deliberately small network surface:

- user type/current-account context;
- active vehicle retrieval;
- explicit vehicle search.

`api_route_index.csv` contains 57 normalized occurrences because it also
indexes the bundled `WebhouseStandard` project. Those shared-library literals
span users, accounts, vehicles, fitments, CAN support, logs, decoder, and
control-room families, but their presence does not prove the SWAT DL UI calls
them.

Filter the CSV's `project` column to `SWATDealer` for app-owned routes. Query
values are omitted from the route field. The primary implementation is
`SWATDealer/Shared.cs`.
