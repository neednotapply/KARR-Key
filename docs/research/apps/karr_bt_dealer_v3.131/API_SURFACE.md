# API surface

`api_route_index.csv` contains 86 unique source occurrences after normalization.
Its primary route families are:

| Family | Occurrences | Observed responsibility |
|---|---:|---|
| vehicles | 28 | Inventory/search, products, sale/unwind, booking, statistics |
| fitments | 17 | Shared install/repair/removal/test operations |
| users | 14 | Login/access/account changes and user context |
| vehiclecansupport | 7 | CAN compatibility, file metadata, file retrieval |
| logs | 6 | Last access, locations, and operational logs |
| accounts | 5 | Current/list/create/verification account operations |
| devicesecurity | 5 | Authorized device-key retrieval |
| products | 2 | Product/snow-mode operations |
| decoders | 1 | Vehicle/VIN decoding |
| controlroom | 1 | Alarm-event submission |

Routes are literal decompiled code locations, not a live API specification.
Operation classification is inferred from nearby client calls and may not
capture server behavior. Query values are deliberately excluded from the CSV.
Use the source location, request/response model, and caller together.
