package com.karr.key.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.karr.key.ble.BleController
import com.karr.key.core.KarrTrace
import com.karr.key.data.EncryptedDeviceKeyStore
import com.karr.key.data.LocalKeyRepository
import com.karr.key.data.MapHistoryCsv
import com.karr.key.data.MapHistoryImportSummary
import com.karr.key.desktop.data.DesktopAutoConnectPreferences
import com.karr.key.desktop.data.DesktopDeviceProfileStore
import com.karr.key.desktop.data.DesktopDeviceReadStore
import com.karr.key.desktop.data.DesktopMapHistoryStore
import com.karr.key.desktop.data.DesktopOperationStore
import com.karr.key.desktop.data.FileSecureValueStore
import com.karr.key.desktop.data.FileStringCipher
import com.karr.key.desktop.terminal.TraceLog
import com.karr.key.desktop.transport.MockBleController
import com.karr.key.desktop.transport.UnavailableBleController
import com.karr.key.desktop.transport.WindowsBleController
import com.karr.key.desktop.transport.WindowsBleTransport
import com.karr.key.desktop.resources.Res
import com.karr.key.desktop.resources.app_icon
import com.karr.key.desktop.ui.KarrDesktopApp
import com.karr.key.desktop.ui.KarrDesktopTheme
import com.karr.key.protocol.LegacyDeviceAuthenticator
import org.jetbrains.compose.resources.painterResource
import java.awt.FileDialog
import java.awt.Frame
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Desktop host entry point. Constructs the file-backed stores (encrypted with a
 * per-user master key), installs the trace sink for the in-app Terminal, and
 * renders the shell. The transport is the simulated unit until the Windows-native
 * controller lands; the whole UI is exercisable against it.
 */
fun main() {
    val traceLog = TraceLog()
    KarrTrace.sink = traceLog.asSink(mirrorToStdout = true)
    KarrTrace.enabled = true
    KarrTrace.log(KarrTrace.SWEEP) { "desktop host started" }

    val cipher = FileStringCipher()
    val secureValues = FileSecureValueStore(cipher)
    val deviceKeys = EncryptedDeviceKeyStore(secureValues)
    val repository = LocalKeyRepository()
    val operationStore = DesktopOperationStore(cipher)
    val mapHistoryStore = DesktopMapHistoryStore(cipher)
    val deviceReadStore = DesktopDeviceReadStore(cipher)
    val deviceProfileStore = DesktopDeviceProfileStore(cipher)
    val autoConnectPreferences = DesktopAutoConnectPreferences()

    val controller = selectController(deviceKeys)

    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 880.dp)
        Window(
            onCloseRequest = {
                controller.shutdown()
                deviceReadStore.close()
                exitApplication()
            },
            state = windowState,
            title = "KARR Key",
            icon = painterResource(Res.drawable.app_icon),
        ) {
            KarrDesktopTheme {
                KarrDesktopApp(
                    repository = repository,
                    controller = controller,
                    deviceKeys = deviceKeys,
                    operationStore = operationStore,
                    mapHistoryStore = mapHistoryStore,
                    deviceReadStore = deviceReadStore,
                    deviceProfileStore = deviceProfileStore,
                    autoConnectPreferences = autoConnectPreferences,
                    traceLog = traceLog,
                    requestMapHistoryExport = { onResult ->
                        exportMapHistory(mapHistoryStore, deviceReadStore, deviceProfileStore, onResult)
                    },
                    requestMapHistoryImport = { onResult ->
                        importMapHistory(mapHistoryStore, deviceReadStore, deviceProfileStore, onResult)
                    },
                )
            }
        }
    }
}

/**
 * Chooses the BLE transport. The simulated unit is opt-in only (KARR_BLE=mock) so
 * a normal run never shows a phantom device; otherwise the bleak BLE helper
 * (`karr_ble_bridge.py`, bundled in the app resources) provides real Bluetooth.
 * If the helper or a Python interpreter can't be found, an inert controller is
 * used — it scans nothing and reports how to enable a transport — rather than
 * silently substituting the mock.
 *
 * Overrides: KARR_BLE_HELPER (path to a .py script or a native helper .exe),
 * KARR_PYTHON (python executable).
 */
private fun selectController(deviceKeys: EncryptedDeviceKeyStore): BleController {
    if (System.getenv("KARR_BLE")?.lowercase() == "mock") {
        KarrTrace.warn(KarrTrace.BLE, "Using the simulated KARR unit (KARR_BLE=mock).")
        return MockBleController()
    }
    return runCatching {
        WindowsBleController(
            authenticator = LegacyDeviceAuthenticator(deviceKeys),
            transport = WindowsBleTransport(resolveBleHelperCommand()),
        )
    }.getOrElse { failure ->
        KarrTrace.warn(
            KarrTrace.BLE,
            "BLE helper unavailable (${failure.message}); scanning disabled. Install Python + " +
                "bleak (python -m pip install bleak), or set KARR_BLE=mock for the simulated unit.",
        )
        UnavailableBleController()
    }
}

/** Builds the process command for the BLE helper. */
private fun resolveBleHelperCommand(): List<String> {
    val python = System.getenv("KARR_PYTHON")?.takeIf(String::isNotBlank) ?: "python"
    System.getenv("KARR_BLE_HELPER")?.takeIf(String::isNotBlank)?.let { override ->
        val path = Paths.get(override)
        check(Files.exists(path)) { "BLE helper not found at $override" }
        return if (override.endsWith(".py")) listOf(python, path.toString()) else listOf(path.toString())
    }
    val script = listOfNotNull(
        System.getProperty("compose.application.resources.dir")?.let { Paths.get(it, HELPER_SCRIPT) },
        Paths.get("app-resources", "common", HELPER_SCRIPT),
        Paths.get("src", "desktop", "app", "app-resources", "common", HELPER_SCRIPT),
    ).firstOrNull { Files.exists(it) }
    checkNotNull(script) { "BLE helper script $HELPER_SCRIPT not found" }
    return listOf(python, script.toString())
}

private const val HELPER_SCRIPT = "karr_ble_bridge.py"

private const val MAP_EXPORT_LIMIT = 5_000
private val EXPORT_TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss").withZone(ZoneOffset.UTC)

private fun exportMapHistory(
    mapHistoryStore: DesktopMapHistoryStore,
    deviceReadStore: DesktopDeviceReadStore,
    deviceProfileStore: DesktopDeviceProfileStore,
    onResult: (Result<Int>) -> Unit,
) {
    thread(name = "map-export") {
        val dialog = FileDialog(null as Frame?, "Export map history", FileDialog.SAVE).apply {
            file = "karr-key-map-history-${EXPORT_TIMESTAMP.format(Instant.now())}.csv"
            isVisible = true
        }
        val dir = dialog.directory
        val name = dialog.file
        if (dir == null || name == null) return@thread
        onResult(
            runCatching {
                val observations = mapHistoryStore.recent(limit = MAP_EXPORT_LIMIT)
                val reads = deviceReadStore.allGroupedByAddress()
                val profiles = deviceProfileStore.all()
                val csv = MapHistoryCsv.encode(observations, reads, profiles)
                Files.writeString(Paths.get(dir, name), csv)
                observations.size
            },
        )
    }
}

private fun importMapHistory(
    mapHistoryStore: DesktopMapHistoryStore,
    deviceReadStore: DesktopDeviceReadStore,
    deviceProfileStore: DesktopDeviceProfileStore,
    onResult: (Result<MapHistoryImportSummary>) -> Unit,
) {
    thread(name = "map-import") {
        val dialog = FileDialog(null as Frame?, "Import map history", FileDialog.LOAD).apply {
            isVisible = true
        }
        val dir = dialog.directory
        val name = dialog.file
        if (dir == null || name == null) return@thread
        onResult(
            runCatching {
                val path = Paths.get(dir, name)
                openCsvReader(path.toString()).use { reader ->
                    val content = MapHistoryCsv.decodeContent(reader.lineSequence())
                    deviceReadStore.upsertAll(content.readsByAddress)
                    content.profilesByAddress.forEach { (address, profile) ->
                        deviceProfileStore.record(address, profile.unitModelCode, profile.vulnerability)
                    }
                    mapHistoryStore.upsertAll(content.observations)
                }
            },
        )
    }
}

/** WiGLE exports are gzip-compressed; detect that from the magic bytes rather than the name. */
private fun openCsvReader(path: String): BufferedReader {
    val stream: InputStream = runCatching {
        GZIPInputStream(Files.newInputStream(Paths.get(path)))
    }.getOrElse { Files.newInputStream(Paths.get(path)) }
    return BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
}
