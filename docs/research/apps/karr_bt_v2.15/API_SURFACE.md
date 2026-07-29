# API surface

`api_route_index.csv` contains 34 normalized source occurrences:

| Family | Occurrences | Observed responsibility |
|---|---:|---|
| vehicles | 12 | Customer vehicles, requests, diagnostics, configuration |
| logs | 7 | Violations and location/log persistence |
| users | 6 | Login validation, access, user details/list |
| alerts | 3 | Alerts and alert configuration |
| accounts | 2 | Current account and verification |
| mapwaypoints | 2 | Geofence/waypoint behavior |
| ncompass | 1 | Server-assisted BLE authentication |
| vehiclecansupport | 1 | Vehicle CAN lookup |

The client also contains methods whose URLs are assembled across variables;
the managed symbol/call indexes should be used alongside this literal-route
inventory. Query data is excluded from the index to avoid duplicating PII or
credential-like values.
