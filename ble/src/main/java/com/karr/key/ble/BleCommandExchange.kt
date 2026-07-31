package com.karr.key.ble

import com.karr.key.protocol.PacketParseResult
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtPacket
import com.karr.key.protocol.QtProtocolCodec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

sealed interface BleCommandExchangeResult {
    data class Acknowledged(
        val response: QtPacket,
        val rawFrame: ByteArray,
    ) : BleCommandExchangeResult

    data class Rejected(
        val response: QtPacket,
        val rawFrame: ByteArray,
    ) : BleCommandExchangeResult

    data object TimedOut : BleCommandExchangeResult
    data class TransportFailure(val message: String) : BleCommandExchangeResult
}

class BleCommandExchange(
    private val controller: BleController,
) {
    suspend fun sendAndAwait(
        packet: ByteArray,
        expectedCommand: QtCommand,
        acceptGenericAck: Boolean = true,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onGattAccepted: suspend () -> Unit = {},
    ): BleCommandExchangeResult = coroutineScope {
        require(timeoutMillis > 0) { "Response timeout must be positive." }
        val acceptedCodes = buildSet {
            add(expectedCommand.code)
            add(QtCommand.ACK_ERROR.code)
            if (acceptGenericAck) {
                add(QtCommand.ACK_OK.code)
            }
        }
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeoutMillis) {
                controller.notifications
                    .mapNotNull { bytes ->
                        (QtProtocolCodec.parse(bytes) as? PacketParseResult.Success)
                            ?.let { parsed ->
                                ReceivedResponse(
                                    packet = parsed.packet,
                                    rawFrame = bytes.copyOf(),
                                )
                            }
                    }
                    .first { incoming -> incoming.packet.commandCode in acceptedCodes }
            }
        }

        val sendResult = runCatching {
            controller.send(packet).getOrThrow()
        }
        if (sendResult.isFailure) {
            response.cancel()
            return@coroutineScope BleCommandExchangeResult.TransportFailure(
                sendResult.exceptionOrNull()?.message ?: "The BLE write failed.",
            )
        }

        onGattAccepted()
        val incoming = response.await()
            ?: return@coroutineScope BleCommandExchangeResult.TimedOut
        if (incoming.packet.command == QtCommand.ACK_ERROR) {
            BleCommandExchangeResult.Rejected(
                response = incoming.packet,
                rawFrame = incoming.rawFrame,
            )
        } else {
            BleCommandExchangeResult.Acknowledged(
                response = incoming.packet,
                rawFrame = incoming.rawFrame,
            )
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }

    private data class ReceivedResponse(
        val packet: QtPacket,
        val rawFrame: ByteArray,
    )
}
