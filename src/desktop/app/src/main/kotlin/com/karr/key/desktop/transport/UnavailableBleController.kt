package com.karr.key.desktop.transport

import com.karr.key.ble.BleController
import com.karr.key.ble.BleScanObservation
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.DiscoveredDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [BleController] used when no real desktop Bluetooth transport is available —
 * the Windows helper is not built/installed and the mock was not requested. It
 * scans nothing (so no phantom unit ever appears) and every action fails with a
 * message pointing at how to enable a real or simulated transport.
 */
class UnavailableBleController : BleController {
    private val _state = MutableStateFlow<BleSessionState>(BleSessionState.Idle)
    override val state: StateFlow<BleSessionState> = _state.asStateFlow()
    override val devices: StateFlow<List<DiscoveredDevice>> = MutableStateFlow(emptyList())
    override val scanObservations: SharedFlow<BleScanObservation> =
        MutableSharedFlow<BleScanObservation>().asSharedFlow()
    override val notifications: SharedFlow<ByteArray> =
        MutableSharedFlow<ByteArray>().asSharedFlow()

    override fun startScan(): Result<Unit> = failure()
    override fun stopScan() {}
    override fun connect(address: String): Result<Unit> = failure()
    override fun disconnect() {}
    override fun setReconnectAllowed(allowed: Boolean) {}
    override fun retryReconnect(): Result<Unit> = failure()
    override suspend fun send(packet: ByteArray): Result<Unit> = failure()
    override fun shutdown() {}

    private fun failure(): Result<Unit> = Result.failure(IllegalStateException(MESSAGE))

    private companion object {
        const val MESSAGE =
            "No desktop Bluetooth transport is available. Build the Windows BLE helper " +
                "(see desktop/blebridge) and set KARR_BLE=windows, or set KARR_BLE=mock to use " +
                "the simulated unit."
    }
}
