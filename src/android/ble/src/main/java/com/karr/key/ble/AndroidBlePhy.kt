package com.karr.key.ble

import android.bluetooth.BluetoothDevice

/**
 * Maps Android's platform PHY constants onto the shared [BlePhy] model defined
 * in shared/transport. This is the only piece of the former BleModels.kt that
 * touches the Android SDK; everything else moved to the platform-free module so
 * the desktop host reuses it.
 */
internal fun blePhyFromPlatformValue(value: Int): BlePhy = when (value) {
    BluetoothDevice.PHY_LE_1M -> BlePhy.LE_1M
    BluetoothDevice.PHY_LE_2M -> BlePhy.LE_2M
    BluetoothDevice.PHY_LE_CODED -> BlePhy.LE_CODED
    else -> BlePhy.UNKNOWN
}
