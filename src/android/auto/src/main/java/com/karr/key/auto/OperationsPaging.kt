package com.karr.key.auto

/**
 * Which slice of the operation list one [OperationsScreen] page shows. The head unit rejects a
 * list longer than its content limit, so a catalog bigger than the limit is split across pages,
 * each ending in a "More" row that leads to the next slice. Pure and side-effect free so the
 * off-by-ones — page never exceeds the limit, every operation stays reachable — are unit-tested.
 */
internal data class OperationsPage(
    /** Index of the first operation on this page (inclusive). */
    val fromIndex: Int,
    /** Index just past the last operation on this page (exclusive). */
    val toIndex: Int,
    /** Start index for the next page, or null when this page shows the tail (no "More" row). */
    val nextStart: Int?,
) {
    val size: Int get() = toIndex - fromIndex
}

/**
 * Page [start]..[toIndex) out of [total] operations under a host row [limit]. When the remainder
 * doesn't fit, one row is spent on "More", so the page shows `limit - 1` operations and points at
 * the rest via [OperationsPage.nextStart]; otherwise it shows the whole remainder and ends.
 */
internal fun operationsPage(total: Int, limit: Int, start: Int): OperationsPage {
    if (total <= 0) return OperationsPage(0, 0, null)
    val rowLimit = limit.coerceAtLeast(2) // a real head unit caps lists well above this
    val from = start.coerceIn(0, total - 1)
    val remaining = total - from
    val paged = remaining > rowLimit
    val take = if (paged) rowLimit - 1 else remaining
    val to = from + take
    return OperationsPage(fromIndex = from, toIndex = to, nextStart = if (paged) to else null)
}
