package com.karr.key.auto

import com.karr.key.core.FeatureCatalog
import com.karr.key.core.Session
import com.karr.key.core.UserRole
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredWorkflowCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarOperationPolicyTest {

    @Test
    fun noOperationsWithoutCustomerRole() {
        val operations = CarOperationPolicy.carVisibleOperations(session(UserRole.INSTALLER))

        assertTrue(operations.isEmpty())
    }

    /**
     * A visible operation's [CarOperationPolicy.customerBinding] only surfaces one binding, but
     * operations that share an underlying command (e.g. "read-state" backs customer.vehicles,
     * customer.immobilizer, *and* customer.diagnostics — the catalog collapses same-command
     * actions into one operation rather than listing it three times) fold several Customer
     * features into a single visible row. This checks the property that actually matters: every
     * Customer feature is reachable through *some* visible operation, not that each gets its own.
     */
    @Test
    fun everyCustomerFeatureReachableThroughAVisibleOperation() {
        val operations = CarOperationPolicy.carVisibleOperations(session(UserRole.CUSTOMER))

        val reachableFeatureIds = operations
            .flatMap { operation -> operation.bindings }
            .filter { binding -> binding.feature.requiredRole == UserRole.CUSTOMER }
            .map { binding -> binding.feature.id }
            .toSet()
        val customerFeatureIds = FeatureCatalog.customer.map { feature -> feature.id }.toSet()
        assertEquals(customerFeatureIds, reachableFeatureIds)
    }

    @Test
    fun everyVisibleOperationHasACustomerBinding() {
        val operations = CarOperationPolicy.carVisibleOperations(
            session(UserRole.CUSTOMER, UserRole.DEALER, UserRole.INSTALLER),
        )

        operations.forEach { operation ->
            assertNotNull(CarOperationPolicy.customerBinding(operation))
        }
    }

    @Test
    fun dealerOnlyRoleSeesNoOperations() {
        val operations = CarOperationPolicy.carVisibleOperations(
            session(UserRole.DEALER, UserRole.INSTALLER),
        )

        assertTrue(operations.isEmpty())
    }

    @Test
    fun mutatingOperationsRequireConfirmation() {
        val unlock = operationForFeature("customer.unlock")

        assertTrue(CarOperationPolicy.requiresConfirmation(unlock))
    }

    @Test
    fun readOperationsDoNotRequireConfirmation() {
        val diagnostics = operationForFeature("customer.diagnostics")

        assertFalse(CarOperationPolicy.requiresConfirmation(diagnostics))
    }

    @Test
    fun blockingReasonRequiresCustomerRole() {
        val unlock = operationForFeature("customer.unlock")

        val reason = CarOperationPolicy.blockingReason(
            operation = unlock,
            session = session(UserRole.INSTALLER),
            deviceAuthenticated = true,
            unitModel = null,
            unitMode = null,
        )

        assertNotNull(reason)
    }

    @Test
    fun blockingReasonRequiresAuthenticatedDeviceEvenForNonSafetySensitiveRead() {
        val diagnostics = operationForFeature("customer.diagnostics")

        val reason = CarOperationPolicy.blockingReason(
            operation = diagnostics,
            session = session(UserRole.CUSTOMER),
            deviceAuthenticated = false,
            unitModel = null,
            unitMode = null,
        )

        assertEquals("Connect and authenticate a unit.", reason)
    }

    @Test
    fun blockingReasonFlagsIncompatibleModelForTrunkRelease() {
        val trunk = operationForFeature("customer.trunk")

        val reason = CarOperationPolicy.blockingReason(
            operation = trunk,
            session = session(UserRole.CUSTOMER),
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR1_CAN,
            unitMode = null,
        )

        assertNotNull(reason)
    }

    @Test
    fun blockingReasonAllowsCompatibleTrunkRelease() {
        val trunk = operationForFeature("customer.trunk")

        val reason = CarOperationPolicy.blockingReason(
            operation = trunk,
            session = session(UserRole.CUSTOMER),
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.QT6_RED,
            unitMode = null,
        )

        assertNull(reason)
    }

    private fun operationForFeature(featureId: String): RecoveredDeviceOperation =
        RecoveredWorkflowCatalog.operations.first { operation ->
            operation.bindings.any { binding -> binding.feature.id == featureId }
        }

    private fun session(vararg roles: UserRole): Session {
        val roleSet = roles.toSet()
        return Session(
            displayName = "Test session",
            roles = roleSet,
            capabilities = FeatureCatalog.capabilitiesFor(roleSet),
        )
    }
}
