# IDA workspace guide

Six IDA Professional 9.1 databases are under `04_native/ida_databases`.

| Database | Functions | Purpose |
|---|---:|---|
| `libxamarin-app.i64` | 1 | App/runtime configuration and assembly-store registration |
| `libxa-internal-api.i64` | 249 | Xamarin.Android internal native API |
| `libmonodroid.i64` | 604 | Android/JNI to Mono bridge |
| `libmono-native.i64` | 392 | Mono native platform support |
| `libmono-btls-shared.i64` | 3,838 | TLS/cryptography plumbing |
| `libmonosgen-2.0.i64` | 7,870 | Mono runtime and garbage collector |

Open a database with IDA, for example:

```powershell
& 'C:\Program Files\IDA Professional 9.1\ida.exe' `
  '.\04_native\ida_databases\libmonodroid.i64'
```

Every discovered function has a persistent repeatable comment. Real names and
exports are preserved; stripped auto-named functions are explicitly labeled
as such. String comments classify Xamarin/Mono, JNI, assembly loading, TLS, and
other library-role evidence. `native_index.csv` and `04_native/reports` are
machine-readable sidecars.

Start in managed C# for KARR behavior. Use IDA only when following native
startup, JNI transitions, TLS internals, or runtime behavior.
