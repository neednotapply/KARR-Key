package com.karr.key.ble

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
    /**
     * A patched KARR unit: it accepted the Bluetooth/UART link but never issued the legacy
     * `REQUEST_AUTH` challenge the recovered keys answer — it either stayed silent or replied with a
     * non-legacy frame the legacy codec cannot parse — then dropped the link. Unlike a vulnerable
     * unit it never reaches [Ready], so this terminal state is how the app learns the unit is not
     * exposed to the legacy technique. [evidence] describes which of the two patched behaviours was
     * observed.
     */
    data class PatchedUnitDetected(
        val address: String,
        val name: String?,
        val evidence: String,
    ) : BleSessionState
    data class Error(val message: String) : BleSessionState
}

fun BleSessionState.withReadModeObservation(packet: QtPacket): BleSessionState {
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

fun BleSessionState.withLinkDiagnostics(
    diagnostics: BleLinkDiagnostics,
): BleSessionState = if (this is BleSessionState.Ready) {
    copy(linkDiagnostics = diagnostics)
} else {
    this
}

/**
 * Whether a just-closed connection matches the patched-unit signature: the unit accepted the UART
 * link ([uartSubscribed]) but the connection ended before it ever issued the legacy `REQUEST_AUTH`
 * challenge ([sawLegacyAuthChallenge] is false) — the behaviour a patched KARR unit shows, whether
 * it stayed silent or replied with a non-legacy frame. Excludes automatic-reconnect drops
 * ([isReconnect]) and post-authentication drops ([previousState] already [BleSessionState.Ready]),
 * neither of which says anything about the legacy challenge. A user-initiated disconnect never
 * reaches this check because it nulls the active GATT first.
 */
fun isPatchedUnitDisconnect(
    uartSubscribed: Boolean,
    sawLegacyAuthChallenge: Boolean,
    isReconnect: Boolean,
    previousState: BleSessionState,
): Boolean =
    uartSubscribed &&
        !sawLegacyAuthChallenge &&
        !isReconnect &&
        previousState !is BleSessionState.Ready

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
