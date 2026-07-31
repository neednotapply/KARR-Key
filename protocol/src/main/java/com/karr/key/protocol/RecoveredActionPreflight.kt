package com.karr.key.protocol

enum class RecoveredActionPreflight(
    val requestCommands: List<QtCommand>,
) {
    IGNITION_OFF(listOf(QtCommand.READ_STATE)),
    QT6_RED_TRUNK_RELEASE(listOf(QtCommand.READ_MODE)),
    SILENT_CONTROL_SETTINGS(
        listOf(
            QtCommand.READ_MODE,
            QtCommand.READ_ALL_SETTINGS,
        ),
    ),
}

data class QtVehicleState(
    val ignitionOn: Boolean,
)

sealed interface RecoveredPreflightDecision {
    data class Allowed(
        val commandOverride: QtCommand? = null,
        val explanation: String? = null,
    ) : RecoveredPreflightDecision

    data class Denied(val reason: String) : RecoveredPreflightDecision
}

fun RecoveredActionPreflight.evaluate(
    response: QtPacket,
): RecoveredPreflightDecision = evaluate(listOf(response))

fun RecoveredActionPreflight.evaluate(
    responses: List<QtPacket>,
    attemptedCommand: QtCommand? = null,
): RecoveredPreflightDecision = when (this) {
    RecoveredActionPreflight.IGNITION_OFF ->
        evaluateIgnitionOff(responses.responseFor(QtCommand.READ_STATE))
    RecoveredActionPreflight.QT6_RED_TRUNK_RELEASE ->
        evaluateQt6RedTrunkRelease(responses.responseFor(QtCommand.READ_MODE))
    RecoveredActionPreflight.SILENT_CONTROL_SETTINGS ->
        evaluateSilentControl(
            modeResponse = responses.responseFor(QtCommand.READ_MODE),
            settingsResponse = responses.responseFor(QtCommand.READ_ALL_SETTINGS),
            attemptedCommand = attemptedCommand,
        )
}

private fun List<QtPacket>.responseFor(command: QtCommand): QtPacket? =
    firstOrNull { response -> response.command == command }

private fun evaluateIgnitionOff(response: QtPacket?): RecoveredPreflightDecision {
    if (response == null) {
        return RecoveredPreflightDecision.Denied(
            "Ignition preflight did not receive a READ_STATE response.",
        )
    }
    val state = response.payload.toVehicleState()
        ?: return RecoveredPreflightDecision.Denied(
            "Ignition preflight returned an empty or malformed state payload.",
        )
    return if (state.ignitionOn) {
        RecoveredPreflightDecision.Denied(
            "Command blocked because the connected unit reports ignition on.",
        )
    } else {
        RecoveredPreflightDecision.Allowed()
    }
}

private fun ByteArray.toVehicleState(): QtVehicleState? {
    val primaryState = firstOrNull()?.toInt()?.and(0xff) ?: return null
    return QtVehicleState(
        ignitionOn = primaryState and IGNITION_ON_MASK == IGNITION_ON_MASK,
    )
}

private fun evaluateQt6RedTrunkRelease(response: QtPacket?): RecoveredPreflightDecision {
    if (response == null) {
        return RecoveredPreflightDecision.Denied(
            "Trunk compatibility check did not receive a READ_MODE response.",
        )
    }
    if (response.payload.size < READ_MODE_MINIMUM_PAYLOAD_BYTES) {
        return RecoveredPreflightDecision.Denied(
            "Trunk compatibility check returned an incomplete unit identity.",
        )
    }

    val mode = KarrDeviceMode.fromStatusByte(response.payload[0])
    val unitModel = KarrUnitModel.fromTypeByte(response.payload[1])
    if (unitModel != KarrUnitModel.QT6_RED) {
        return RecoveredPreflightDecision.Denied(
            "Trunk release is unavailable: the recovered customer apps expose this output " +
                "only for QT6 RED systems, but the connected unit reports " +
                "${unitModel.displayName}.",
        )
    }
    if (mode != KarrDeviceMode.USER && mode != KarrDeviceMode.VALET) {
        return RecoveredPreflightDecision.Denied(
            "Trunk release is unavailable while the connected unit is in " +
                "${mode.name.lowercase().replace('_', ' ')} mode.",
        )
    }
    return RecoveredPreflightDecision.Allowed()
}

private fun evaluateSilentControl(
    modeResponse: QtPacket?,
    settingsResponse: QtPacket?,
    attemptedCommand: QtCommand?,
): RecoveredPreflightDecision {
    if (modeResponse == null) {
        return RecoveredPreflightDecision.Denied(
            "Silent-control compatibility check did not receive a READ_MODE response.",
        )
    }
    if (modeResponse.payload.size < READ_MODE_MINIMUM_PAYLOAD_BYTES) {
        return RecoveredPreflightDecision.Denied(
            "Silent-control compatibility check returned an incomplete unit identity.",
        )
    }
    val mode = KarrDeviceMode.fromStatusByte(modeResponse.payload[0])
    if (mode != KarrDeviceMode.USER && mode != KarrDeviceMode.VALET) {
        return RecoveredPreflightDecision.Denied(
            "Silent controls are unavailable while the connected unit is in " +
                "${mode.name.lowercase().replace('_', ' ')} mode.",
        )
    }

    val unitModel = KarrUnitModel.fromTypeByte(modeResponse.payload[1])
    if (unitModel == KarrUnitModel.QT6_GREEN) {
        return RecoveredPreflightDecision.Denied(
            "Silent controls are unavailable: the recovered customer apps hide them for " +
                "QT6 GREEN systems.",
        )
    }
    if (unitModel !in SILENT_CONTROL_UNIT_MODELS) {
        return RecoveredPreflightDecision.Denied(
            "Silent controls are unavailable for ${unitModel.displayName}.",
        )
    }

    val settings = settingsResponse?.payload
        ?: return RecoveredPreflightDecision.Denied(
            "Silent-control compatibility check did not receive unit settings.",
        )
    if (settings.size <= ARM_DISARM_TONES_INDEX) {
        return RecoveredPreflightDecision.Denied(
            "Silent-control compatibility check returned incomplete unit settings.",
        )
    }

    val tonesEnabled = settings[ARM_DISARM_TONES_INDEX].toInt() != 0
    if (!tonesEnabled) {
        val standardCommand = when (attemptedCommand) {
            QtCommand.SILENT_LOCK -> QtCommand.LOCK_DOORS
            QtCommand.SILENT_UNLOCK -> QtCommand.UNLOCK_DOORS
            else -> null
        } ?: return RecoveredPreflightDecision.Denied(
            "Silent-control compatibility check could not resolve the requested door action.",
        )
        return RecoveredPreflightDecision.Allowed(
            commandOverride = standardCommand,
            explanation =
                "Arm/disarm tones are already off. Using ${standardCommand.name}, matching " +
                    "the recovered app's silent configuration behavior.",
        )
    }

    if (unitModel == KarrUnitModel.QT6_CAN || unitModel == KarrUnitModel.DR1_CAN) {
        if (settings.size <= ALARM_COLOR_INDEX) {
            return RecoveredPreflightDecision.Denied(
                "Silent-control compatibility check could not read the CAN system color.",
            )
        }
        val color = settings[ALARM_COLOR_INDEX].toInt() and 0xff
        if (color != BLUE_ALARM_COLOR) {
            return RecoveredPreflightDecision.Denied(
                "The recovered KARR BT controls expose the dedicated silent commands only " +
                    "for Blue CAN/DR configurations when arm/disarm tones are enabled.",
            )
        }
    }

    return RecoveredPreflightDecision.Allowed(
        explanation =
            "Unit identity and arm/disarm tone settings support the dedicated silent command.",
    )
}

private const val IGNITION_ON_MASK = 0x40
private const val READ_MODE_MINIMUM_PAYLOAD_BYTES = 2
private val SILENT_CONTROL_UNIT_MODELS = setOf(
    KarrUnitModel.QT6_CAN,
    KarrUnitModel.QT6_BLUE,
    KarrUnitModel.QT6_RED,
    KarrUnitModel.DR1_CAN,
)
private const val ARM_DISARM_TONES_INDEX = 6
private const val ALARM_COLOR_INDEX = 19
private const val BLUE_ALARM_COLOR = 1
