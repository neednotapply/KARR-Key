import json

import ida_auto
import ida_funcs
import ida_pro
import idautils
import idc


def main():
    ida_auto.auto_wait()
    function_count = 0
    commented_count = 0
    for ea in idautils.Functions():
        function_count += 1
        func = ida_funcs.get_func(ea)
        if ida_funcs.get_func_cmt(func, True):
            commented_count += 1
    result = {
        "database": idc.get_idb_path(),
        "function_count": function_count,
        "repeatably_commented_function_count": commented_count,
        "all_functions_commented": function_count == commented_count,
    }
    with open(idc.ARGV[1], "w", encoding="utf-8") as handle:
        json.dump(result, handle, indent=2)
    ida_pro.qexit(0)


main()
