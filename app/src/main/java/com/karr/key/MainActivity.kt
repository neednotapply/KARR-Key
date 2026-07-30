package com.karr.key

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.karr.key.ble.AndroidBleController
import com.karr.key.ble.BlePermissions
import com.karr.key.data.EncryptedDeviceKeyStore
import com.karr.key.data.EncryptedLocalValueStore
import com.karr.key.data.LocalKeyRepository
import com.karr.key.data.LocalOperationStore
import com.karr.key.protocol.LegacyDeviceAuthenticator
import com.karr.key.ui.KarrKeyApp
import com.karr.key.ui.KarrKeyTheme

class MainActivity : ComponentActivity() {
    private lateinit var bleController: AndroidBleController
    private lateinit var operationStore: LocalOperationStore
    private var permissionResult: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.isNotEmpty() && results.values.all { it }
        permissionResult?.invoke(granted)
        permissionResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deviceKeys = EncryptedDeviceKeyStore(
            EncryptedLocalValueStore(this),
        )
        bleController = AndroidBleController(
            context = this,
            authenticator = LegacyDeviceAuthenticator(deviceKeys),
        )
        val repository = LocalKeyRepository()
        operationStore = LocalOperationStore(this)

        setContent {
            KarrKeyTheme {
                KarrKeyApp(
                    repository = repository,
                    bleController = bleController,
                    deviceKeys = deviceKeys,
                    operationStore = operationStore,
                    requestBlePermissions = ::requestBlePermissions,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bleController.setReconnectAllowed(true)
    }

    override fun onStop() {
        bleController.setReconnectAllowed(false)
        super.onStop()
    }

    override fun onDestroy() {
        bleController.shutdown()
        operationStore.close()
        super.onDestroy()
    }

    private fun requestBlePermissions(onResult: (Boolean) -> Unit) {
        val permissions = BlePermissions.scanAndConnect()
        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            onResult(true)
            return
        }
        permissionResult = onResult
        permissionLauncher.launch(permissions)
    }
}
