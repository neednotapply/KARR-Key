package com.karr.key.ui

import com.karr.key.ble.BleCommandExchange
import com.karr.key.ble.BleController
import com.karr.key.ble.BleScanObservation
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.DiscoveredDevice
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.PacketParseResult
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtProtocolCodec
import com.karr.key.protocol.QtSecurityState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityStateReconciliationTest {
    @Test
    fun confirmsRequestedStateFromFreshReadModeResponse() = runTest {
        val controller = ReconciliationBleController { notifications ->
            notifications.emit(readModeResponse(armed = true))
            Result.success(Unit)
        }

        val result = BleCommandExchange(controller).reconcileSecurityState(
            expected = QtSecurityState.ARMED,
            timeoutMillis = 100,
        )

        assertEquals(
            SecurityStateReconciliation.Confirmed(QtSecurityState.ARMED),
            result,
        )
        assertEquals(1, controller.sentPackets.size)
        val request = QtProtocolCodec.parse(controller.sentPackets.single())
        assertTrue(request is PacketParseResult.Success)
        assertEquals(
            QtCommand.READ_MODE,
            (request as PacketParseResult.Success).packet.command,
        )
    }

    @Test
    fun preservesDifferentFreshStateInsteadOfClaimingSuccess() = runTest {
        val controller = ReconciliationBleController { notifications ->
            notifications.emit(readModeResponse(armed = false))
            Result.success(Unit)
        }

        val result = BleCommandExchange(controller).reconcileSecurityState(
            expected = QtSecurityState.ARMED,
            timeoutMillis = 100,
        )

        assertEquals(
            SecurityStateReconciliation.Different(QtSecurityState.DISARMED),
            result,
        )
    }

    @Test
    fun leavesTimeoutUnconfirmedWhenFreshStateCannotBeRead() = runTest {
        val controller = ReconciliationBleController { _ -> Result.success(Unit) }

        val result = BleCommandExchange(controller).reconcileSecurityState(
            expected = QtSecurityState.DISARMED,
            timeoutMillis = 1,
        )

        assertEquals(SecurityStateReconciliation.Unavailable, result)
    }

    private fun readModeResponse(armed: Boolean): ByteArray {
        val status = KarrDeviceMode.USER.code or if (armed) 0x08 else 0x00
        return QtProtocolCodec.generateOriginal(
            QtCommand.READ_MODE,
            byteArrayOf(status.toByte(), 0x04, 0x2A),
        )
    }
}

private class ReconciliationBleController(
    private val sendBehavior: suspend (MutableSharedFlow<ByteArray>) -> Result<Unit>,
) : BleController {
    override val state = MutableStateFlow<BleSessionState>(
        BleSessionState.Ready(address = "00:11:22:33:44:55", name = "QT000001"),
    )
    override val devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val scanObservations = MutableSharedFlow<BleScanObservation>()
    // Mirror AndroidBleController's real notifications flow: buffered (not a rendezvous), so a
    // synchronous emit from send() is retained for the already-subscribed collector instead of
    // depending on a wall-clock rendezvous. Combined with runTest's virtual time, the exchange
    // resolves deterministically regardless of host load.
    override val notifications = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 32,
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
        return sendBehavior(notifications)
    }

    override fun shutdown() = Unit
}
