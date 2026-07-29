# IDA workspace guide

Six IDA Professional 9.1 databases are under `04_native/ida_databases`.

| Database | Functions | Purpose |
|---|---:|---|
| `libxamarin-app.i64` | 0 | Generated app configuration/data image |
| `libxa-internal-api.i64` | 249 | Xamarin.Android internal native API |
| `libmonodroid.i64` | 748 | Android/JNI to Mono bridge |
| `libmono-native.i64` | 392 | Mono platform support |
| `libmono-btls-shared.i64` | 3,838 | Native TLS/cryptography |
| `libmonosgen-2.0.i64` | 7,858 | Mono runtime and garbage collector |

`libxamarin-app` contains no IDA-discovered functions in this build; it still
contains generated runtime/configuration data and has a valid database.

Every discovered function in all databases has a persistent repeatable
comment. Real exports/names are retained, and stripped functions remain
honestly auto-named. Sidecar reports are in `04_native/reports`; the validated
summary is `native_index.csv`.

Use the managed `QTAP` C# project for application behavior. Use IDA for native
runtime, JNI, or TLS questions.
