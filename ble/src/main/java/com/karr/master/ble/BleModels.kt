package com.karr.master.ble

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
    val connectionEligible: Boolean
        get() = connectable &&
            classification.tier == KarrCandidateTier.CONFIRMED_FORMAT
}

sealed interface BleSessionState {
    data object Idle : BleSessionState
    data object Scanning : BleSessionState
    data class Connecting(val address: String) : BleSessionState
    data class DiscoveringUart(val address: String) : BleSessionState
    data class UartSubscribed(val address: String, val name: String?) : BleSessionState
    data class AuthenticationRequired(
        val address: String,
        val name: String?,
        val challengeBytes: Int,
    ) : BleSessionState
    data class Ready(val address: String, val name: String?) : BleSessionState
    data class Error(val message: String) : BleSessionState
}

interface BleController {
    val state: StateFlow<BleSessionState>
    val devices: StateFlow<List<DiscoveredDevice>>
    val notifications: SharedFlow<ByteArray>

    fun startScan(): Result<Unit>
    fun stopScan()
    fun connect(address: String): Result<Unit>
    fun disconnect()
    suspend fun send(packet: ByteArray): Result<Unit>
    fun shutdown()
}

object KarrGatt {
    val UART_SERVICE: UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
    val UART_CHARACTERISTIC: UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")
    val CLIENT_CONFIGURATION: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
