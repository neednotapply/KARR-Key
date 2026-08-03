package com.karr.key.ble

import android.bluetooth.BluetoothDevice
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtPacket
import java.util.UUID
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val connectable: Boolean,
    val classification: KarrDeviceClassification,
) {
    val manualProbeEligible: Boolean
        get() = connectable && classification.isCandidate

    val confirmedConnectionEligible: Boolean
        get() = connectable &&
            classification.tier == KarrCandidateTier.CONFIRMED_FORMAT
}

data class BleScanObservation(
    val device: DiscoveredDevice,
    val observedUtcMillis: Long,
    val observedElapsedRealtimeMillis: Long,
)

enum class BlePhy(val displayName: String) {
    LE_1M("LE 1M"),
    LE_2M("LE 2M"),
    LE_CODED("LE Coded"),
    UNKNOWN("Not reported"),
}

data class BleLinkDiagnostics(
    val transmitPhy: BlePhy = BlePhy.UNKNOWN,
    val receivePhy: BlePhy = BlePhy.UNKNOWN,
)

internal fun blePhyFromPlatformValue(value: Int): BlePhy = when (value) {
    BluetoothDevice.PHY_LE_1M -> BlePhy.LE_1M
    BluetoothDevice.PHY_LE_2M -> BlePhy.LE_2M
    BluetoothDevice.PHY_LE_CODED -> BlePhy.LE_CODED
    else -> BlePhy.UNKNOWN
}

sealed interface BleSessionState {
    data object Idle : BleSessionState
    data object Scanning : BleSessionState
    data class Reconnecting(
        val serial: String,
        val attempt: Int,
        val maxAttempts: Int,
        val step: String,
    ) : BleSessionState
    data class ReconnectFailed(
        val serial: String,
        val attempts: Int,
        val message: String,
    ) : BleSessionState
    data class Connecting(val address: String) : BleSessionState
    data class DiscoveringUart(val address: String) : BleSessionState
    data class UartSubscribed(val address: String, val name: String?) : BleSessionState
    data class AuthenticationRequired(
        val address: String,
        val name: String?,
        val challengeBytes: Int,
    ) : BleSessionState
    data class Authenticating(
        val address: String,
        val name: String?,
        val step: String,
    ) : BleSessionState
    data class Ready(
        val address: String,
        val name: String?,
        val unitModel: KarrUnitModel? = null,
        val unitMode: KarrDeviceMode? = null,
        val linkDiagnostics: BleLinkDiagnostics = BleLinkDiagnostics(),
    ) : BleSessionState
    data class Error(val message: String) : BleSessionState
}

internal fun BleSessionState.withReadModeObservation(packet: QtPacket): BleSessionState {
    if (this !is BleSessionState.Ready ||
        packet.command != QtCommand.READ_MODE ||
        packet.payload.isEmpty()
    ) {
        return this
    }

    return copy(
        unitMode = KarrDeviceMode.fromStatusByte(packet.payload[0]),
        unitModel = packet.payload.getOrNull(1)?.let(KarrUnitModel::fromTypeByte) ?: unitModel,
    )
}

internal fun BleSessionState.withLinkDiagnostics(
    diagnostics: BleLinkDiagnostics,
): BleSessionState = if (this is BleSessionState.Ready) {
    copy(linkDiagnostics = diagnostics)
} else {
    this
}

interface BleController {
    val state: StateFlow<BleSessionState>
    val devices: StateFlow<List<DiscoveredDevice>>
    val scanObservations: SharedFlow<BleScanObservation>
    val notifications: SharedFlow<ByteArray>

    fun startScan(): Result<Unit>
    fun stopScan()
    fun connect(address: String): Result<Unit>
    fun disconnect()
    fun setReconnectAllowed(allowed: Boolean)
    fun retryReconnect(): Result<Unit>
    suspend fun send(packet: ByteArray): Result<Unit>
    fun shutdown()
}

object KarrGatt {
    val UART_SERVICE: UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
    val UART_CHARACTERISTIC: UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")
    val CLIENT_CONFIGURATION: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
