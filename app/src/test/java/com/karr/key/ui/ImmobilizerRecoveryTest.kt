package com.karr.key.ui

import com.karr.key.core.FeatureCatalog
import com.karr.key.core.Session
import com.karr.key.core.UserRole
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.RecoveredWorkflowCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmobilizerRecoveryTest {
    @Test
    fun candidateRequiresUsableUnlockAndDisarmRecovery() {
        val status = immobilizerRecoveryStatus(
            session = installerSession(),
            operation = immobilizerCandidate(),
            deviceAuthenticated = true,
            unitModel = KarrUnitModel.QT6_CAN,
            unitMode = KarrDeviceMode.INSTALLER,
        )

        assertEquals("unlock-doors-00", status?.operation?.id)
        assertEquals("Unlock and disarm", status?.operation?.label)
        assertTrue(status?.available == true)
        assertNull(status?.reason)
    }

    @Test
    fun disconnectedCandidateKeepsRecoveryGateClosed() {
        val status = immobilizerRecoveryStatus(
            session = installerSession(),
            operation = immobilizerCandidate(),
            deviceAuthenticated = false,
            unitModel = KarrUnitModel.QT6_CAN,
            unitMode = KarrDeviceMode.INSTALLER,
        )

        assertFalse(status?.available == true)
        assertTrue(status?.reason?.contains("Connect and authenticate") == true)
    }

    @Test
    fun ordinaryOperationsDoNotRequireImmobilizerRecovery() {
        val unlock = RecoveredWorkflowCatalog.operationById("unlock-doors-00")!!

        assertNull(
            immobilizerRecoveryStatus(
                session = installerSession(),
                operation = unlock,
                deviceAuthenticated = true,
                unitModel = KarrUnitModel.QT6_CAN,
                unitMode = KarrDeviceMode.INSTALLER,
            ),
        )
    }

    private fun immobilizerCandidate() = RecoveredWorkflowCatalog.operations.single { operation ->
        operation.command == QtCommand.VEHICLE_CONTROL && operation.payload == listOf(2)
    }

    private fun installerSession() = Session(
        displayName = "Installer",
        roles = setOf(UserRole.INSTALLER),
        capabilities = FeatureCatalog.capabilitiesFor(setOf(UserRole.INSTALLER)),
    )
}
