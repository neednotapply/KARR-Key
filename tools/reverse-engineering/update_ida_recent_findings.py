"""Persist the 2026-07-28 KARR BLE/authentication findings in an IDA database.

The relevant application logic is managed C#, while these databases mostly
cover Xamarin/Mono runtime and native glue. The annotation is therefore a
database-level research note at the first mapped address; it does not assign
invented semantics to unrelated native functions.
"""

import hashlib
import json
import os

import ida_auto
import ida_funcs
import ida_loader
import ida_pro
import ida_segment
import idaapi
import idautils
import idc


MARKER = "[KARR_MASTER_RESEARCH_UPDATE_2026_07_28]"

COMMON_NOTE = (
    "Pixel capture: after KARR UART subscription, the unit sent an Original "
    "0xAA frame, cmd 0x0E RequestAuth, with an 8-byte challenge. GATT 19 was "
    "peer termination after that request went unanswered; subscription is not "
    "authenticated Ready. Observed candidates: DR D029A54D, DR 0F9ACB56. "
    "Recovered discovery also accepts DR/QT names and Dealer manufacturer "
    "frames. Discovery is not authorization. No key or response is recorded."
)

APP_NOTES = {
    "karr_bt_v2.15": (
        "Managed QTAP evidence: "
        "RequestAuth(14) -> Read_Mode(34) -> ProvideAuth(15) -> "
        "AuthSuccess(16). Legacy key material is intentionally not reproduced."
    ),
    "karr_bt_dealer_v3.131": (
        "Managed Dealer evidence: the same auth commands support legacy 8-byte "
        "and newer 32-byte paths. Authorized per-unit key material comes from "
        "its data/backend layer. Bond51 defaults false; bonding is separate."
    ),
    "karr_installer_v1.39": (
        "Managed Installer evidence: RequestAuth(14) -> Read_Mode(34) -> "
        "ProvideAuth(15) -> AuthSuccess(16). Optional Bond51 defaults false. "
        "Use additionally requires an authorized technician job."
    ),
    "karr_swat_dl_v2.05": (
        "Recovered SWAT DL has no BLE discovery, UART, or unit authentication. "
        "It remains a read-only server/map locator."
    ),
}

EVIDENCE_PATHS = {
    "karr_bt_v2.15": (
        "CONNECTION_AUTHENTICATION.md; "
        "docs/research/apps/karr_bt_v2.15/BLE_PROTOCOL.md"
    ),
    "karr_bt_dealer_v3.131": (
        "CONNECTION_AUTHENTICATION.md; "
        "docs/research/apps/karr_bt_dealer_v3.131/BLE_PROTOCOL.md"
    ),
    "karr_installer_v1.39": (
        "CONNECTION_AUTHENTICATION.md; "
        "docs/research/apps/karr_installer_v1.39/BLE_PROTOCOL.md"
    ),
    "karr_swat_dl_v2.05": (
        "docs/research/apps/karr_swat_dl_v2.05/"
        "SECURITY_AND_MASTER_APP_NOTES.md"
    ),
}


def app_slug(database_path):
    normalized = database_path.replace("\\", "/").lower()
    for slug in APP_NOTES:
        if slug.lower() in normalized:
            return slug
    raise RuntimeError("Unable to determine KARR app from IDA path: %s" % database_path)


def research_note(slug):
    return "\n".join(
        (
            MARKER,
            COMMON_NOTE,
            APP_NOTES[slug],
            "Repository evidence: %s." % EVIDENCE_PATHS[slug],
        )
    )


def sha256_text(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest().upper()


def write_report(path, payload):
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, sort_keys=False)


def apply_note(database_path, slug, note, report_path):
    address = idaapi.inf_get_min_ea()
    existing = idc.get_cmt(address, True) or ""
    if MARKER in existing:
        prefix = existing.split(MARKER, 1)[0].rstrip()
    else:
        prefix = existing.rstrip()
    combined = (prefix + "\n\n" if prefix else "") + note
    if not idc.set_cmt(address, combined, True):
        raise RuntimeError("IDA refused the database-level repeatable comment")

    segment = ida_segment.getseg(address)
    if segment:
        segment_comment = ida_segment.get_segment_cmt(segment, True) or ""
        short_marker = "%s See repeatable comment at 0x%X." % (MARKER, address)
        if MARKER not in segment_comment:
            updated = (
                segment_comment.rstrip() + "\n" + short_marker
                if segment_comment
                else short_marker
            )
            ida_segment.set_segment_cmt(segment, updated, True)

    ida_loader.save_database(database_path, 0)
    persisted = idc.get_cmt(address, True) or ""
    result = {
        "mode": "apply",
        "database": database_path,
        "app": slug,
        "marker": MARKER,
        "comment_address": "0x%X" % address,
        "note_sha256": sha256_text(note),
        "note_length": len(note),
        "persisted_length": len(persisted),
        "persisted_in_open_database": note in persisted,
        "contains_secret_values": False,
    }
    write_report(report_path, result)
    if not result["persisted_in_open_database"]:
        raise RuntimeError("Research note did not persist in the open database")


def validate_note(database_path, slug, note, report_path):
    address = idaapi.inf_get_min_ea()
    persisted = idc.get_cmt(address, True) or ""
    segment = ida_segment.getseg(address)
    segment_comment = (
        ida_segment.get_segment_cmt(segment, True) if segment else ""
    ) or ""
    function_count = 0
    commented_function_count = 0
    for function_address in idautils.Functions():
        function_count += 1
        function = ida_funcs.get_func(function_address)
        if ida_funcs.get_func_cmt(function, True):
            commented_function_count += 1
    result = {
        "mode": "validate",
        "database": database_path,
        "app": slug,
        "marker": MARKER,
        "comment_address": "0x%X" % address,
        "note_sha256": sha256_text(note),
        "note_length": len(note),
        "persisted_length": len(persisted),
        "note_persisted": note in persisted,
        "segment_marker_persisted": MARKER in segment_comment,
        "function_count": function_count,
        "repeatably_commented_function_count": commented_function_count,
        "all_functions_commented": (
            function_count == commented_function_count
        ),
        "contains_secret_values": False,
    }
    result["passed"] = (
        result["note_persisted"]
        and result["segment_marker_persisted"]
        and result["all_functions_commented"]
    )
    write_report(report_path, result)
    if not result["passed"]:
        raise RuntimeError("IDA research-note validation failed")


def main():
    ida_auto.auto_wait()
    if len(idc.ARGV) < 3:
        raise RuntimeError(
            "usage: update_ida_recent_findings.py <apply|validate> <report.json>"
        )
    mode = idc.ARGV[1].lower()
    report_path = idc.ARGV[2]
    database_path = idc.get_idb_path()
    slug = app_slug(database_path)
    note = research_note(slug)

    try:
        if mode == "apply":
            apply_note(database_path, slug, note, report_path)
        elif mode == "validate":
            validate_note(database_path, slug, note, report_path)
        else:
            raise RuntimeError("unsupported mode: %s" % mode)
    except Exception as exc:
        write_report(
            report_path,
            {
                "mode": mode,
                "database": database_path,
                "app": slug,
                "marker": MARKER,
                "passed": False,
                "error": str(exc),
            },
        )
        ida_pro.qexit(1)
        return
    ida_pro.qexit(0)


main()
