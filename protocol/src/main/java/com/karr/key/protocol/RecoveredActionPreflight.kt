package com.karr.key.protocol

enum class RecoveredActionPreflight {
    IGNITION_OFF,
}

data class QtVehicleState(
    val ignitionOn: Boolean,
)

sealed interface RecoveredPreflightDecision {
    data object Allowed : RecoveredPreflightDecision
    data class Denied(val reason: String) : RecoveredPreflightDecision
}

fun RecoveredActionPreflight.evaluate(
    response: QtPacket,
): RecoveredPreflightDecision = when (this) {
    RecoveredActionPreflight.IGNITION_OFF -> evaluateIgnitionOff(response)
}

private fun evaluateIgnitionOff(response: QtPacket): RecoveredPreflightDecision {
    if (response.command != QtCommand.READ_STATE) {
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
        RecoveredPreflightDecision.Allowed
    }
}

private fun ByteArray.toVehicleState(): QtVehicleState? {
    val primaryState = firstOrNull()?.toInt()?.and(0xff) ?: return null
    return QtVehicleState(
        ignitionOn = primaryState and IGNITION_ON_MASK == IGNITION_ON_MASK,
    )
}

private const val IGNITION_ON_MASK = 0x40
