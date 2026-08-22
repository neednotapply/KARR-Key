package com.karr.key.ui

import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.RecoveredWorkflowCatalog
import com.karr.key.protocol.dangerScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationListTest {
    @Test
    fun experimentalOperationsAreExcludedFromTheListButKeptForTheConsole() {
        val listed = listableOperations()
        assertTrue(listed.isNotEmpty())
        assertTrue("Experimental ops must not appear in the list", listed.none { it.experimental })

        // The catalog still holds the experimental immobilizer op, so the console keeps its payload
        // as a VEHICLE_CONTROL preset — that is where the test now lives.
        assertTrue(RecoveredWorkflowCatalog.operations.any { it.experimental })
        val vehicleControlPresets =
            RecoveredWorkflowCatalog.knownPayloadsByCommand[QtCommand.VEHICLE_CONTROL].orEmpty()
        assertTrue(vehicleControlPresets.any { preset -> preset.payload == listOf(2) })
    }

    @Test
    fun dangerSortOrdersAscendingAndDescending() {
        val descending = deviceOperationsFor(
            filters = OperationFilters(dangerSort = DangerSort.DESCENDING),
        ).map { it.dangerScore }
        assertEquals(descending.sortedDescending(), descending)

        val ascending = deviceOperationsFor(
            filters = OperationFilters(dangerSort = DangerSort.ASCENDING),
        ).map { it.dangerScore }
        assertEquals(ascending.sorted(), ascending)
    }

    @Test
    fun dangerSortReordersWithoutChangingMembership() {
        val unsorted = deviceOperationsFor().map { it.id }.toSet()
        val sorted = deviceOperationsFor(
            filters = OperationFilters(dangerSort = DangerSort.DESCENDING),
        ).map { it.id }.toSet()
        assertEquals(unsorted, sorted)
    }

    @Test
    fun dangerSortComposesWithTheTypeFilter() {
        val commandsDescending = deviceOperationsFor(
            filters = OperationFilters(
                operationType = OperationTypeFilter.COMMANDS,
                dangerSort = DangerSort.DESCENDING,
            ),
        )
        assertTrue(commandsDescending.isNotEmpty())
        assertTrue(commandsDescending.all { it.mutating })
        val scores = commandsDescending.map { it.dangerScore }
        assertEquals(scores.sortedDescending(), scores)
    }
}
