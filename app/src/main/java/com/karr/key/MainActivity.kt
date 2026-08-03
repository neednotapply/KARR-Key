package com.karr.key

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.karr.key.ble.BlePermissions
import com.karr.key.data.LocalKeyRepository
import com.karr.key.data.LocalMapHistoryStore
import com.karr.key.data.LocalOperationStore
import com.karr.key.ui.KarrKeyApp
import com.karr.key.ui.KarrKeyTheme

class MainActivity : ComponentActivity() {
    private lateinit var operationStore: LocalOperationStore
    private lateinit var mapHistoryStore: LocalMapHistoryStore
    private var permissionResult: ((Boolean) -> Unit)? = null
    private var locationPermissionResult: ((Boolean) -> Unit)? = null

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.isNotEmpty() && results.values.all { it }
        if (granted) {
            requestNotificationPermissionIfNeeded()
        } else {
            completePermissionRequest(false)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Notification denial does not remove Bluetooth functionality. Android
        // still surfaces an active foreground service in the system task UI.
        completePermissionRequest(true)
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        locationPermissionResult?.invoke(
            results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
        )
        locationPermissionResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as KarrApplication
        val repository = LocalKeyRepository()
        operationStore = LocalOperationStore(this)
        mapHistoryStore = LocalMapHistoryStore(this)

        setContent {
            KarrKeyTheme {
                KarrKeyApp(
                    repository = repository,
                    bleController = app.bleController,
                    deviceKeys = app.deviceKeys,
                    operationStore = operationStore,
                    mapHistoryStore = mapHistoryStore,
                    requestBlePermissions = ::requestBlePermissions,
                    requestMapLocationPermissions = ::requestMapLocationPermissions,
                )
            }
        }
    }

    override fun onDestroy() {
        operationStore.close()
        mapHistoryStore.close()
        super.onDestroy()
    }

    private fun requestBlePermissions(onResult: (Boolean) -> Unit) {
        val permissions = BlePermissions.scanAndConnect()
        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            permissionResult = onResult
            requestNotificationPermissionIfNeeded()
            return
        }
        permissionResult = onResult
        blePermissionLauncher.launch(permissions)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            completePermissionRequest(true)
        }
    }

    private fun completePermissionRequest(granted: Boolean) {
        permissionResult?.invoke(granted)
        permissionResult = null
    }

    private fun requestMapLocationPermissions(onResult: (Boolean) -> Unit) {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (permissions.any {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            onResult(true)
            return
        }
        locationPermissionResult = onResult
        locationPermissionLauncher.launch(permissions)
    }
}
