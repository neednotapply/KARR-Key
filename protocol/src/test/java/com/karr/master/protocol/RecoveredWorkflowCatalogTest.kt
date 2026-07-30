package com.karr.master.protocol

import com.karr.master.core.FeatureCatalog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveredWorkflowCatalogTest {
    @Test
    fun everyMappedWorkflowTargetsAnExistingFeature() {
        val featureIds = FeatureCatalog.all.map { feature -> feature.id }.toSet()

        assertTrue(RecoveredWorkflowCatalog.featureIdsWithDeviceActions.isNotEmpty())
        assertTrue(
            RecoveredWorkflowCatalog.featureIdsWithDeviceActions.all(featureIds::contains),
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
    fun destructiveServiceActionsRequireConfirmationMetadata() {
        val destructiveActions = listOf(
            "dealer.remotes",
            "dealer.violations",
            "installer.tests",
        ).flatMap(RecoveredWorkflowCatalog::actionsFor).filter { action -> action.mutating }

        assertFalse(destructiveActions.isEmpty())
        assertTrue(destructiveActions.all { action -> action.description.isNotBlank() })
        assertTrue(
            destructiveActions
                .flatMap { action -> action.payload }
                .all { value -> value in 0..0xff },
        )
    }

    @Test
    fun artifactPagesDoNotExposeUnscopedTransferPackets() {
        val artifactActions = listOf(
            "dealer.artifacts",
            "dealer.firmware",
            "installer.can_transfer",
            "installer.firmware",
        ).flatMap(RecoveredWorkflowCatalog::actionsFor)
        val transferCommands = setOf(
            QtCommand.START_CAN_FILE_DOWNLOAD,
            QtCommand.CAN_FILE_CHUNK,
            QtCommand.CAN_FILE_DOWNLOAD_COMPLETE,
            QtCommand.BOOTLOAD_ENTER,
            QtCommand.BOOTLOAD_START,
            QtCommand.BOOTLOAD_CHUNK,
            QtCommand.BOOTLOAD_COMPLETE,
        )

        assertTrue(artifactActions.isNotEmpty())
        assertTrue(artifactActions.none { action -> action.command in transferCommands })
        assertTrue(artifactActions.none { action -> action.mutating })
    }
}
