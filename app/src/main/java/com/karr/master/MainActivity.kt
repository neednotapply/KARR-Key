package com.karr.master

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.karr.master.ble.AndroidBleController
import com.karr.master.ble.BlePermissions
import com.karr.master.data.LocalMasterRepository
import com.karr.master.ui.KarrMasterApp
import com.karr.master.ui.KarrMasterTheme

class MainActivity : ComponentActivity() {
    private lateinit var bleController: AndroidBleController
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
        bleController = AndroidBleController(this)
        val repository = LocalMasterRepository()

        setContent {
            KarrMasterTheme {
                KarrMasterApp(
                    repository = repository,
                    bleController = bleController,
                    requestBlePermissions = ::requestBlePermissions,
                )
            }
        }
    }

    override fun onDestroy() {
        bleController.shutdown()
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
