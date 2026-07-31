package com.karr.key.ui

import com.karr.key.core.FeatureCatalog
import com.karr.key.core.Session
import com.karr.key.core.UserRole
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.RecoveredActionPreflight
import com.karr.key.protocol.RecoveredWorkflowCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceOperationBindingTest {
    @Test
    fun customerAccessCannotBorrowInstallerTrunkCompatibility() {
        val binding = trunkOperation().preferredBinding(
            session = session(UserRole.CUSTOMER),
            unitModel = KarrUnitModel.DR1_CAN,
        )

        assertEquals("customer.trunk", binding.feature.id)
        assertEquals(
            RecoveredActionPreflight.QT6_RED_TRUNK_RELEASE,
            binding.action.preflight,
        )
    }

    @Test
    fun installerAccessUsesInstallerTrunkValidationBinding() {
        val binding = trunkOperation().preferredBinding(
            session = session(UserRole.INSTALLER),
            unitModel = KarrUnitModel.DR1_CAN,
        )

        assertEquals("installer.tests", binding.feature.id)
        assertEquals(null, binding.action.preflight)
    }

    @Test
    fun allRoleAccessRetainsCustomerSafetyPathWhenBothBindingsSupportModel() {
        val roles = UserRole.entries.toSet()
        val binding = trunkOperation().preferredBinding(
            session = Session(
                displayName = "All roles",
                roles = roles,
                capabilities = FeatureCatalog.capabilitiesFor(roles),
            ),
            unitModel = KarrUnitModel.QT6_RED,
        )

        assertEquals("customer.trunk", binding.feature.id)
        assertEquals(
            RecoveredActionPreflight.QT6_RED_TRUNK_RELEASE,
            binding.action.preflight,
        )
    }

    private fun trunkOperation() =
        RecoveredWorkflowCatalog.operations.single { operation ->
            operation.command == QtCommand.VEHICLE_CONTROL &&
                operation.payload == listOf(10)
        }

    private fun session(role: UserRole) = Session(
        displayName = role.name,
        roles = setOf(role),
        capabilities = FeatureCatalog.capabilitiesFor(setOf(role)),
    )
}
