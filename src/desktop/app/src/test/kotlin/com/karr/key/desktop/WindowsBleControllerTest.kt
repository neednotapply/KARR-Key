package com.karr.key.desktop

import com.karr.key.ble.BleSessionState
import com.karr.key.data.EncryptedDeviceKeyStore
import com.karr.key.desktop.data.FileSecureValueStore
import com.karr.key.desktop.data.FileStringCipher
import com.karr.key.desktop.transport.RawBleEvent
import com.karr.key.desktop.transport.RawBleTransport
import com.karr.key.desktop.transport.WindowsBleController
import com.karr.key.protocol.DeviceKeyPurpose
import com.karr.key.protocol.LegacyDeviceAuthenticator
import com.karr.key.protocol.PacketParseResult
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtProtocolCodec
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the WindowsBleController's KARR session orchestration — classification
 * and the legacy REQUEST_AUTH -> READ_MODE -> PROVIDE_AUTH -> AUTH_SUCCESS
 * handshake — against a scripted fake transport, so the logic is validated without
 * a radio or the native helper. (Real-hardware validation of the WinRT helper
 * itself remains separate.)
 */
class WindowsBleControllerTest {
    private class FakeRawTransport : RawBleTransport {
        private val _events = MutableSharedFlow<RawBleEvent>(replay = 64, extraBufferCapacity = 64)
        override val events: SharedFlow<RawBleEvent> = _events
        val written = mutableListOf<ByteArray>()
        fun push(event: RawBleEvent) { _events.tryEmit(event) }
        override fun startScan() = Result.success(Unit)
        override fun stopScan() {}
        override fun connect(address: String) = Result.success(Unit)
        override fun disconnect() {}
        override suspend fun write(bytes: ByteArray): Result<Unit> {
            synchronized(written) { written.add(bytes.copyOf()) }
            return Result.success(Unit)
        }
        override fun shutdown() {}
        fun lastCommand(command: QtCommand): Boolean = synchronized(written) {
            written.any { (QtProtocolCodec.parse(it) as? PacketParseResult.Success)?.packet?.command == command }
        }
    }

    @Test
    fun handshakeReachesReady() {
        val tmp = Files.createTempDirectory("winble-test")
        val cipher = FileStringCipher(tmp.resolve("secret.key"))
        val secure = FileSecureValueStore(cipher, tmp.resolve("sv.json"))
        val keys = EncryptedDeviceKeyStore(secure)
        keys.provision(DeviceKeyPurpose.CUSTOMER, ByteArray(16) { it.toByte() })
        val authenticator = LegacyDeviceAuthenticator(keys)

        val transport = FakeRawTransport()
        val controller = WindowsBleController(authenticator, transport)
        try {
            controller.startScan()
            transport.push(
                RawBleEvent.ScanResult(
                    address = "AA:BB:CC:DD:EE:01",
                    name = "DR 1A2B3C4",
                    rssi = -55,
                    connectable = true,
                ),
            )
            runBlocking {
                withTimeout(2_000) { while (controller.devices.value.isEmpty()) delay(10) }
                assertTrue(controller.devices.value.first().classification.isCandidate)

                controller.connect("AA:BB:CC:DD:EE:01")
                transport.push(RawBleEvent.Connected("AA:BB:CC:DD:EE:01"))
                transport.push(RawBleEvent.UartReady)
                withTimeout(2_000) {
                    while (controller.state.value !is BleSessionState.UartSubscribed) delay(10)
                }

                // Unit issues the 8-byte legacy challenge.
                transport.push(
                    RawBleEvent.Notification(
                        QtProtocolCodec.generateOriginal(QtCommand.REQUEST_AUTH, ByteArray(8)),
                    ),
                )
                withTimeout(2_000) { while (!transport.lastCommand(QtCommand.READ_MODE)) delay(10) }

                // Unit answers READ_MODE with mode=USER(2), type=DR1_CAN(4).
                transport.push(
                    RawBleEvent.Notification(
                        QtProtocolCodec.generateOriginal(QtCommand.READ_MODE, byteArrayOf(2, 4)),
                    ),
                )
                withTimeout(2_000) { while (!transport.lastCommand(QtCommand.PROVIDE_AUTH)) delay(10) }

                // Unit accepts the challenge response.
                transport.push(
                    RawBleEvent.Notification(QtProtocolCodec.generateOriginal(QtCommand.AUTH_SUCCESS)),
                )
                withTimeout(2_000) { while (controller.state.value !is BleSessionState.Ready) delay(10) }
            }

            val ready = controller.state.value as BleSessionState.Ready
            assertEquals("DR1 CAN", ready.unitModel?.displayName)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun patchedUnitDetectedWhenNoLegacyChallenge() {
        val tmp = Files.createTempDirectory("winble-test2")
        val cipher = FileStringCipher(tmp.resolve("secret.key"))
        val keys = EncryptedDeviceKeyStore(FileSecureValueStore(cipher, tmp.resolve("sv.json")))
        val transport = FakeRawTransport()
        val controller = WindowsBleController(LegacyDeviceAuthenticator(keys), transport)
        try {
            controller.connect("AA:BB:CC:DD:EE:02")
            transport.push(RawBleEvent.Connected("AA:BB:CC:DD:EE:02"))
            transport.push(RawBleEvent.UartReady)
            runBlocking {
                withTimeout(2_000) {
                    while (controller.state.value !is BleSessionState.UartSubscribed) delay(10)
                }
                // Link drops before any legacy challenge -> patched signature.
                transport.push(RawBleEvent.Disconnected(status = 19))
                withTimeout(2_000) {
                    while (controller.state.value !is BleSessionState.PatchedUnitDetected) delay(10)
                }
            }
            assertTrue(controller.state.value is BleSessionState.PatchedUnitDetected)
        } finally {
            controller.shutdown()
        }
    }
}
