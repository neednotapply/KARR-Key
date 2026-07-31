package com.karr.key.protocol

import com.karr.key.core.FeatureCatalog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveredWorkflowCatalogTest {
    @Test
    fun everyCatalogEntryIsBackedByBluetoothConnectionOrDeviceActions() {
        val featureIds = FeatureCatalog.all.map { feature -> feature.id }.toSet()
        val connectionOnlyFeatureIds = setOf(
            "dealer.bluetooth",
            "installer.bluetooth",
        )

        assertTrue(RecoveredWorkflowCatalog.featureIdsWithDeviceActions.isNotEmpty())
        assertEquals(
            featureIds,
            RecoveredWorkflowCatalog.featureIdsWithDeviceActions + connectionOnlyFeatureIds,
        )
    }

    @Test
    fun customerVehicleControlsMatchRecoveredPayloads() {
        val lock = RecoveredWorkflowCatalog.actionsFor("customer.controls").single()
        val trunk = RecoveredWorkflowCatalog.actionsFor("customer.trunk").single()
        val valet = RecoveredWorkflowCatalog.actionsFor("customer.valet")

        assertEquals(QtCommand.LOCK_DOORS, lock.command)
        assertEquals(listOf(0), lock.payload)
        assertEquals(QtCommand.VEHICLE_CONTROL, trunk.command)
        assertEquals(listOf(10), trunk.payload)
        assertEquals(
            listOf(KarrDeviceMode.VALET.code, KarrDeviceMode.USER.code),
            valet.map { action -> action.payload.single() },
        )
        assertTrue(lock.mutating)
        assertTrue(trunk.mutating)
    }

    @Test
    fun generatedActionPacketUsesOriginalCodec() {
        val action = RecoveredWorkflowCatalog.actionsFor("customer.trunk").single()

        assertArrayEquals(
            QtProtocolCodec.generateOriginal(
                QtCommand.VEHICLE_CONTROL,
                byteArrayOf(10),
            ),
            action.packet(),
        )
    }

    @Test
    fun installerImmobilizerTestMatchesRecoveredExperimentalOutputSelector() {
        val action = RecoveredWorkflowCatalog.actionsFor("installer.immobilizer_test").single()

        assertEquals(QtCommand.VEHICLE_CONTROL, action.command)
        assertEquals(listOf(2), action.payload)
        assertEquals(RecoveredActionPreflight.IGNITION_OFF, action.preflight)
        assertTrue(action.mutating)
        assertTrue(action.singleShot)
        assertTrue(action.experimental)
        assertArrayEquals(
            byteArrayOf(
                0xAA.toByte(),
                0x00,
                0x02,
                0x18,
                0x02,
                0xE4.toByte(),
            ),
            action.packet(),
        )
    }

    @Test
    fun customerImmobilizerRemainsReadOnly() {
        val actions = RecoveredWorkflowCatalog.actionsFor("customer.immobilizer")

        assertEquals(1, actions.size)
        assertEquals(QtCommand.READ_STATE, actions.single().command)
        assertFalse(actions.single().mutating)
    }

    @Test
    fun destructiveServiceActionsRequireConfirmationMetadata() {
        val destructiveActions = listOf(
            "dealer.remotes",
            "dealer.violations",
            "installer.tests",
            "installer.immobilizer_test",
        ).flatMap(RecoveredWorkflowCatalog::actionsFor).filter { action -> action.mutating }

        assertFalse(destructiveActions.isEmpty())
        assertTrue(destructiveActions.all { action -> action.description.isNotBlank() })
        assertTrue(
            destructiveActions
                .flatMap { action -> action.payload }
                .all { value -> value in 0..0xff },
        )
    }

}
