package com.karr.key.ui

import com.karr.key.core.FeatureCatalog
import com.karr.key.core.Session
import com.karr.key.core.UserRole
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.RecoveredActionPreflight
import com.karr.key.protocol.RecoveredWorkflowCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun incompatibleConnectedModelBlocksOperationAtCatalog() {
        val availability = operationModelAvailability(
            session = session(UserRole.CUSTOMER),
            operation = operation(QtCommand.SILENT_LOCK, listOf(0)),
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR2_ESP,
        )

        assertTrue(availability is FeatureAvailability.HardwareUnsupported)
        assertFalse(availability.allowsOperationOpen())
    }

    @Test
    fun supportedConnectedModelKeepsOperationOpen() {
        val availability = operationModelAvailability(
            session = session(UserRole.CUSTOMER),
            operation = operation(QtCommand.SILENT_LOCK, listOf(0)),
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR1_CAN,
        )

        assertEquals(FeatureAvailability.Available, availability)
        assertTrue(availability.allowsOperationOpen())
    }

    @Test
    fun compatibleRoleBindingKeepsFlattenedOperationOpen() {
        val roles = UserRole.entries.toSet()
        val availability = operationModelAvailability(
            session = Session(
                displayName = "All roles",
                roles = roles,
                capabilities = FeatureCatalog.capabilitiesFor(roles),
            ),
            operation = trunkOperation(),
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR1_CAN,
        )

        assertEquals(FeatureAvailability.Available, availability)
        assertTrue(availability.allowsOperationOpen())
    }

    @Test
    fun unidentifiedConnectedModelBlocksRestrictedOperationAtCatalog() {
        val availability = operationModelAvailability(
            session = session(UserRole.CUSTOMER),
            operation = operation(QtCommand.SILENT_LOCK, listOf(0)),
            deviceAuthenticated = true,
            unitModel = null,
        )

        assertTrue(availability is FeatureAvailability.HardwareUnknown)
        assertFalse(availability.allowsOperationOpen())
    }

    @Test
    fun dr1LearnedRemoteReadIsDisabledAtCatalog() {
        val availability = operationModelAvailability(
            session = session(UserRole.INSTALLER),
            operation = operation(QtCommand.READ_REMOTES, emptyList()),
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR1_CAN,
            unitMode = KarrDeviceMode.USER,
        )

        assertTrue(availability is FeatureAvailability.HardwareUnsupported)
        assertFalse(availability.allowsOperationOpen())
    }

    @Test
    fun bumpTestAvailabilityTracksCurrentOperatingMode() {
        val operation = operation(QtCommand.BUMP_TEST, listOf(1))
        val userMode = operationModelAvailability(
            session = session(UserRole.INSTALLER),
            operation = operation,
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR1_CAN,
            unitMode = KarrDeviceMode.USER,
        )
        val installerMode = operationModelAvailability(
            session = session(UserRole.INSTALLER),
            operation = operation,
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR1_CAN,
            unitMode = KarrDeviceMode.INSTALLER,
        )

        assertTrue(userMode is FeatureAvailability.ModeRestricted)
        assertTrue(userMode.allowsOperationOpen())
        assertEquals(FeatureAvailability.Available, installerMode)
        assertTrue(installerMode.allowsOperationOpen())
    }

    @Test
    fun experimentalInstallerSwitchTracksMostRecentlySuppliedMode() {
        val switchToInstaller = operation(
            QtCommand.CHANGE_MODE,
            listOf(KarrDeviceMode.INSTALLER.code),
        )
        val dealerMode = operationModelAvailability(
            session = session(UserRole.INSTALLER),
            operation = switchToInstaller,
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.QT6_RED,
            unitMode = KarrDeviceMode.DEALER,
        )
        val userMode = operationModelAvailability(
            session = session(UserRole.INSTALLER),
            operation = switchToInstaller,
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.QT6_RED,
            unitMode = KarrDeviceMode.USER,
        )

        assertEquals(FeatureAvailability.Available, dealerMode)
        assertTrue(userMode is FeatureAvailability.ModeRestricted)
        assertTrue(userMode.allowsOperationOpen())
    }

    @Test
    fun sharedUserModePacketSelectsRoleAndSourceModeAppropriateBinding() {
        val returnToUser = operation(
            QtCommand.CHANGE_MODE,
            listOf(KarrDeviceMode.USER.code),
        )

        assertEquals(
            "customer.valet",
            returnToUser.preferredBinding(
                session = session(UserRole.CUSTOMER),
                unitModel = KarrUnitModel.QT6_RED,
                unitMode = KarrDeviceMode.VALET,
            ).feature.id,
        )
        assertEquals(
            "installer.mode_switches",
            returnToUser.preferredBinding(
                session = session(UserRole.INSTALLER),
                unitModel = KarrUnitModel.QT6_RED,
                unitMode = KarrDeviceMode.DEALER,
            ).feature.id,
        )
    }

    private fun trunkOperation() =
        operation(QtCommand.VEHICLE_CONTROL, listOf(10))

    private fun operation(command: QtCommand, payload: List<Int>) =
        RecoveredWorkflowCatalog.operations.single { operation ->
            operation.command == command && operation.payload == payload
        }

    private fun session(role: UserRole) = Session(
        displayName = role.name,
        roles = setOf(role),
        capabilities = FeatureCatalog.capabilitiesFor(setOf(role)),
    )
}
