package com.karr.key.protocol

import com.karr.key.core.FeatureCatalog
import com.karr.key.core.UserRole
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
    fun operationDangerScoresStayInRangeAndBumpForExperimental() {
        RecoveredWorkflowCatalog.operations.forEach { operation ->
            assertTrue(
                "${operation.id} danger ${operation.dangerScore} out of range",
                operation.dangerScore in 0..10,
            )
        }
        // The experimental immobilizer-output test scores above the plain command it rides on.
        val immobilizer = RecoveredWorkflowCatalog.operations
            .first { operation -> operation.bindings.any { it.action.experimental } }
        assertTrue(immobilizer.dangerScore > immobilizer.command.dangerScore)
    }

    @Test
    fun lowestRoleIsTheLeastPrivilegedPermittedRole() {
        // Read unit state is shared by all three roles; its floor is Customer.
        val readState = RecoveredWorkflowCatalog.operationById("read-state")
        assertTrue(readState != null)
        assertEquals(
            setOf(UserRole.CUSTOMER, UserRole.DEALER, UserRole.INSTALLER),
            readState!!.roles,
        )
        assertEquals(UserRole.CUSTOMER, readState.lowestRole)

        // For every operation the floor is one of its roles and the minimum by privilege order.
        RecoveredWorkflowCatalog.operations.forEach { operation ->
            assertTrue(operation.lowestRole in operation.roles)
            assertEquals(
                operation.roles.minByOrNull { role -> role.ordinal },
                operation.lowestRole,
            )
        }
    }

    @Test
    fun hardwareValidatedReadsArePromotedFromTheConsole() {
        val ids = RecoveredWorkflowCatalog.operations.map { operation -> operation.id }.toSet()
        listOf(
            "read-vin", "read-serial",
            "read-io-protection", "read-reset-cause", "get-tilt-sensitivity", "debug-message",
            "can-filename", "can-file-version", "can-read-flag",
            "check-can-errors", "bus-state", "gmlan-timing-read",
        ).forEach { id -> assertTrue("$id should be a promoted operation", id in ids) }

        // Identity/info reads read at the Customer floor; the deeper CAN/bus reads at the Dealer floor.
        assertEquals(UserRole.CUSTOMER, RecoveredWorkflowCatalog.operationById("read-vin")!!.lowestRole)
        assertEquals(
            UserRole.DEALER,
            RecoveredWorkflowCatalog.operationById("can-filename")!!.lowestRole,
        )
        // All promoted reads are non-mutating.
        listOf("read-vin", "can-filename", "gmlan-timing-read").forEach { id ->
            assertFalse(RecoveredWorkflowCatalog.operationById(id)!!.mutating)
        }
    }

    @Test
    fun knownPayloadsSurfaceRecoveredValuesAndOmitEmptyReads() {
        val known = RecoveredWorkflowCatalog.knownPayloadsByCommand

        // A command with multiple recovered payloads exposes each distinct one.
        val vehicleControl = known[QtCommand.VEHICLE_CONTROL].orEmpty().map { it.payload }
        assertTrue(vehicleControl.contains(listOf(10)))
        assertTrue(vehicleControl.contains(listOf(2)))

        // CHANGE_MODE exposes the recovered target-mode codes.
        val modeCodes = known[QtCommand.CHANGE_MODE].orEmpty().map { it.payload }
        assertTrue(modeCodes.contains(listOf(KarrDeviceMode.VALET.code)))
        assertTrue(modeCodes.contains(listOf(KarrDeviceMode.INSTALLER.code)))

        // A single-payload command still appears.
        assertEquals(listOf(listOf(1)), known[QtCommand.BUMP_TEST].orEmpty().map { it.payload })

        // Plain reads take no payload, so they are absent rather than listed with an empty one.
        assertFalse(known.containsKey(QtCommand.READ_STATE))
        // Every listed payload is non-empty and every label is populated.
        known.values.flatten().forEach { entry ->
            assertTrue(entry.payload.isNotEmpty())
            assertTrue(entry.label.isNotBlank())
        }
    }

    @Test
    fun autoConnectOperationsOfferReadsAndCommandsButExcludeExperimentalAndModeSwitches() {
        val ops = RecoveredWorkflowCatalog.autoConnectOperations
        val experimentalIds = RecoveredWorkflowCatalog.operations
            .filter { operation -> operation.category == "Experimental" }
            .map { operation -> operation.id }

        assertTrue(experimentalIds.isNotEmpty())
        assertTrue(ops.any { operation -> operation.category == "Reads" })
        assertTrue(ops.any { operation -> operation.category == "Commands" })
        assertTrue(ops.none { operation -> operation.id in experimentalIds })
        // Standalone mode switches are excluded; a mode change happens implicitly before a send.
        assertTrue(ops.none { operation -> operation.command == QtCommand.CHANGE_MODE })
        // The default auto-connect operation stays available.
        assertTrue(ops.any { operation -> operation.id == "read-state" })
        // Reads are listed before commands.
        val lastReadIndex = ops.indexOfLast { operation -> operation.category == "Reads" }
        val firstCommandIndex = ops.indexOfFirst { operation -> operation.category == "Commands" }
        assertTrue(lastReadIndex < firstCommandIndex)
    }

    @Test
    fun canonicalOperationsDeduplicateIdenticalPacketsAndRetainBindings() {
        val operations = RecoveredWorkflowCatalog.operations
        val operationPackets = operations.map { operation ->
            operation.command to operation.payload
        }

        assertEquals(38, operations.size)
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

        val unlock = operations.single { operation ->
            operation.command == QtCommand.UNLOCK_DOORS && operation.payload == listOf(0)
        }
        assertEquals("Unlock and disarm", unlock.label)
        assertEquals(
            setOf("customer.unlock", "installer.tests"),
            unlock.bindings.map { binding -> binding.feature.id }.toSet(),
        )

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
        assertEquals(
            listOf(KarrDeviceMode.VALET, KarrDeviceMode.USER),
            valet.map(RecoveredDeviceAction::targetMode),
        )
        assertEquals(
            RecoveredModelCompatibility.Supported,
            valet.first().compatibilityWith(KarrUnitModel.QT6_RED, KarrDeviceMode.USER),
        )
        assertTrue(
            valet.first().compatibilityWith(KarrUnitModel.QT6_RED, KarrDeviceMode.VALET) is
                RecoveredModelCompatibility.Unsupported,
        )
        assertEquals(
            RecoveredModelCompatibility.Supported,
            valet.last().compatibilityWith(KarrUnitModel.QT6_RED, KarrDeviceMode.VALET),
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
    fun learnedRemoteReadIsLimitedToRecoveredQt6Models() {
        val action = RecoveredWorkflowCatalog.operations.single { operation ->
            operation.command == QtCommand.READ_REMOTES
        }.bindings.first().action

        assertTrue(
            action.compatibilityWith(
                KarrUnitModel.DR1_CAN,
                KarrDeviceMode.USER,
            ) is RecoveredModelCompatibility.Unsupported,
        )
        assertTrue(
            action.compatibilityWith(
                KarrUnitModel.DR2_ESP,
                KarrDeviceMode.DEALER,
            ) is RecoveredModelCompatibility.Unsupported,
        )
        assertEquals(
            RecoveredModelCompatibility.Supported,
            action.compatibilityWith(
                KarrUnitModel.QT6_RED,
                KarrDeviceMode.USER,
            ),
        )
        assertTrue(
            action.compatibilityWith(
                KarrUnitModel.QT7_GREEN,
                KarrDeviceMode.USER,
            ) is RecoveredModelCompatibility.Unsupported,
        )
    }

    @Test
    fun bumpTestRequiresInstallerOperatingMode() {
        val action = RecoveredWorkflowCatalog.operations.single { operation ->
            operation.command == QtCommand.BUMP_TEST
        }.bindings.single().action

        val blocked = action.compatibilityWith(
            KarrUnitModel.DR1_CAN,
            KarrDeviceMode.USER,
        )
        assertTrue(blocked is RecoveredModelCompatibility.Unsupported)
        assertEquals(
            RecoveredCompatibilityConstraint.UNIT_MODE,
            (blocked as RecoveredModelCompatibility.Unsupported).constraint,
        )
        assertTrue(blocked.reason.contains("User mode"))
        assertEquals(
            RecoveredModelCompatibility.Supported,
            action.compatibilityWith(
                KarrUnitModel.DR1_CAN,
                KarrDeviceMode.INSTALLER,
            ),
        )
        assertTrue(
            action.compatibilityWith(
                KarrUnitModel.DR2_ESP,
                KarrDeviceMode.INSTALLER,
            ) is RecoveredModelCompatibility.Unsupported,
        )
        assertTrue(
            action.compatibilityWith(KarrUnitModel.DR1_CAN) is
                RecoveredModelCompatibility.Unknown,
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
    fun installerUnlockIsTheRecoveredDisarmedRecoveryState() {
        val action = RecoveredWorkflowCatalog.actionsFor("installer.tests")
            .single { candidate -> candidate.command == QtCommand.UNLOCK_DOORS }

        assertEquals(listOf(0), action.payload)
        assertEquals(QtSecurityState.DISARMED, action.expectedSecurityState)
        assertFalse(action.experimental)
    }

    @Test
    fun serviceModeSwitchesExposeOnlyRecoveredOrdinaryTargets() {
        val actions = RecoveredWorkflowCatalog.actionsFor("installer.mode_switches")

        assertEquals(
            setOf(
                KarrDeviceMode.INSTALLER,
                KarrDeviceMode.DEALER,
                KarrDeviceMode.USER,
                KarrDeviceMode.NO_SALE,
                KarrDeviceMode.BCA,
            ),
            actions.mapNotNull(RecoveredDeviceAction::targetMode).toSet(),
        )
        assertTrue(actions.all { action -> action.command == QtCommand.CHANGE_MODE })
        assertTrue(actions.all(RecoveredDeviceAction::mutating))
        assertTrue(actions.all(RecoveredDeviceAction::singleShot))
        assertTrue(actions.none(RecoveredDeviceAction::experimental))
        assertTrue(
            actions.none { action ->
                action.targetMode in setOf(
                    KarrDeviceMode.BOOTLOAD,
                    KarrDeviceMode.UNCONFIGURED,
                )
            },
        )
    }

    @Test
    fun modeSwitchCatalogDescriptionsStayCompact() {
        val modeDescriptions = RecoveredWorkflowCatalog.operations
            .filter { operation -> operation.command == QtCommand.CHANGE_MODE }
            .associate { operation ->
                KarrDeviceMode.entries.single { mode ->
                    mode.code == operation.payload.single()
                } to operation.description
            }

        assertEquals(
            setOf(
                KarrDeviceMode.INSTALLER,
                KarrDeviceMode.DEALER,
                KarrDeviceMode.USER,
                KarrDeviceMode.NO_SALE,
                KarrDeviceMode.BCA,
                KarrDeviceMode.VALET,
            ),
            modeDescriptions.keys,
        )
        assertTrue(modeDescriptions.values.none { description -> description.contains("Mode effect:") })
        assertEquals(
            "Changes the connected unit to recovered mode 5 (Valet).",
            modeDescriptions.getValue(KarrDeviceMode.VALET),
        )
        assertTrue(
            modeDescriptions.values.all { description ->
                description.startsWith("Changes the connected unit ") ||
                    description.startsWith("Sends the recovered ")
            },
        )
    }

    @Test
    fun valetAndServiceReturnsToUserAreConfirmed() {
        val customerReturn = RecoveredWorkflowCatalog.actionsFor("customer.valet")
            .single { action -> action.targetMode == KarrDeviceMode.USER }
        val serviceReturn = RecoveredWorkflowCatalog.actionsFor("installer.mode_switches")
            .single { action -> action.targetMode == KarrDeviceMode.USER }

        assertFalse(customerReturn.experimental)
        assertFalse(serviceReturn.experimental)
    }

    @Test
    fun confirmedModeSwitchesFollowRecoveredSourceModeGraph() {
        val actions = RecoveredWorkflowCatalog.actionsFor("installer.mode_switches")
            .associateBy(RecoveredDeviceAction::targetMode)

        assertEquals(
            RecoveredModelCompatibility.Supported,
            actions.getValue(KarrDeviceMode.INSTALLER)
                .compatibilityWith(KarrUnitModel.QT6_RED, KarrDeviceMode.DEALER),
        )
        assertTrue(
            actions.getValue(KarrDeviceMode.INSTALLER)
                .compatibilityWith(KarrUnitModel.QT6_RED, KarrDeviceMode.USER) is
                RecoveredModelCompatibility.Unsupported,
        )
        assertEquals(
            RecoveredModelCompatibility.Supported,
            actions.getValue(KarrDeviceMode.DEALER)
                .compatibilityWith(KarrUnitModel.QT6_RED, KarrDeviceMode.INSTALLER),
        )
        assertTrue(
            actions.getValue(KarrDeviceMode.BCA)
                .compatibilityWith(KarrUnitModel.QT6_RED, KarrDeviceMode.USER) is
                RecoveredModelCompatibility.Unsupported,
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
            "installer.mode_switches",
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
