# IDA workspace guide

Eight IDA Professional 9.1 databases are under `04_native/ida_databases`.

| Database | Functions | Purpose |
|---|---:|---|
| `libxamarin-app.i64` | 1 | App/runtime configuration |
| `libxa-internal-api.i64` | 249 | Xamarin.Android internal API |
| `libmonodroid.i64` | 604 | Android/JNI to Mono bridge |
| `libmono-native.i64` | 392 | Mono platform support |
| `libmono-btls-shared.i64` | 3,838 | Native TLS/cryptography |
| `libmonosgen-2.0.i64` | 7,870 | Mono runtime/GC |
| `libe_sqlite3.i64` | 1,658 | Bundled SQLite engine |
| `libsqlite3_xamarin.i64` | 1,776 | Xamarin SQLite native integration/engine |

Every discovered function has a persistent repeatable comment; real exports
and names are retained. `native_index.csv` contains hashes, counts, roles, and
validation. Detailed reports are under `04_native/reports`.

Use the C# projects for installer behavior. Use the SQLite databases when
examining native database capabilities, but do not infer an active first-party
schema solely from their presence: no direct first-party C# SQLite reference
was found.
