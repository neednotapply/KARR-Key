# API surface

`api_route_index.csv` contains 88 normalized source occurrences:

| Family | Occurrences | Observed responsibility |
|---|---:|---|
| fitments | 31 | Install, repair, replacement, removal, validation, tests |
| users | 15 | Login, access, technician/user context |
| vehicles | 14 | Vehicle/unit search and job-related data |
| vehiclecansupport | 13 | CAN filters, definitions, metadata, file retrieval |
| accounts | 6 | Account context/list/verification |
| logs | 4 | Location and operational logging |
| controlroom | 2 | Alarm/test event submission |
| enumerators | 1 | Consumables/reference choices |
| decoders | 1 | VIN/vehicle decode |
| stock | 1 | Stock-related lookup |

The CSV points to exact source lines and distinguishes the main app from
`WebhouseStandard`. It omits query values. Route names alone are not enough to
reconstruct validation or authorization; follow the request/response types and
calling job step.
