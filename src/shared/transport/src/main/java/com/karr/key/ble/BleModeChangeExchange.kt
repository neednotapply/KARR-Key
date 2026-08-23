package com.karr.key.ble

import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtPacket
import com.karr.key.protocol.QtProtocolCodec

enum class ModeChangeStage {
    CHANGE_MODE,
    READ_MODE,
}

sealed interface BleModeChangeResult {
    data class Confirmed(
        val targetMode: KarrDeviceMode,
        val readResponse: QtPacket,
        val rawFrame: ByteArray,
    ) : BleModeChangeResult

    data class ObservedDifferent(
        val targetMode: KarrDeviceMode,
        val observedMode: KarrDeviceMode,
        val readResponse: QtPacket,
        val rawFrame: ByteArray,
    ) : BleModeChangeResult

    data class Rejected(
        val stage: ModeChangeStage,
        val response: QtPacket,
        val rawFrame: ByteArray,
    ) : BleModeChangeResult

    data class TimedOut(val stage: ModeChangeStage) : BleModeChangeResult

    data class TransportFailure(
        val stage: ModeChangeStage,
        val message: String,
    ) : BleModeChangeResult

    data class MalformedRead(
        val response: QtPacket,
        val rawFrame: ByteArray,
    ) : BleModeChangeResult
}

/**
 * Sends a recovered CHANGE_MODE command once, then confirms the unit's actual
 * state with a fresh READ_MODE. A failed confirmation never resends the
 * mutating command.
 */
class BleModeChangeExchange(
    controller: BleController,
) {
    private val exchange = BleCommandExchange(controller)

    suspend fun changeAndConfirm(
        changePacket: ByteArray,
        targetMode: KarrDeviceMode,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onChangeGattAccepted: suspend () -> Unit = {},
        onReadGattAccepted: suspend () -> Unit = {},
    ): BleModeChangeResult {
        when (
            val changeResult = exchange.sendAndAwait(
                packet = changePacket,
                expectedCommand = QtCommand.CHANGE_MODE,
                timeoutMillis = timeoutMillis,
                onGattAccepted = onChangeGattAccepted,
            )
        ) {
            is BleCommandExchangeResult.Acknowledged -> Unit
            is BleCommandExchangeResult.Rejected ->
                return BleModeChangeResult.Rejected(
                    stage = ModeChangeStage.CHANGE_MODE,
                    response = changeResult.response,
                    rawFrame = changeResult.rawFrame,
                )
            BleCommandExchangeResult.TimedOut ->
                return BleModeChangeResult.TimedOut(ModeChangeStage.CHANGE_MODE)
            is BleCommandExchangeResult.TransportFailure ->
                return BleModeChangeResult.TransportFailure(
                    stage = ModeChangeStage.CHANGE_MODE,
                    message = changeResult.message,
                )
        }

        return when (
            val readResult = exchange.sendAndAwait(
                packet = QtProtocolCodec.generateOriginal(QtCommand.READ_MODE),
                expectedCommand = QtCommand.READ_MODE,
                acceptGenericAck = false,
                timeoutMillis = timeoutMillis,
                onGattAccepted = onReadGattAccepted,
            )
        ) {
            is BleCommandExchangeResult.Acknowledged -> {
                val observedMode = readResult.response.payload.firstOrNull()
                    ?.let(KarrDeviceMode::fromStatusByte)
                    ?: return BleModeChangeResult.MalformedRead(
                        response = readResult.response,
                        rawFrame = readResult.rawFrame,
                    )
                if (observedMode == targetMode) {
                    BleModeChangeResult.Confirmed(
                        targetMode = targetMode,
                        readResponse = readResult.response,
                        rawFrame = readResult.rawFrame,
                    )
                } else {
                    BleModeChangeResult.ObservedDifferent(
                        targetMode = targetMode,
                        observedMode = observedMode,
                        readResponse = readResult.response,
                        rawFrame = readResult.rawFrame,
                    )
                }
            }
            is BleCommandExchangeResult.Rejected -> BleModeChangeResult.Rejected(
                stage = ModeChangeStage.READ_MODE,
                response = readResult.response,
                rawFrame = readResult.rawFrame,
            )
            BleCommandExchangeResult.TimedOut ->
                BleModeChangeResult.TimedOut(ModeChangeStage.READ_MODE)
            is BleCommandExchangeResult.TransportFailure ->
                BleModeChangeResult.TransportFailure(
                    stage = ModeChangeStage.READ_MODE,
                    message = readResult.message,
                )
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
