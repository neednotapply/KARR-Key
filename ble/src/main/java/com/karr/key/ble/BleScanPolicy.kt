package com.karr.key.ble

import android.bluetooth.le.ScanSettings

internal enum class BleScanPurpose {
    MANUAL,
    RECONNECT,
}

internal data class BleScanConfiguration(
    val scanMode: Int,
    val callbackType: Int,
    val reportDelayMillis: Long,
)

internal object BleScanPolicy {
    private val batterySaving = BleScanConfiguration(
        scanMode = ScanSettings.SCAN_MODE_LOW_POWER,
        callbackType = ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
        reportDelayMillis = 0L,
    )

    fun configurationFor(purpose: BleScanPurpose): BleScanConfiguration = when (purpose) {
        BleScanPurpose.MANUAL,
        BleScanPurpose.RECONNECT,
        -> batterySaving
    }
}
