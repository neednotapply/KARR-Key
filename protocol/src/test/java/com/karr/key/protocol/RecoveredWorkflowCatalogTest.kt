package com.karr.key.protocol

import com.karr.key.core.FeatureCatalog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveredWorkflowCatalogTest {
    @Test
    fun everyCatalogEntryIsBackedByDeviceActions() {
        val featureIds = FeatureCatalog.all.map { feature -> feature.id }.toSet()

        assertTrue(RecoveredWorkflowCatalog.featureIdsWithDeviceActions.isNotEmpty())
        assertEquals(
            featureIds,
            RecoveredWorkflowCatalog.featureIdsWithDeviceActions,
        )
    }

    @Test
    fun canonicalOperationsDeduplicateIdenticalPacketsAndRetainBindings() {
        val operations = RecoveredWorkflowCatalog.operations
        val operationPackets = operations.map { operation ->
            operation.command to operation.payload
        }

        assertEquals(21, operations.size)
        assertEquals(operationPackets.size, operationPackets.distinct().size)

        val readState = operations.single { operation ->
            operation.command == QtCommand.READ_STATE
        }
        assertEquals("Read unit state", readState.label)
        assertEquals(
            FeatureCatalog.all.map { feature -> feature.requiredRole }.toSet(),
            readState.roles,
        )
        assertTrue(readState.bindings.size > 3)

        val trunk = operations.single { operation ->
            operation.command == QtCommand.VEHICLE_CONTROL &&
                operation.payload == listOf(10)
        }
        assertEquals("Activate trunk output", trunk.label)
        assertEquals(2, trunk.bindings.size)
        assertEquals(
            setOf("customer.trunk", "installer.tests"),
            trunk.bindings.map { binding -> binding.feature.id }.toSet(),
        )
    }

    @Test
    fun operationIdsAreStableAndResolveBackToCanonicalOperations() {
        RecoveredWorkflowCatalog.operations.forEach { operation ->
            assertEquals(
                operation,
                RecoveredWorkflowCatalog.operationById(operation.id),
            )
        }
        assertEquals(
            "vehicle-control-0a",
            RecoveredWorkflowCatalog.operations.single { operation ->
                operation.command == QtCommand.VEHICLE_CONTROL &&
                    operation.payload == listOf(10)
            }.id,
        )
    }

    @Test
    fun customerVehicleControlsMatchRecoveredPayloads() {
        val lock = RecoveredWorkflowCatalog.actionsFor("customer.controls").single()
        val unlock = RecoveredWorkflowCatalog.actionsFor("customer.unlock").single()
        val trunk = RecoveredWorkflowCatalog.actionsFor("customer.trunk").single()
        val valet = RecoveredWorkflowCatalog.actionsFor("customer.valet")

        assertEquals(QtCommand.LOCK_DOORS, lock.command)
        assertEquals(listOf(0), lock.payload)
        assertEquals(QtSecurityState.ARMED, lock.expectedSecurityState)
        assertEquals(QtSecurityState.DISARMED, unlock.expectedSecurityState)
        assertEquals(QtCommand.VEHICLE_CONTROL, trunk.command)
        assertEquals(listOf(10), trunk.payload)
        assertEquals(
            RecoveredActionPreflight.QT6_RED_TRUNK_RELEASE,
            trunk.preflight,
        )
        assertTrue(
            trunk.compatibilityWith(KarrUnitModel.DR1_CAN) is
                RecoveredModelCompatibility.Unsupported,
        )
        assertEquals(
            RecoveredModelCompatibility.Supported,
            trunk.compatibilityWith(KarrUnitModel.QT6_RED),
        )
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
    fun silentControlsUseRecoveredPayloadAndSettingsPreflight() {
        val actions = RecoveredWorkflowCatalog.actionsFor("customer.silent_controls")

        assertEquals(
            listOf(QtCommand.SILENT_LOCK, QtCommand.SILENT_UNLOCK),
            actions.map(RecoveredDeviceAction::command),
        )
        assertTrue(actions.all { action -> action.payload == listOf(0) })
        assertTrue(
            actions.all { action ->
                action.preflight == RecoveredActionPreflight.SILENT_CONTROL_SETTINGS
            },
        )
        assertEquals(
            listOf(QtSecurityState.ARMED, QtSecurityState.DISARMED),
            actions.map(RecoveredDeviceAction::expectedSecurityState),
        )
        assertTrue(
            actions.all { action ->
                action.compatibilityWith(KarrUnitModel.QT6_GREEN) is
                    RecoveredModelCompatibility.Unsupported
            },
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
        assertEquals(null, action.expectedSecurityState)
        assertEquals(
            RecoveredModelCompatibility.Supported,
            action.compatibilityWith(KarrUnitModel.QT6_CAN),
        )
        assertTrue(
            action.compatibilityWith(KarrUnitModel.DR1_CAN) is
                RecoveredModelCompatibility.Unsupported,
        )
        assertTrue(
            action.compatibilityWith(KarrUnitModel.DR2_ESP) is
                RecoveredModelCompatibility.Unsupported,
        )
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
