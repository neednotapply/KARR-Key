package com.karr.key.protocol

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
    }

    fun packet(): ByteArray = QtProtocolCodec.generateOriginal(
        command = command,
        payload = ByteArray(payload.size) { index -> payload[index].toByte() },
    )
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
        "customer.device" to commonIdentityReads,
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
            ),
            mutate(
                id = "return-user",
                label = "Return to user mode",
                description = "Changes the connected unit to recovered mode 2 (User).",
                command = QtCommand.CHANGE_MODE,
                payload = listOf(KarrDeviceMode.USER.code),
                source = CUSTOMER_SOURCE,
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
            read("read-remotes", "Read learned remotes", QtCommand.READ_REMOTES),
        ),
        "installer.tests" to listOf(
            mutate(
                id = "bump-test",
                label = "Run bump test",
                description = "Runs the recovered installer bump-test command.",
                command = QtCommand.BUMP_TEST,
                payload = listOf(1),
                source = INSTALLER_SOURCE,
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
                description = "Runs the recovered installer unlock validation.",
                command = QtCommand.UNLOCK_DOORS,
                payload = listOf(0),
                source = INSTALLER_SOURCE,
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
                "immobilizer behavior are intentionally reported as separate observations."
        else -> null
    }

    private fun remoteServiceActions(source: String) = listOf(
        read("read-remotes", "Read learned remotes", QtCommand.READ_REMOTES),
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
    ) = RecoveredDeviceAction(
        id = id,
        label = label,
        description = "Requests ${command.name} from the authenticated unit.",
        command = command,
        source = when (command) {
            QtCommand.READ_ALL_SETTINGS,
            QtCommand.READ_VIOLATION_INHIBIT,
            -> CUSTOMER_SOURCE
            else -> "Shared recovered BLE callers"
        },
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
        expectedSecurityState = expectedSecurityState,
        singleShot = singleShot,
        experimental = experimental,
    )

}
