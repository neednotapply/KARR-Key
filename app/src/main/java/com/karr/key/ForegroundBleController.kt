package com.karr.key

import android.content.Context
import com.karr.key.ble.BleController
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.DiscoveredDevice
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the Android BLE implementation independent of UI/service concerns while
 * ensuring user-started scans and connections have a foreground-service host.
 */
internal class ForegroundBleController(
    context: Context,
    private val delegate: BleController,
) : BleController {
    private val appContext = context.applicationContext

    override val state: StateFlow<BleSessionState> = delegate.state
    override val devices: StateFlow<List<DiscoveredDevice>> = delegate.devices
    override val scanObservations = delegate.scanObservations
    override val notifications: SharedFlow<ByteArray> = delegate.notifications

    override fun startScan(): Result<Unit> {
        delegate.setReconnectAllowed(true)
        return keepAlive(
            result = delegate.startScan(),
            rollback = delegate::stopScan,
        )
    }

    override fun stopScan() = delegate.stopScan()

    override fun connect(address: String): Result<Unit> {
        delegate.setReconnectAllowed(true)
        return keepAlive(
            result = delegate.connect(address),
            rollback = delegate::disconnect,
        )
    }

    override fun disconnect() = delegate.disconnect()

    override fun setReconnectAllowed(allowed: Boolean) {
        delegate.setReconnectAllowed(allowed)
    }

    override fun retryReconnect(): Result<Unit> {
        delegate.setReconnectAllowed(true)
        return keepAlive(
            result = delegate.retryReconnect(),
            rollback = delegate::disconnect,
        )
    }

    override suspend fun send(packet: ByteArray): Result<Unit> = delegate.send(packet)

    override fun shutdown() = delegate.shutdown()

    private fun keepAlive(
        result: Result<Unit>,
        rollback: () -> Unit,
    ): Result<Unit> {
        if (result.isFailure) {
            return result
        }
        return BluetoothSessionService.keepAlive(appContext)
            .onFailure { rollback() }
    }
}
