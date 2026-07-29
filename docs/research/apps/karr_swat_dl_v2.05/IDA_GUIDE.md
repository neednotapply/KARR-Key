# IDA workspace guide

Six IDA Professional 9.1 databases are under `04_native/ida_databases`.

| Database | Functions | Purpose |
|---|---:|---|
| `libxamarin-app.i64` | 1 | App/runtime configuration |
| `libxa-internal-api.i64` | 249 | Xamarin.Android internal API |
| `libmonodroid.i64` | 605 | Android/JNI to Mono bridge |
| `libmono-native.i64` | 392 | Mono platform support |
| `libmono-btls-shared.i64` | 3,838 | Native TLS/cryptography |
| `libmonosgen-2.0.i64` | 7,862 | Mono runtime and garbage collector |

Every discovered function has a persistent repeatable comment. Real
exports/names are preserved; stripped functions remain auto-named with honest
classification. Reports are under `04_native/reports`, and `native_index.csv`
records hashes, counts, roles, and validation.

SWAT locator behavior is in managed C#. Use IDA for Xamarin/JNI/TLS/runtime
questions, not as the starting point for vehicle search or map logic.
