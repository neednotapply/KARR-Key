package com.karr.master

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.karr.master.ble.AndroidBleController
import com.karr.master.ble.BlePermissions
import com.karr.master.data.EncryptedDeviceKeyStore
import com.karr.master.data.EncryptedLocalValueStore
import com.karr.master.data.LocalMasterRepository
import com.karr.master.data.LocalOperationStore
import com.karr.master.protocol.LegacyDeviceAuthenticator
import com.karr.master.ui.KarrMasterApp
import com.karr.master.ui.KarrMasterTheme

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
        val repository = LocalMasterRepository()
        operationStore = LocalOperationStore(this)

        setContent {
            KarrMasterTheme {
                KarrMasterApp(
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
