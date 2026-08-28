package com.karr.key.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationsPagingTest {

    @Test
    fun everythingFitsOnOnePageWhenAtOrUnderLimit() {
        val page = operationsPage(total = 6, limit = 6, start = 0)
        assertEquals(0, page.fromIndex)
        assertEquals(6, page.toIndex)
        assertNull("no More row when it all fits", page.nextStart)
    }

    @Test
    fun oneOverTheLimitStartsPaging() {
        // 7 items, limit 6: first page shows 5 (limit - 1) and points at index 5.
        val page = operationsPage(total = 7, limit = 6, start = 0)
        assertEquals(5, page.size)
        assertEquals(5, page.nextStart)
    }

    @Test
    fun emptyCatalogYieldsEmptyPage() {
        val page = operationsPage(total = 0, limit = 6, start = 0)
        assertEquals(0, page.size)
        assertNull(page.nextStart)
    }

    @Test
    fun pagesCoverEveryOperationExactlyOnceAndNeverExceedTheLimit() {
        // Walk the whole catalog the way OperationsScreen does and check the invariants that back
        // the user's "list all operations": full coverage, in order, no page longer than the cap.
        for (total in 0..40) {
            for (limit in 2..8) {
                val visited = mutableListOf<Int>()
                var start = 0
                var guard = 0
                while (true) {
                    val page = operationsPage(total, limit, start)
                    val rows = page.size + if (page.nextStart != null) 1 else 0
                    assertTrue(
                        "page rows ($rows) must fit the host limit ($limit) at total=$total",
                        rows <= limit,
                    )
                    for (i in page.fromIndex until page.toIndex) visited.add(i)
                    val next = page.nextStart ?: break
                    assertTrue("pagination must advance", next > start)
                    start = next
                    check(guard++ < 100) { "pagination failed to terminate" }
                }
                assertEquals(
                    "every index 0 until $total shown once (limit=$limit)",
                    (0 until total).toList(),
                    visited,
                )
            }
        }
    }
}
