package com.karr.key

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.karr.key.ble.BlePermissions
import com.karr.key.data.LocalDeviceReadStore
import com.karr.key.data.LocalKeyRepository
import com.karr.key.data.LocalMapHistoryStore
import com.karr.key.data.LocalOperationStore
import com.karr.key.data.MapHistoryCsv
import com.karr.key.data.MapHistoryImportSummary
import com.karr.key.ui.KarrKeyApp
import com.karr.key.ui.KarrKeyTheme
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var operationStore: LocalOperationStore
    private lateinit var mapHistoryStore: LocalMapHistoryStore
    private lateinit var deviceReadStore: LocalDeviceReadStore
    private val activityScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var permissionResult: ((Boolean) -> Unit)? = null
    private var locationPermissionResult: ((Boolean) -> Unit)? = null
    private var mapExportResult: ((Result<Int>) -> Unit)? = null
    private var mapImportResult: ((Result<MapHistoryImportSummary>) -> Unit)? = null

    private val mapExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val callback = mapExportResult
        mapExportResult = null
        if (uri == null) return@registerForActivityResult
        activityScope.launch {
            callback?.invoke(
                runCatching {
                    withContext(Dispatchers.IO) {
                        val observations = mapHistoryStore.recent(limit = MAP_EXPORT_LIMIT)
                        val reads = deviceReadStore.allGroupedByAddress()
                        val csv = MapHistoryCsv.encode(observations, reads)
                        contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(csv.toByteArray(Charsets.UTF_8))
                        } ?: error("Unable to open the destination file.")
                        observations.size
                    }
                },
            )
        }
    }

    private val mapImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val callback = mapImportResult
        mapImportResult = null
        if (uri == null) return@registerForActivityResult
        activityScope.launch {
            callback?.invoke(
                runCatching {
                    withContext(Dispatchers.IO) {
                        // A real WiGLE export of a well-scanned area can be hundreds of
                        // thousands of lines; stream it instead of reading the whole file
                        // into one String, or large imports run the process out of heap.
                        val content = openCsvReader(uri).use { reader ->
                            MapHistoryCsv.decodeContent(reader.lineSequence())
                        }
                        deviceReadStore.upsertAll(content.readsByAddress)
                        mapHistoryStore.upsertAll(content.observations)
                    }
                },
            )
        }
    }

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
        deviceReadStore = LocalDeviceReadStore(this)

        setContent {
            KarrKeyTheme {
                KarrKeyApp(
                    repository = repository,
                    bleController = app.bleController,
                    deviceKeys = app.deviceKeys,
                    operationStore = operationStore,
                    mapHistoryStore = mapHistoryStore,
                    deviceReadStore = deviceReadStore,
                    autoConnectReadPreferences = app.autoConnectReadPreferences,
                    requestBlePermissions = ::requestBlePermissions,
                    requestMapLocationPermissions = ::requestMapLocationPermissions,
                    requestMapHistoryExport = ::requestMapHistoryExport,
                    requestMapHistoryImport = ::requestMapHistoryImport,
                )
            }
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        operationStore.close()
        mapHistoryStore.close()
        deviceReadStore.close()
        super.onDestroy()
    }

    private fun requestMapHistoryExport(onResult: (Result<Int>) -> Unit) {
        mapExportResult = onResult
        mapExportLauncher.launch(defaultMapExportFileName())
    }

    private fun requestMapHistoryImport(onResult: (Result<MapHistoryImportSummary>) -> Unit) {
        mapImportResult = onResult
        mapImportLauncher.launch(MAP_IMPORT_MIME_TYPES)
    }

    private fun defaultMapExportFileName(): String {
        val timestamp = EXPORT_FILE_TIMESTAMP.format(Instant.now())
        return "karr-key-map-history-$timestamp.csv"
    }

    /**
     * WiGLE's own export is gzip-compressed (`.csv.gz`); detect that from the file's magic
     * bytes rather than its name or MIME type, since content providers don't reliably report
     * either. [GZIPInputStream]'s constructor validates the gzip header immediately, so a
     * non-gzip file fails fast here and a fresh plain-text stream is opened instead.
     */
    private fun openCsvReader(uri: Uri): BufferedReader {
        val stream: InputStream = runCatching {
            val candidate = contentResolver.openInputStream(uri)
                ?: error("Unable to open the selected file.")
            GZIPInputStream(candidate)
        }.getOrElse {
            contentResolver.openInputStream(uri) ?: error("Unable to open the selected file.")
        }
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
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

    private companion object {
        const val MAP_EXPORT_LIMIT = 5_000
        val MAP_IMPORT_MIME_TYPES = arrayOf(
            "text/csv",
            "text/comma-separated-values",
            "text/plain",
            "application/gzip",
            "application/x-gzip",
            "application/octet-stream",
            "*/*",
        )
        val EXPORT_FILE_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss").withZone(ZoneOffset.UTC)
    }
}
