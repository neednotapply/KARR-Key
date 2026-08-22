package com.karr.key.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BleScanPolicyTest {
    @Test
    fun manualAndReconnectScansUseTheSameBatterySavingProfile() {
        val manual = BleScanPolicy.configurationFor(BleScanPurpose.MANUAL)
        val reconnect = BleScanPolicy.configurationFor(BleScanPurpose.RECONNECT)

        assertSame(manual, reconnect)
        assertEquals(ScanSettings.SCAN_MODE_LOW_POWER, manual.scanMode)
        assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, manual.callbackType)
        assertEquals(0L, manual.reportDelayMillis)
    }

    @Test
    fun platformPhyValuesMapWithoutClaimingUnknownOrCodedLinks() {
        assertEquals(BlePhy.LE_1M, blePhyFromPlatformValue(BluetoothDevice.PHY_LE_1M))
        assertEquals(BlePhy.LE_2M, blePhyFromPlatformValue(BluetoothDevice.PHY_LE_2M))
        assertEquals(BlePhy.LE_CODED, blePhyFromPlatformValue(BluetoothDevice.PHY_LE_CODED))
        assertEquals(BlePhy.UNKNOWN, blePhyFromPlatformValue(Int.MAX_VALUE))
    }
}
