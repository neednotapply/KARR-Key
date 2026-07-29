import hashlib
import json
import os

import ida_auto
import ida_funcs
import ida_loader
import ida_name
import ida_nalt
import ida_pro
import ida_segment
import idaapi
import idautils
import idc


LIBRARY_ROLES = {
    "libxamarin-app.so": "Xamarin application glue: assembly-store metadata, runtime configuration, and managed startup registration.",
    "libxa-internal-api.so": "Xamarin.Android internal native API bridge used by generated Java and managed glue.",
    "libmonodroid.so": "Xamarin.Android runtime bridge between Android/JNI and the Mono managed runtime.",
    "libmono-native.so": "Mono native support library for platform services exposed to managed code.",
    "libmono-btls-shared.so": "Mono BTLS native TLS and cryptography implementation used by managed networking.",
    "libmonosgen-2.0.so": "Mono SGen managed runtime and garbage collector; framework runtime rather than application business logic.",
    "libe_sqlite3.so": "Bundled SQLite database engine; first-party managed callers were not established, so active application use is not assumed.",
    "libsqlite3_xamarin.so": "Bundled Xamarin SQLite native integration/engine; first-party managed callers were not established.",
}


def function_annotation(name, library):
    lower = name.lower()
    if name == "JNI_OnLoad":
        return "JNI library-load entry point. Registers or initializes the Android/native boundary; trace registered methods and managed callers for application semantics."
    if name.startswith("Java_"):
        return "JNI-exported native method called from Android Java glue. Trace its managed registration and caller before assigning application semantics."
    if "sqlite" in lower:
        return "SQLite database or managed/native SQLite bridge function. Inspect SQL strings and managed callers to identify the owning data model."
    if "xamarin" in lower or "monodroid" in lower:
        return "Xamarin.Android runtime or glue function participating in managed startup, type registration, or JNI marshaling."
    if "mono_btls" in lower or "btls" in lower:
        return "Mono BTLS transport-security implementation. This is cryptographic/runtime plumbing, not evidence of an app-specific protocol by itself."
    if lower.startswith("mono_gc") or "sgen" in lower or "garbage" in lower:
        return "Mono/SGen garbage-collection runtime function. Framework implementation, not application business logic."
    if lower.startswith("mono_"):
        return "Mono runtime function used to load, execute, inspect, or support managed assemblies."
    if lower.startswith("__") or lower.startswith("_unwind"):
        return "Compiler, C runtime, or unwind helper retained from the native toolchain."
    if lower.startswith("j_"):
        return "Import or tail-call thunk. Follow the target function for semantics."
    if name.startswith(("sub_", "loc_", "nullsub_")):
        return "Auto-discovered native function in a stripped binary. No reliable original semantic name is recoverable; use callers, cross-references, and data references before renaming."
    return "Named native function. Library role: %s" % LIBRARY_ROLES.get(
        library, "native support code recovered from the original APK"
    )


def string_annotation(text):
    lower = text.lower()
    if "sqlite" in lower or lower.startswith(("select ", "insert ", "update ", "delete ", "create table")):
        return "SQLite implementation, schema, or query string; inspect cross-references and managed data models."
    if "xamarin" in lower or "monodroid" in lower or "mono." in lower:
        return "Xamarin/Mono runtime diagnostic or configuration string."
    if "assembly" in lower or text.endswith(".dll"):
        return "Managed assembly loading or registration string."
    if any(token in lower for token in ("tls", "ssl", "x509", "certificate")):
        return "TLS or certificate runtime string; inspect callers before assigning app-specific meaning."
    if "jni" in lower or text.startswith("Java_"):
        return "JNI registration or marshaling string."
    if text.startswith(("http://", "https://")):
        return "Network endpoint string present in the binary. Correlate with the managed network-service call sites."
    return None


def main():
    ida_auto.auto_wait()
    library = ida_nalt.get_root_filename()
    input_path = ida_nalt.get_input_file_path()
    app_label = os.environ.get("KARR_APP_LABEL", "KARR application")
    role = LIBRARY_ROLES.get(
        library, "Native component recovered from the original APK."
    )

    functions = []
    for ea in idautils.Functions():
        func = ida_funcs.get_func(ea)
        name = ida_name.get_name(ea) or ("sub_%X" % ea)
        annotation = function_annotation(name, library)
        ida_funcs.set_func_cmt(func, annotation, True)
        functions.append(
            {
                "address": "0x%X" % ea,
                "name": name,
                "size": int(func.end_ea - func.start_ea),
                "annotation": annotation,
            }
        )

    segments = []
    for start in idautils.Segments():
        seg = ida_segment.getseg(start)
        name = ida_segment.get_segm_name(seg)
        comment = "%s segment in %s. Library role: %s" % (name, library, role)
        try:
            ida_segment.set_segment_cmt(seg, comment, True)
        except Exception:
            pass
        segments.append(
            {
                "name": name,
                "start": "0x%X" % seg.start_ea,
                "end": "0x%X" % seg.end_ea,
                "permissions": int(seg.perm),
            }
        )

    exports = [
        {"ordinal": int(ordinal), "address": "0x%X" % ea, "name": name}
        for _index, ordinal, ea, name in idautils.Entries()
    ]

    tagged_strings = []
    for item in idautils.Strings():
        try:
            value = str(item)
        except Exception:
            continue
        annotation = string_annotation(value)
        if not annotation:
            continue
        idc.set_cmt(item.ea, annotation, True)
        tagged_strings.append(
            {
                "address": "0x%X" % item.ea,
                "length": len(value),
                "classification": annotation,
                "sha256": hashlib.sha256(
                    value.encode("utf-8", "replace")
                ).hexdigest(),
            }
        )

    min_ea = idaapi.inf_get_min_ea()
    idc.set_cmt(
        min_ea,
        "%s native analysis. %s Business logic primarily resides in the recovered managed assemblies."
        % (app_label, role),
        True,
    )

    report_path = (
        idc.ARGV[1]
        if len(idc.ARGV) > 1
        else os.path.splitext(idc.get_idb_path())[0] + ".json"
    )
    report = {
        "app": app_label,
        "library": library,
        "input_path": input_path,
        "role": role,
        "function_count": len(functions),
        "auto_named_function_count": sum(
            1
            for function in functions
            if function["name"].startswith(("sub_", "loc_", "nullsub_"))
        ),
        "export_count": len(exports),
        "tagged_string_count": len(tagged_strings),
        "segments": segments,
        "exports": exports,
        "functions": functions,
        "tagged_strings": tagged_strings,
    }
    with open(report_path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2, sort_keys=False)

    ida_loader.save_database(idc.get_idb_path(), 0)
    ida_pro.qexit(0)


main()
