package com.karr.key

import android.app.Application
import com.karr.key.ble.AndroidBleController
import com.karr.key.ble.BleController
import com.karr.key.data.EncryptedDeviceKeyStore
import com.karr.key.data.EncryptedLocalValueStore
import com.karr.key.protocol.LegacyDeviceAuthenticator

class KarrApplication : Application() {
    lateinit var deviceKeys: EncryptedDeviceKeyStore
        private set

    lateinit var bleController: BleController
        private set

    override fun onCreate() {
        super.onCreate()
        deviceKeys = EncryptedDeviceKeyStore(
            EncryptedLocalValueStore(this),
        )
        val androidController = AndroidBleController(
            context = this,
            authenticator = LegacyDeviceAuthenticator(deviceKeys),
        )
        androidController.setReconnectAllowed(true)
        bleController = ForegroundBleController(
            context = this,
            delegate = androidController,
        )
    }
}
