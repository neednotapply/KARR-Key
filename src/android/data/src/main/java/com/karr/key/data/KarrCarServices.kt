package com.karr.key.data

import com.karr.key.ble.BleController
import com.karr.key.protocol.DeviceKeyProvisioning

/**
 * The process-wide singletons the Android Auto surface needs from [android.app.Application],
 * without depending on the `android:app` module (which itself depends on `android:auto` to merge
 * its car-service manifest entry — a direct dependency the other way would cycle). Implemented by
 * `KarrApplication`; the car module reaches it via `applicationContext as KarrCarServices`, the
 * same cast `BluetoothSessionService` already uses to reach `KarrApplication` directly.
 */
interface KarrCarServices {
    val bleController: BleController
    val deviceKeys: DeviceKeyProvisioning
}
