package com.karr.key

import android.app.Application
import android.content.pm.ApplicationInfo
import com.karr.key.ble.AndroidBleController
import com.karr.key.ble.BleController
import com.karr.key.core.KarrTrace
import com.karr.key.data.AutoConnectReadPreferences
import com.karr.key.data.EncryptedDeviceKeyStore
import com.karr.key.data.EncryptedLocalValueStore
import com.karr.key.protocol.LegacyDeviceAuthenticator

class KarrApplication : Application() {
    lateinit var deviceKeys: EncryptedDeviceKeyStore
        private set

    lateinit var bleController: BleController
        private set

    lateinit var autoConnectReadPreferences: AutoConnectReadPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        // Debuggable builds only: the live trace never runs in a release APK.
        KarrTrace.enabled =
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        deviceKeys = EncryptedDeviceKeyStore(
            EncryptedLocalValueStore(this),
        )
        autoConnectReadPreferences = AutoConnectReadPreferences(this)
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
