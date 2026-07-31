package com.karr.key.ble

import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtProtocolCodec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleCommandExchangeTest {
    @Test
    fun subscribesBeforeWriteAndAcceptsMatchingResponse() = runTest {
        val controller = FakeBleController { _, notifications ->
            notifications.emit(
                QtProtocolCodec.generateOriginal(
                    QtCommand.VEHICLE_CONTROL,
                    byteArrayOf(2),
                ),
            )
            Result.success(Unit)
        }
        val exchange = BleCommandExchange(controller)

        val result = exchange.sendAndAwait(
            packet = QtProtocolCodec.generateOriginal(
                QtCommand.VEHICLE_CONTROL,
                byteArrayOf(2),
            ),
            expectedCommand = QtCommand.VEHICLE_CONTROL,
            timeoutMillis = 100,
        )

        assertTrue(result is BleCommandExchangeResult.Acknowledged)
        assertEquals(1, controller.sentPackets.size)
        assertEquals(
            QtCommand.VEHICLE_CONTROL,
            (result as BleCommandExchangeResult.Acknowledged).response.command,
        )
    }

    @Test
    fun readStatePreflightIgnoresGenericAckAndWaitsForState() = runTest {
        val controller = FakeBleController { _, notifications ->
            notifications.emit(QtProtocolCodec.generateOriginal(QtCommand.ACK_OK))
            notifications.emit(
                QtProtocolCodec.generateOriginal(
                    QtCommand.READ_STATE,
                    byteArrayOf(0x00),
                ),
            )
            Result.success(Unit)
        }
        val exchange = BleCommandExchange(controller)

        val result = exchange.sendAndAwait(
            packet = QtProtocolCodec.generateOriginal(QtCommand.READ_STATE),
            expectedCommand = QtCommand.READ_STATE,
            acceptGenericAck = false,
            timeoutMillis = 100,
        )

        assertTrue(result is BleCommandExchangeResult.Acknowledged)
        assertEquals(
            QtCommand.READ_STATE,
            (result as BleCommandExchangeResult.Acknowledged).response.command,
        )
        assertEquals(1, controller.sentPackets.size)
    }

    @Test
    fun timeoutDoesNotResendCommand() = runTest {
        val controller = FakeBleController { _, _ -> Result.success(Unit) }
        val exchange = BleCommandExchange(controller)

        val result = exchange.sendAndAwait(
            packet = QtProtocolCodec.generateOriginal(
                QtCommand.VEHICLE_CONTROL,
                byteArrayOf(2),
            ),
            expectedCommand = QtCommand.VEHICLE_CONTROL,
            timeoutMillis = 1,
        )

        assertEquals(BleCommandExchangeResult.TimedOut, result)
        assertEquals(1, controller.sentPackets.size)
    }

    @Test
    fun transportFailureDoesNotResendCommand() = runTest {
        val controller = FakeBleController { _, _ ->
            Result.failure(IllegalStateException("write failed"))
        }
        val exchange = BleCommandExchange(controller)

        val result = exchange.sendAndAwait(
            packet = QtProtocolCodec.generateOriginal(
                QtCommand.VEHICLE_CONTROL,
                byteArrayOf(2),
            ),
            expectedCommand = QtCommand.VEHICLE_CONTROL,
            timeoutMillis = 100,
        )

        assertTrue(result is BleCommandExchangeResult.TransportFailure)
        assertEquals(1, controller.sentPackets.size)
    }
}

private class FakeBleController(
    private val sendBehavior: suspend (
        packet: ByteArray,
        notifications: MutableSharedFlow<ByteArray>,
    ) -> Result<Unit>,
) : BleController {
    override val state = MutableStateFlow<BleSessionState>(
        BleSessionState.Ready(address = "00:11:22:33:44:55", name = "QT000001"),
    )
    override val devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val notifications = MutableSharedFlow<ByteArray>()
    val sentPackets = mutableListOf<ByteArray>()

    override fun startScan(): Result<Unit> = Result.success(Unit)

    override fun stopScan() = Unit

    override fun connect(address: String): Result<Unit> = Result.success(Unit)

    override fun disconnect() = Unit

    override fun setReconnectAllowed(allowed: Boolean) = Unit

    override fun retryReconnect(): Result<Unit> = Result.success(Unit)

    override suspend fun send(packet: ByteArray): Result<Unit> {
        sentPackets += packet.copyOf()
        return sendBehavior(packet, notifications)
    }

    override fun shutdown() = Unit
}
