package com.karr.key.ble

import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.PacketParseResult
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtProtocolCodec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleCommandExchangeTest {
    @Test
    fun subscribesBeforeWriteAndAcceptsMatchingResponse() = runTest {
        // Rendezvous (no buffer) on purpose: if sendAndAwait wrote before subscribing, this
        // synchronous emit would be dropped and the exchange would time out. The buffered default
        // used by the other tests would mask that ordering regression, so this one opts into
        // rendezvous to keep proving the subscribe-before-write contract.
        val controller = FakeBleController(notificationBufferCapacity = 0) { _, notifications ->
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
    fun readStateIgnoresUnrelatedNotificationsAndRetainsMatchingRawFrame() = runTest {
        val matchingFrame = QtProtocolCodec.generateOriginal(
            QtCommand.READ_STATE,
            byteArrayOf(0x00),
        )
        val controller = FakeBleController { _, notifications ->
            notifications.emit(QtProtocolCodec.generateOriginal(QtCommand.ACK_OK))
            notifications.emit(
                QtProtocolCodec.generateOriginal(
                    QtCommand.PIC2_READY,
                    byteArrayOf(0x01),
                ),
            )
            notifications.emit(matchingFrame)
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
        assertArrayEquals(matchingFrame, result.rawFrame)
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

    @Test
    fun modeChangeIsConfirmedByASeparateReadModeResponse() = runTest {
        val controller = FakeBleController { packet, notifications ->
            when (packet.command()) {
                QtCommand.CHANGE_MODE ->
                    notifications.emit(QtProtocolCodec.generateOriginal(QtCommand.ACK_OK))
                QtCommand.READ_MODE -> notifications.emit(
                    QtProtocolCodec.generateOriginal(
                        QtCommand.READ_MODE,
                        byteArrayOf(KarrDeviceMode.DEALER.code.toByte()),
                    ),
                )
                else -> error("Unexpected packet")
            }
            Result.success(Unit)
        }

        val result = BleModeChangeExchange(controller).changeAndConfirm(
            changePacket = QtProtocolCodec.generateOriginal(
                QtCommand.CHANGE_MODE,
                byteArrayOf(KarrDeviceMode.DEALER.code.toByte()),
            ),
            targetMode = KarrDeviceMode.DEALER,
            timeoutMillis = 100,
        )

        assertTrue(result is BleModeChangeResult.Confirmed)
        assertEquals(
            listOf(QtCommand.CHANGE_MODE, QtCommand.READ_MODE),
            controller.sentPackets.map(ByteArray::command),
        )
    }

    @Test
    fun modeChangeReportsTheModeActuallyReturnedByTheUnit() = runTest {
        val controller = FakeBleController { packet, notifications ->
            if (packet.command() == QtCommand.CHANGE_MODE) {
                notifications.emit(QtProtocolCodec.generateOriginal(QtCommand.ACK_OK))
            } else {
                notifications.emit(
                    QtProtocolCodec.generateOriginal(
                        QtCommand.READ_MODE,
                        byteArrayOf(KarrDeviceMode.USER.code.toByte()),
                    ),
                )
            }
            Result.success(Unit)
        }

        val result = BleModeChangeExchange(controller).changeAndConfirm(
            changePacket = QtProtocolCodec.generateOriginal(
                QtCommand.CHANGE_MODE,
                byteArrayOf(KarrDeviceMode.DEALER.code.toByte()),
            ),
            targetMode = KarrDeviceMode.DEALER,
            timeoutMillis = 100,
        )

        assertTrue(result is BleModeChangeResult.ObservedDifferent)
        assertEquals(
            KarrDeviceMode.USER,
            (result as BleModeChangeResult.ObservedDifferent).observedMode,
        )
    }

    @Test
    fun rejectedModeChangeDoesNotIssueReadOrRetry() = runTest {
        val controller = FakeBleController { _, notifications ->
            notifications.emit(
                QtProtocolCodec.generateOriginal(QtCommand.ACK_ERROR, byteArrayOf(1)),
            )
            Result.success(Unit)
        }

        val result = BleModeChangeExchange(controller).changeAndConfirm(
            changePacket = QtProtocolCodec.generateOriginal(
                QtCommand.CHANGE_MODE,
                byteArrayOf(KarrDeviceMode.DEALER.code.toByte()),
            ),
            targetMode = KarrDeviceMode.DEALER,
            timeoutMillis = 100,
        )

        assertTrue(result is BleModeChangeResult.Rejected)
        assertEquals(
            ModeChangeStage.CHANGE_MODE,
            (result as BleModeChangeResult.Rejected).stage,
        )
        assertEquals(1, controller.sentPackets.size)
    }

    @Test
    fun readModeTimeoutNeverResendsMutatingModeChange() = runTest {
        val controller = FakeBleController { packet, notifications ->
            if (packet.command() == QtCommand.CHANGE_MODE) {
                notifications.emit(QtProtocolCodec.generateOriginal(QtCommand.ACK_OK))
            }
            Result.success(Unit)
        }

        val result = BleModeChangeExchange(controller).changeAndConfirm(
            changePacket = QtProtocolCodec.generateOriginal(
                QtCommand.CHANGE_MODE,
                byteArrayOf(KarrDeviceMode.DEALER.code.toByte()),
            ),
            targetMode = KarrDeviceMode.DEALER,
            timeoutMillis = 1,
        )

        assertEquals(
            BleModeChangeResult.TimedOut(ModeChangeStage.READ_MODE),
            result,
        )
        assertEquals(
            listOf(QtCommand.CHANGE_MODE, QtCommand.READ_MODE),
            controller.sentPackets.map(ByteArray::command),
        )
    }
}

private fun ByteArray.command(): QtCommand? =
    (QtProtocolCodec.parse(this) as PacketParseResult.Success).packet.command

private class FakeBleController(
    // Default mirrors AndroidBleController's real notifications flow (extraBufferCapacity = 32,
    // non-blocking tryEmit), so a synchronous emit from send() is retained for the collector rather
    // than depending on a rendezvous handoff. A test that must prove send-vs-subscribe ordering
    // overrides this to 0 (rendezvous), where a value emitted before the collector subscribes is
    // dropped instead of buffered.
    private val notificationBufferCapacity: Int = 32,
    private val sendBehavior: suspend (
        packet: ByteArray,
        notifications: MutableSharedFlow<ByteArray>,
    ) -> Result<Unit>,
) : BleController {
    override val state = MutableStateFlow<BleSessionState>(
        BleSessionState.Ready(address = "00:11:22:33:44:55", name = "QT000001"),
    )
    override val devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val scanObservations = MutableSharedFlow<BleScanObservation>()
    override val notifications = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = notificationBufferCapacity,
    )
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
