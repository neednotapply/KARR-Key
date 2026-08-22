package com.karr.key.ui

import com.karr.key.core.FeatureCatalog
import com.karr.key.core.Session
import com.karr.key.core.UserRole
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.RecoveredWorkflowCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModeSwitchOfferTest {
    @Test
    fun dealerModeOffersDirectInstallerSwitchForBumpTest() {
        val offer = modeSwitchOffer(
            session = installerSession(),
            blockedAction = bumpTest(),
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR1_CAN,
            unitMode = KarrDeviceMode.DEALER,
        )

        assertEquals(KarrDeviceMode.INSTALLER, offer?.nextMode)
        assertEquals(1, offer?.totalSteps)
        assertEquals(listOf(KarrDeviceMode.INSTALLER.code), offer?.operation?.payload)
    }

    @Test
    fun userModeOffersDealerAsFirstStepTowardInstaller() {
        val offer = modeSwitchOffer(
            session = installerSession(),
            blockedAction = bumpTest(),
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR1_CAN,
            unitMode = KarrDeviceMode.USER,
        )

        assertEquals(KarrDeviceMode.DEALER, offer?.nextMode)
        assertEquals(2, offer?.totalSteps)
        assertEquals(listOf(KarrDeviceMode.DEALER.code), offer?.operation?.payload)
    }

    @Test
    fun offerDoesNotBypassRoleNeededForValetReturn() {
        val offer = modeSwitchOffer(
            session = installerSession(),
            blockedAction = bumpTest(),
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.DR1_CAN,
            unitMode = KarrDeviceMode.VALET,
        )

        assertNull(offer)
    }

    @Test
    fun hardwareRestrictionNeverOffersModeSwitch() {
        val action = RecoveredWorkflowCatalog.actionsFor("installer.immobilizer_test").single()

        assertNull(
            modeSwitchOffer(
                session = installerSession(),
                blockedAction = action,
                deviceAuthenticated = true,
                unitModel = KarrUnitModel.DR1_CAN,
                unitMode = KarrDeviceMode.DEALER,
            ),
        )
    }

    private fun bumpTest() = RecoveredWorkflowCatalog.operations
        .single { operation -> operation.command == QtCommand.BUMP_TEST }
        .bindings
        .single()
        .action

    private fun installerSession() = Session(
        displayName = "Installer",
        roles = setOf(UserRole.INSTALLER),
        capabilities = FeatureCatalog.capabilitiesFor(setOf(UserRole.INSTALLER)),
    )
}
