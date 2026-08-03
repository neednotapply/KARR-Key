package com.karr.key.ui

import com.karr.key.core.UserRole
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.RecoveredWorkflowCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KarrNavigationStateTest {
    @Test
    fun topLevelDestinationsOmitTopBarWhileOperationDetailsKeepTheirTitle() {
        assertNull(shellTopBarTitle(AppRoute.Home))
        assertNull(shellTopBarTitle(AppRoute.Operations))
        assertNull(shellTopBarTitle(AppRoute.Device()))
        assertEquals(
            "Read state",
            shellTopBarTitle(
                route = AppRoute.OperationDetail("read-state"),
                operationTitle = "Read state",
            ),
        )
    }

    @Test
    fun shellExposesHomeOperationsAndDeviceDestinations() {
        assertEquals(
            listOf(
                TopLevelDestination.HOME,
                TopLevelDestination.OPERATIONS,
                TopLevelDestination.DEVICE,
            ),
            TopLevelDestination.entries,
        )
        assertEquals("Operations", TopLevelDestination.OPERATIONS.label)

        val navigation = KarrNavigationState()
        assertEquals(TopLevelDestination.HOME, navigation.selectedTopLevel)
        assertEquals(AppRoute.Home, navigation.currentRoute)
    }

    @Test
    fun operationsDestinationOpensCanonicalCatalogRoot() {
        val navigation = KarrNavigationState()

        navigation.openOperations()

        assertEquals(TopLevelDestination.OPERATIONS, navigation.selectedTopLevel)
        assertEquals(AppRoute.Operations, navigation.currentRoute)
    }

    @Test
    fun successfulConnectionFromDeviceOpensOperationsOnce() {
        val navigation = KarrNavigationState()
        navigation.openDevice(DeviceSection.DIAGNOSTICS)

        assertTrue(
            navigation.openOperationsAfterSuccessfulConnection(
                wasAuthenticated = false,
                isAuthenticated = true,
            ),
        )
        assertEquals(TopLevelDestination.OPERATIONS, navigation.selectedTopLevel)
        assertEquals(AppRoute.Operations, navigation.currentRoute)
        assertFalse(
            navigation.openOperationsAfterSuccessfulConnection(
                wasAuthenticated = true,
                isAuthenticated = true,
            ),
        )
    }

    @Test
    fun authenticationDoesNotRedirectAwayFromNonDevicePages() {
        val navigation = KarrNavigationState()

        assertFalse(
            navigation.openOperationsAfterSuccessfulConnection(
                wasAuthenticated = false,
                isAuthenticated = true,
            ),
        )
        assertEquals(AppRoute.Home, navigation.currentRoute)

        navigation.openOperations()
        assertFalse(
            navigation.openOperationsAfterSuccessfulConnection(
                wasAuthenticated = false,
                isAuthenticated = true,
            ),
        )
        assertEquals(AppRoute.Operations, navigation.currentRoute)
    }

    @Test
    fun incompleteConnectionDoesNotLeaveDevicePage() {
        val navigation = KarrNavigationState()
        navigation.openDevice()

        assertFalse(
            navigation.openOperationsAfterSuccessfulConnection(
                wasAuthenticated = false,
                isAuthenticated = false,
            ),
        )
        assertEquals(AppRoute.Device(), navigation.currentRoute)
    }

    @Test
    fun adjacentTopLevelMovesFollowHomeOperationsDeviceOrder() {
        val navigation = KarrNavigationState()

        assertFalse(navigation.moveTopLevelBy(-1))
        assertTrue(navigation.moveTopLevelBy(1))
        assertEquals(TopLevelDestination.OPERATIONS, navigation.selectedTopLevel)
        assertTrue(navigation.topLevelTransitionForward)
        assertTrue(navigation.moveTopLevelBy(1))
        assertEquals(TopLevelDestination.DEVICE, navigation.selectedTopLevel)
        assertFalse(navigation.moveTopLevelBy(1))
        assertTrue(navigation.moveTopLevelBy(-1))
        assertEquals(TopLevelDestination.OPERATIONS, navigation.selectedTopLevel)
        assertFalse(navigation.topLevelTransitionForward)
    }

    @Test
    fun topLevelMoveDoesNotLeaveAnOpenOperationDetail() {
        val navigation = KarrNavigationState()
        val operation = RecoveredWorkflowCatalog.operations.first()
        navigation.openOperation(operation)

        assertFalse(navigation.moveTopLevelBy(1))
        assertEquals(
            AppRoute.OperationDetail(operation.id),
            navigation.currentRoute,
        )
    }

    @Test
    fun topLevelSwitchPreservesOperationAndReselectReturnsToCatalog() {
        val navigation = KarrNavigationState()
        val operation = RecoveredWorkflowCatalog.operations.first()
        navigation.openOperation(operation)

        navigation.openDevice(DeviceSection.DIAGNOSTICS)
        navigation.selectTopLevel(TopLevelDestination.OPERATIONS)

        assertEquals(
            AppRoute.OperationDetail(operation.id),
            navigation.currentRoute,
        )

        navigation.selectTopLevel(TopLevelDestination.OPERATIONS)
        assertEquals(AppRoute.Operations, navigation.currentRoute)
    }

    @Test
    fun navigateUpReturnsFromOperationToCatalog() {
        val navigation = KarrNavigationState()
        val operation = RecoveredWorkflowCatalog.operations.first()
        navigation.openOperation(operation)

        assertTrue(navigation.navigateUp())
        assertEquals(AppRoute.Operations, navigation.currentRoute)
        assertFalse(navigation.navigateUp())
    }

    @Test
    fun catalogListsEveryUniquePacketOnce() {
        val operations = deviceOperationsFor()
        val packets = operations.map { operation ->
            operation.command to operation.payload
        }

        assertEquals(25, operations.size)
        assertEquals(packets.size, packets.distinct().size)
        assertEquals(
            UserRole.entries.toSet(),
            operations.flatMap { operation -> operation.roles }.toSet(),
        )
    }

    @Test
    fun searchMatchesRolesCommandsAndCategories() {
        val dealerOperations = deviceOperationsFor(query = "dealer")
        assertTrue(dealerOperations.isNotEmpty())
        assertTrue(
            dealerOperations.any { operation -> UserRole.DEALER in operation.roles },
        )
        assertTrue(
            dealerOperations.any { operation ->
                operation.label == "Switch to Dealer mode"
            },
        )

        val reads = deviceOperationsFor(query = "Reads")
        assertTrue(reads.isNotEmpty())
        assertTrue(
            reads.containsAll(
                RecoveredWorkflowCatalog.operations.filter { operation -> !operation.mutating },
            ),
        )

        assertEquals(
            listOf(QtCommand.READ_STATE),
            deviceOperationsFor(query = "READ_STATE").map { operation -> operation.command },
        )
    }

    @Test
    fun readsAndCommandsFiltersPartitionTheCatalog() {
        val reads = deviceOperationsFor(
            filters = OperationFilters(operationType = OperationTypeFilter.READS),
        )
        val commands = deviceOperationsFor(
            filters = OperationFilters(operationType = OperationTypeFilter.COMMANDS),
        )

        assertTrue(reads.isNotEmpty())
        assertTrue(reads.all { operation -> !operation.mutating })
        assertTrue(commands.isNotEmpty())
        assertTrue(commands.all { operation -> operation.mutating })
        assertTrue(commands.any { operation -> operation.category == "Experimental" })
        assertEquals(
            deviceOperationsFor().map { operation -> operation.id }.toSet(),
            (reads + commands).map { operation -> operation.id }.toSet(),
        )
    }

    @Test
    fun workflowFilterReturnsUniqueMatchingOperations() {
        val filters = OperationFilters(workflow = "Validation")
        val operations = deviceOperationsFor(filters = filters)

        assertTrue(operations.isNotEmpty())
        assertTrue(
            operations.all { operation ->
                operation.bindings.any { binding ->
                    binding.feature.category == "Validation"
                }
            },
        )
        assertEquals(operations.size, operations.map { operation -> operation.id }.distinct().size)
    }

    @Test
    fun workflowFilterRestoresFormerFeatureCategories() {
        val vehicleControls = deviceOperationsFor(
            filters = OperationFilters(workflow = "Vehicle controls"),
        )

        assertTrue(vehicleControls.isNotEmpty())
        assertTrue(vehicleControls.size < deviceOperationsFor().size)
        assertTrue(
            vehicleControls.all { operation ->
                operation.bindings.any { binding ->
                    binding.feature.category == "Vehicle controls"
                }
            },
        )
    }

    @Test
    fun unitModeFilterCollectsEveryModeSwitch() {
        val modeSwitches = deviceOperationsFor(
            filters = OperationFilters(workflow = UNIT_MODE_FILTER),
        )

        assertEquals(6, modeSwitches.size)
        assertTrue(modeSwitches.all { operation -> operation.command == QtCommand.CHANGE_MODE })
        assertEquals(
            (0..5).toSet(),
            modeSwitches.map { operation -> operation.payload.single() }.toSet(),
        )
    }

    @Test
    fun experimentalFilterCollectsOnlyTheRemainingCandidate() {
        val experimental = deviceOperationsFor(
            filters = OperationFilters(workflow = EXPERIMENTAL_FILTER),
        )

        assertEquals(1, experimental.size)
        assertTrue(
            experimental.all { operation ->
                operation.bindings.any { binding -> binding.action.experimental }
            },
        )
        assertTrue(experimental.none { operation -> operation.command == QtCommand.CHANGE_MODE })
    }

    @Test
    fun workflowTagsOnlyExposeCrossCuttingFilters() {
        val workflows = workflowFiltersFor()

        assertEquals(
            listOf(
                UNIT_MODE_FILTER,
                EXPERIMENTAL_FILTER,
            ),
            workflows,
        )
        listOf("Vehicle controls", "Diagnostics", "Validation", "Unit service").forEach { redundant ->
            assertFalse(redundant in workflows)
        }
    }

}
