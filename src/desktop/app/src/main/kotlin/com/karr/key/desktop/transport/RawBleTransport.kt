package com.karr.key.desktop.transport

import kotlinx.coroutines.flow.SharedFlow

/**
 * A raw BLE transport: scan / connect / UART-subscribe / write / notify, with no
 * knowledge of the KARR protocol or authentication. The Windows-native helper
 * process implements this over WinRT; [WindowsBleController] layers the KARR
 * session state machine (classification, the legacy auth handshake, patched-unit
 * detection) on top, so that orchestration is shared and testable against a fake
 * transport without a radio.
 */
interface RawBleTransport {
    val events: SharedFlow<RawBleEvent>

    fun startScan(): Result<Unit>
    fun stopScan()
    fun connect(address: String): Result<Unit>
    fun disconnect()
    suspend fun write(bytes: ByteArray): Result<Unit>
    fun shutdown()
}

/** Events a [RawBleTransport] emits upward. */
sealed interface RawBleEvent {
    data class ScanResult(
        val address: String,
        val name: String?,
        val rssi: Int,
        val connectable: Boolean,
        val serviceUuids: List<String> = emptyList(),
        val manufacturerData: List<ByteArray> = emptyList(),
    ) : RawBleEvent

    /** GATT connected; UART service/characteristic discovery not yet complete. */
    data class Connected(val address: String) : RawBleEvent

    /** UART characteristic discovered and notifications subscribed — ready for the KARR handshake. */
    data object UartReady : RawBleEvent

    data class Notification(val data: ByteArray) : RawBleEvent

    data class Disconnected(val status: Int) : RawBleEvent

    data class Failure(val message: String) : RawBleEvent
}
