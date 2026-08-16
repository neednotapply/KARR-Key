package com.karr.key.protocol

import com.karr.key.core.FeatureCatalog
import com.karr.key.core.FeatureDefinition
import com.karr.key.core.UserRole
import java.util.Locale

data class RecoveredDeviceAction(
    val id: String,
    val label: String,
    val description: String,
    val command: QtCommand,
    val payload: List<Int> = emptyList(),
    val mutating: Boolean = false,
    val source: String,
    val preflight: RecoveredActionPreflight? = null,
    val modelRequirement: RecoveredModelRequirement? = null,
    val modeRequirement: RecoveredModeRequirement? = null,
    val targetMode: KarrDeviceMode? = null,
    val expectedSecurityState: QtSecurityState? = null,
    val singleShot: Boolean = false,
    val experimental: Boolean = false,
) {
    init {
        require(id.isNotBlank())
        require(label.isNotBlank())
        require(payload.all { value -> value in 0..0xff })
        require(!mutating || description.isNotBlank())
        require(expectedSecurityState == null || mutating) {
            "Only mutating actions can declare an expected security state."
        }
        require(!singleShot || mutating) { "Only mutating actions can be single-shot." }
        targetMode?.let { target ->
            require(mutating && command == QtCommand.CHANGE_MODE) {
                "A target mode requires a mutating CHANGE_MODE action."
            }
            require(payload == listOf(target.code)) {
                "A mode-change payload must contain only the target mode code."
            }
        }
    }

    fun packet(): ByteArray = QtProtocolCodec.generateOriginal(
        command = command,
        payload = ByteArray(payload.size) { index -> payload[index].toByte() },
    )
}

data class RecoveredOperationBinding(
    val feature: FeatureDefinition,
    val action: RecoveredDeviceAction,
    val limitation: String?,
)

data class RecoveredDeviceOperation(
    val id: String,
    val label: String,
    val description: String,
    val category: String,
    val bindings: List<RecoveredOperationBinding>,
) {
    init {
        require(id.isNotBlank())
        require(label.isNotBlank())
        require(bindings.isNotEmpty())
        require(bindings.map { it.action.command }.distinct().size == 1)
        require(bindings.map { it.action.payload }.distinct().size == 1)
    }

    val roles: Set<UserRole>
        get() = bindings.mapTo(linkedSetOf()) { binding ->
            binding.feature.requiredRole
        }

    val command: QtCommand
        get() = bindings.first().action.command

    val payload: List<Int>
        get() = bindings.first().action.payload

    val mutating: Boolean
        get() = bindings.first().action.mutating
}

object RecoveredWorkflowCatalog {
    private const val CUSTOMER_SOURCE = "KARR BT 2.15 managed caller"
    private const val DEALER_SOURCE = "KARR BT Dealer 3.131 managed caller"
    private const val INSTALLER_SOURCE = "KARR Installer 1.39 managed caller"

    private val QT6_RED_ONLY = RecoveredModelRequirement(
        supportedModels = setOf(KarrUnitModel.QT6_RED),
        supportedDescription = "QT6 RED",
    )

    private val SILENT_CONTROL_MODELS = RecoveredModelRequirement(
        supportedModels = setOf(
            KarrUnitModel.QT6_CAN,
            KarrUnitModel.QT6_BLUE,
            KarrUnitModel.QT6_RED,
            KarrUnitModel.DR1_CAN,
        ),
        supportedDescription = "QT6 CAN, QT6 BLUE, QT6 RED, or DR1 CAN",
    )

    private val QT_FAMILY_ONLY = RecoveredModelRequirement(
        supportedFamilies = setOf(KarrUnitFamily.QT),
        supportedDescription = "QT models",
    )

    private val RECOVERED_QT6_MODELS = RecoveredModelRequirement(
        supportedModels = setOf(
            KarrUnitModel.QT6_CAN,
            KarrUnitModel.QT6_BLUE,
            KarrUnitModel.QT6_GREEN,
            KarrUnitModel.QT6_RED,
        ),
        supportedDescription = "recovered QT6 models",
    )

    private val INSTALLER_BUMP_TEST_MODELS = RecoveredModelRequirement(
        supportedModels = RECOVERED_QT6_MODELS.supportedModels + KarrUnitModel.DR1_CAN,
        supportedDescription = "recovered QT6 models or DR1 CAN",
    )

    private val INSTALLER_MODE_ONLY = RecoveredModeRequirement(
        supportedModes = setOf(KarrDeviceMode.INSTALLER),
        supportedDescription = "Installer mode",
    )

    private val USER_MODE_ONLY = RecoveredModeRequirement(
        supportedModes = setOf(KarrDeviceMode.USER),
        supportedDescription = "User mode",
    )

    private val VALET_MODE_ONLY = RecoveredModeRequirement(
        supportedModes = setOf(KarrDeviceMode.VALET),
        supportedDescription = "Valet mode",
    )

    private val DEALER_MODE_ONLY = RecoveredModeRequirement(
        supportedModes = setOf(KarrDeviceMode.DEALER),
        supportedDescription = "Dealer mode",
    )

    private val DEALER_ENTRY_MODES = RecoveredModeRequirement(
        supportedModes = setOf(
            KarrDeviceMode.USER,
            KarrDeviceMode.INSTALLER,
            KarrDeviceMode.NO_SALE,
            KarrDeviceMode.BCA,
        ),
        supportedDescription =
            "User, Installer, No sale, or Borrowed Car Agreement (BCA) mode",
    )

    private val USER_SERVICE_RETURN_MODES = RecoveredModeRequirement(
        supportedModes = setOf(KarrDeviceMode.DEALER, KarrDeviceMode.NO_SALE),
        supportedDescription = "Dealer or No sale mode",
    )

    private val commonIdentityReads = listOf(
        read("read-state", "Read unit state", QtCommand.READ_STATE),
        read("read-mode", "Read unit mode", QtCommand.READ_MODE),
    )

    private val commonDiagnostics = listOf(
        read("read-state", "Read unit state", QtCommand.READ_STATE),
        read(
            "read-battery",
            "Read vehicle battery",
            QtCommand.READ_VEHICLE_BATTERY_VOLTAGE,
        ),
        read("read-firmware", "Read firmware version", QtCommand.READ_FIRMWARE_VERSION),
        read("read-violations", "Read violations", QtCommand.READ_VIOLATIONS),
    )

    private val actions = mapOf(
        "customer.vehicles" to commonIdentityReads,
        "customer.controls" to listOf(
            mutate(
                id = "lock-arm",
                label = "Lock and arm",
                description = "Locks the doors and arms the connected unit.",
                command = QtCommand.LOCK_DOORS,
                payload = listOf(0),
                source = CUSTOMER_SOURCE,
                expectedSecurityState = QtSecurityState.ARMED,
            ),
        ),
        "customer.unlock" to listOf(
            mutate(
                id = "unlock-disarm",
                label = "Unlock and disarm",
                description = "Unlocks the doors and disarms the connected unit.",
                command = QtCommand.UNLOCK_DOORS,
                payload = listOf(0),
                source = CUSTOMER_SOURCE,
                expectedSecurityState = QtSecurityState.DISARMED,
            ),
        ),
        "customer.silent_controls" to listOf(
            mutate(
                id = "silent-lock",
                label = "Silent lock",
                description =
                    "Locks without arm tones using the compatible recovered command path.",
                command = QtCommand.SILENT_LOCK,
                payload = listOf(0),
                source = CUSTOMER_SOURCE,
                preflight = RecoveredActionPreflight.SILENT_CONTROL_SETTINGS,
                modelRequirement = SILENT_CONTROL_MODELS,
                expectedSecurityState = QtSecurityState.ARMED,
            ),
            mutate(
                id = "silent-unlock",
                label = "Silent unlock",
                description =
                    "Unlocks without disarm tones using the compatible recovered command path.",
                command = QtCommand.SILENT_UNLOCK,
                payload = listOf(0),
                source = CUSTOMER_SOURCE,
                preflight = RecoveredActionPreflight.SILENT_CONTROL_SETTINGS,
                modelRequirement = SILENT_CONTROL_MODELS,
                expectedSecurityState = QtSecurityState.DISARMED,
            ),
        ),
        "customer.trunk" to listOf(
            mutate(
                id = "trunk-release",
                label = "Release trunk",
                description =
                    "Activates the recovered trunk-release output on compatible QT6 RED systems.",
                command = QtCommand.VEHICLE_CONTROL,
                payload = listOf(10),
                source = CUSTOMER_SOURCE,
                preflight = RecoveredActionPreflight.QT6_RED_TRUNK_RELEASE,
                modelRequirement = QT6_RED_ONLY,
            ),
        ),
        "customer.valet" to listOf(
            mutate(
                id = "enter-valet",
                label = "Enter valet mode",
                description = "Changes the connected unit to recovered mode 5 (Valet).",
                command = QtCommand.CHANGE_MODE,
                payload = listOf(KarrDeviceMode.VALET.code),
                source = CUSTOMER_SOURCE,
                modeRequirement = USER_MODE_ONLY,
                targetMode = KarrDeviceMode.VALET,
            ),
            mutate(
                id = "return-user",
                label = "Return to user mode",
                description = "Changes the connected unit to recovered mode 2 (User).",
                command = QtCommand.CHANGE_MODE,
                payload = listOf(KarrDeviceMode.USER.code),
                source = CUSTOMER_SOURCE,
                modeRequirement = VALET_MODE_ONLY,
                targetMode = KarrDeviceMode.USER,
            ),
        ),
        "customer.immobilizer" to listOf(
            read("read-state", "Read immobilizer-related state", QtCommand.READ_STATE),
        ),
        "customer.remote_learning" to listOf(
            mutate(
                id = "start-remote-learning",
                label = "Start remote learning",
                description = "Places the connected unit into recovered remote-learning mode.",
                command = QtCommand.REMOTE_LEARN,
                payload = listOf(1),
                source = CUSTOMER_SOURCE,
            ),
            mutate(
                id = "stop-remote-learning",
                label = "Stop remote learning",
                description = "Stops recovered remote-learning mode.",
                command = QtCommand.REMOTE_LEARN,
                payload = listOf(0),
                source = CUSTOMER_SOURCE,
            ),
        ),
        "customer.diagnostics" to commonDiagnostics,
        "customer.settings" to listOf(
            read("read-settings", "Read all unit settings", QtCommand.READ_ALL_SETTINGS),
            read(
                "read-violation-inhibit",
                "Read violation inhibit settings",
                QtCommand.READ_VIOLATION_INHIBIT,
            ),
        ),
        "dealer.device" to commonIdentityReads + listOf(
            read("read-firmware", "Read firmware version", QtCommand.READ_FIRMWARE_VERSION),
        ),
        "dealer.settings" to listOf(
            read("read-settings", "Read all unit settings", QtCommand.READ_ALL_SETTINGS),
        ),
        "dealer.battery_ignition" to listOf(
            read(
                "read-battery",
                "Read vehicle battery",
                QtCommand.READ_VEHICLE_BATTERY_VOLTAGE,
            ),
            read("read-state", "Read ignition, door, and movement state", QtCommand.READ_STATE),
        ),
        "dealer.remotes" to remoteServiceActions(DEALER_SOURCE),
        "dealer.violations" to violationActions(DEALER_SOURCE),
        "dealer.diagnostics" to commonDiagnostics,
        "installer.unit" to commonIdentityReads,
        "installer.fitment" to commonIdentityReads + listOf(
            read(
                "read-battery",
                "Read vehicle battery",
                QtCommand.READ_VEHICLE_BATTERY_VOLTAGE,
            ),
        ),
        "installer.checks" to commonDiagnostics + listOf(
            readRemotes(),
        ),
        "installer.tests" to listOf(
            mutate(
                id = "bump-test",
                label = "Run bump test",
                description = "Runs the recovered installer bump-test command.",
                command = QtCommand.BUMP_TEST,
                payload = listOf(1),
                source = INSTALLER_SOURCE,
                modelRequirement = INSTALLER_BUMP_TEST_MODELS,
                modeRequirement = INSTALLER_MODE_ONLY,
            ),
            mutate(
                id = "test-lock",
                label = "Test arm / lock",
                description = "Runs the recovered installer lock validation.",
                command = QtCommand.LOCK_DOORS,
                payload = listOf(0),
                source = INSTALLER_SOURCE,
            ),
            mutate(
                id = "test-unlock",
                label = "Test disarm / unlock",
                description =
                    "Runs the recovered installer recovery step used to return the vehicle " +
                        "to a non-immobilized, disarmed state.",
                command = QtCommand.UNLOCK_DOORS,
                payload = listOf(0),
                source = INSTALLER_SOURCE,
                expectedSecurityState = QtSecurityState.DISARMED,
            ),
            mutate(
                id = "test-trunk",
                label = "Test trunk output",
                description = "Runs the recovered installer trunk-output validation.",
                command = QtCommand.VEHICLE_CONTROL,
                payload = listOf(10),
                source = INSTALLER_SOURCE,
            ),
        ),
        "installer.immobilizer_test" to listOf(
            mutate(
                id = "test-immobilizer-output",
                label = "Run experimental immobilizer-output test",
                description =
                    "Sends the hidden installer test candidate once and records the response. " +
                        "A protocol acknowledgement does not prove physical immobilization.",
                command = QtCommand.VEHICLE_CONTROL,
                payload = listOf(2),
                source = INSTALLER_SOURCE,
                preflight = RecoveredActionPreflight.IGNITION_OFF,
                modelRequirement = QT_FAMILY_ONLY,
                singleShot = true,
                experimental = true,
            ),
        ),
        "installer.mode_switches" to listOf(
            modeSwitch(
                id = "switch-installer-mode",
                target = KarrDeviceMode.INSTALLER,
                sourceModes = DEALER_MODE_ONLY,
                source = INSTALLER_SOURCE,
                experimental = false,
            ),
            modeSwitch(
                id = "switch-dealer-mode",
                target = KarrDeviceMode.DEALER,
                sourceModes = DEALER_ENTRY_MODES,
                source = "$DEALER_SOURCE and $INSTALLER_SOURCE",
                experimental = false,
            ),
            modeSwitch(
                id = "switch-user-mode",
                target = KarrDeviceMode.USER,
                sourceModes = USER_SERVICE_RETURN_MODES,
                source = DEALER_SOURCE,
                experimental = false,
            ),
            modeSwitch(
                id = "switch-no-sale-mode",
                target = KarrDeviceMode.NO_SALE,
                sourceModes = DEALER_MODE_ONLY,
                source = DEALER_SOURCE,
                experimental = false,
            ),
            modeSwitch(
                id = "switch-bca-mode",
                target = KarrDeviceMode.BCA,
                sourceModes = DEALER_MODE_ONLY,
                source = DEALER_SOURCE,
                experimental = false,
            ),
        ),
        "installer.repair" to commonDiagnostics,
        "installer.removal" to listOf(
            read("read-state", "Read unit state", QtCommand.READ_STATE),
            read("read-mode", "Read unit mode", QtCommand.READ_MODE),
        ),
        "installer.battery_removal" to listOf(
            read(
                "read-battery",
                "Read vehicle battery",
                QtCommand.READ_VEHICLE_BATTERY_VOLTAGE,
            ),
            read("read-state", "Read unit state", QtCommand.READ_STATE),
        ),
        "installer.settings" to listOf(
            read("read-settings", "Read all unit settings", QtCommand.READ_ALL_SETTINGS),
            read(
                "read-violation-inhibit",
                "Read violation inhibit settings",
                QtCommand.READ_VIOLATION_INHIBIT,
            ),
        ),
    )

    val featureIdsWithDeviceActions: Set<String>
        get() = actions.keys

    /**
     * User-facing operations are keyed by the packet they send. Role-specific
     * tools remain bindings so authorization and model checks are preserved
     * without listing the same device operation more than once.
     */
    val operations: List<RecoveredDeviceOperation> by lazy {
        FeatureCatalog.all
            .flatMap { feature ->
                actionsFor(feature.id).map { action ->
                    RecoveredOperationBinding(
                        feature = feature,
                        action = action,
                        limitation = limitationFor(feature.id),
                    )
                }
            }
            .groupBy { binding -> binding.action.operationId() }
            .map { (id, bindings) ->
                val first = bindings.first().action
                RecoveredDeviceOperation(
                    id = id,
                    label = canonicalLabel(first),
                    description = canonicalDescription(first),
                    category = when {
                        bindings.all { binding -> binding.action.experimental } ->
                            "Experimental"
                        first.mutating -> "Commands"
                        else -> "Reads"
                    },
                    bindings = bindings,
                )
            }
    }

    fun operationById(operationId: String): RecoveredDeviceOperation? =
        operations.firstOrNull { operation -> operation.id == operationId }

    /**
     * Operations offered for auto-connect & send: every read plus the standard mutating commands,
     * with Reads listed first. Excluded: experimental operations (the single-shot immobilizer-output
     * test, whose safety depends on an interactive ignition-off preflight) and standalone mode
     * switches (CHANGE_MODE) — a mode change now happens implicitly, driving a unit to a selected
     * command's required mode before sending, rather than being chosen on its own.
     */
    val autoConnectOperations: List<RecoveredDeviceOperation> by lazy {
        operations
            .filterNot { operation -> operation.category == "Experimental" }
            .filterNot { operation -> operation.command == QtCommand.CHANGE_MODE }
            .sortedWith(compareBy({ operation -> operation.category != "Reads" }, { it.label }))
    }

    fun actionsFor(featureId: String): List<RecoveredDeviceAction> =
        actions[featureId].orEmpty()

    fun limitationFor(featureId: String): String? = when (featureId) {
        "customer.immobilizer" ->
            "The recovered customer application changes immobilizer state through its " +
                "authorized server-messaging path. The direct BLE evidence supports state " +
                "inspection, not a customer immobilizer mutation."
        "installer.immobilizer_test" ->
            "This is a candidate recovered from a hidden experimental installer handler: " +
                "VEHICLE_CONTROL with output selector 2. Unit acknowledgement and physical " +
                "immobilizer behavior are intentionally reported as separate observations. " +
                "The preserved installer test sequence uses UNLOCK_DOORS to return to a " +
                "non-immobilized state and requires the technician to verify that result."
        "installer.mode_switches" ->
            "The Installer, Dealer, User, No sale, and Borrowed Car Agreement (BCA) " +
                "transitions have been confirmed by READ_MODE on authorized hardware. Each sends " +
                "CHANGE_MODE once, then requires a fresh READ_MODE to confirm the unit's actual " +
                "mode. Bootload uses a separate protocol path, and no ordinary Unconfigured " +
                "transition was recovered, so neither is exposed here."
        else -> null
    }

    private fun remoteServiceActions(source: String) = listOf(
        readRemotes(),
        mutate(
            id = "start-remote-learning",
            label = "Start remote learning",
            description = "Places the connected unit into recovered remote-learning mode.",
            command = QtCommand.REMOTE_LEARN,
            payload = listOf(1),
            source = source,
        ),
        mutate(
            id = "stop-remote-learning",
            label = "Stop remote learning",
            description = "Stops recovered remote-learning mode.",
            command = QtCommand.REMOTE_LEARN,
            payload = listOf(0),
            source = source,
        ),
        mutate(
            id = "clear-remotes",
            label = "Clear learned remotes",
            description = "Clears remotes stored by the connected unit.",
            command = QtCommand.CLEAR_REMOTES,
            source = source,
        ),
    )

    private fun violationActions(source: String) = listOf(
        read("read-violations", "Read violations", QtCommand.READ_VIOLATIONS),
        mutate(
            id = "clear-violations",
            label = "Clear violations",
            description = "Clears violation history stored by the connected unit.",
            command = QtCommand.CLEAR_VIOLATIONS,
            source = source,
        ),
    )

    private fun read(
        id: String,
        label: String,
        command: QtCommand,
        modelRequirement: RecoveredModelRequirement? = null,
    ) = RecoveredDeviceAction(
        id = id,
        label = label,
        description = "Requests ${command.name} from the authenticated unit.",
        command = command,
        modelRequirement = modelRequirement,
        source = when (command) {
            QtCommand.READ_ALL_SETTINGS,
            QtCommand.READ_VIOLATION_INHIBIT,
            -> CUSTOMER_SOURCE
            else -> "Shared recovered BLE callers"
        },
    )

    private fun readRemotes() = read(
        id = "read-remotes",
        label = "Read learned remotes",
        command = QtCommand.READ_REMOTES,
        modelRequirement = RECOVERED_QT6_MODELS,
    )

    private fun mutate(
        id: String,
        label: String,
        description: String,
        command: QtCommand,
        payload: List<Int> = emptyList(),
        source: String,
        preflight: RecoveredActionPreflight? = null,
        modelRequirement: RecoveredModelRequirement? = null,
        modeRequirement: RecoveredModeRequirement? = null,
        targetMode: KarrDeviceMode? = null,
        expectedSecurityState: QtSecurityState? = null,
        singleShot: Boolean = false,
        experimental: Boolean = false,
    ) = RecoveredDeviceAction(
        id = id,
        label = label,
        description = description,
        command = command,
        payload = payload,
        mutating = true,
        source = source,
        preflight = preflight,
        modelRequirement = modelRequirement,
        modeRequirement = modeRequirement,
        targetMode = targetMode,
        expectedSecurityState = expectedSecurityState,
        singleShot = singleShot,
        experimental = experimental,
    )

    private fun modeSwitch(
        id: String,
        target: KarrDeviceMode,
        sourceModes: RecoveredModeRequirement,
        source: String,
        experimental: Boolean = true,
    ) = mutate(
        id = id,
        label = "Switch to ${target.displayName} mode",
        description =
            "Sends the recovered ${target.displayName} mode transition once, then reads the " +
                "unit mode back before reporting success.",
        command = QtCommand.CHANGE_MODE,
        payload = listOf(target.code),
        source = source,
        modeRequirement = sourceModes,
        targetMode = target,
        singleShot = true,
        experimental = experimental,
    )

    private fun RecoveredDeviceAction.operationId(): String = buildString {
        append(command.name.lowercase(Locale.US).replace('_', '-'))
        payload.forEach { value ->
            append('-')
            append(value.toString(16).padStart(2, '0'))
        }
    }

    private fun canonicalLabel(action: RecoveredDeviceAction): String = when {
        action.command == QtCommand.READ_STATE -> "Read unit state"
        action.command == QtCommand.LOCK_DOORS && action.payload == listOf(0) ->
            "Lock and arm"
        action.command == QtCommand.UNLOCK_DOORS && action.payload == listOf(0) ->
            "Unlock and disarm"
        action.command == QtCommand.VEHICLE_CONTROL && action.payload == listOf(10) ->
            "Activate trunk output"
        else -> action.label
    }

    private fun canonicalDescription(action: RecoveredDeviceAction): String = when {
        action.command == QtCommand.READ_STATE ->
            "Reads the connected unit's operating, security, ignition, door, movement, " +
                "and immobilizer-related state."
        action.command == QtCommand.READ_MODE ->
            "Reads the connected unit's model, operating mode, and security state."
        action.command == QtCommand.UNLOCK_DOORS && action.payload == listOf(0) ->
            "Unlocks the doors and disarms the connected unit."
        action.command == QtCommand.READ_VEHICLE_BATTERY_VOLTAGE ->
            "Reads the vehicle battery voltage reported by the connected unit."
        action.command == QtCommand.READ_FIRMWARE_VERSION ->
            "Reads the connected unit's firmware and hardware version information."
        action.command == QtCommand.READ_VIOLATIONS ->
            "Reads locally stored alarm and violation history from the connected unit."
        action.command == QtCommand.READ_ALL_SETTINGS ->
            "Reads the connected unit's configurable security and control settings."
        action.command == QtCommand.READ_VIOLATION_INHIBIT ->
            "Reads which alarm and violation inputs are currently inhibited."
        action.command == QtCommand.READ_REMOTES ->
            "Reads the learned remote-control records stored by the connected unit."
        action.command == QtCommand.VEHICLE_CONTROL && action.payload == listOf(10) ->
            "Activates the recovered trunk-release output for an authorized validation."
        else -> action.description
    }

}
