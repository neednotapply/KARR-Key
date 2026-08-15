package com.karr.key.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.karr.key.R
import com.karr.key.ble.BleController
import com.karr.key.ble.BleScanObservation
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.KarrCandidateTier
import com.karr.key.core.KarrTrace
import com.karr.key.data.DeviceReadRecord
import com.karr.key.data.LocalDeviceReadStore
import com.karr.key.data.LocalMapHistoryStore
import com.karr.key.data.LocalMapObservation
import com.karr.key.data.MapCandidateTier
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

@Composable
internal fun MapScreen(
    controller: BleController,
    historyStore: LocalMapHistoryStore,
    deviceReadStore: LocalDeviceReadStore,
    requestBlePermissions: (((Boolean) -> Unit) -> Unit),
    requestLocationPermissions: (((Boolean) -> Unit) -> Unit),
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    var history by remember(historyStore) {
        mutableStateOf<List<LocalMapObservation>>(emptyList())
    }
    var readsByAddress by remember(deviceReadStore) {
        mutableStateOf<Map<String, List<DeviceReadRecord>>>(emptyMap())
    }
    var phoneLocation by remember { mutableStateOf<PhoneLocation?>(null) }
    val currentLocation by rememberUpdatedState(phoneLocation)
    var locationGranted by remember {
        mutableStateOf(context.hasForegroundLocationPermission())
    }
    val locationTracker = remember(context) {
        FrameworkLocationTracker(context) { location -> phoneLocation = location }
    }
    var headingUp by remember { mutableStateOf(false) }
    var deviceHeadingDegrees by remember { mutableStateOf(0f) }
    val orientationTracker = remember(context) {
        FrameworkOrientationTracker(context) { heading -> deviceHeadingDegrees = heading }
    }
    // Populated progressively by the history-loading effect below (nearest-first, in blocks)
    // rather than derived in one shot from `history`, so a large stored library doesn't force
    // one giant clustering pass and one giant draw call before anything appears on screen.
    var estimates by remember { mutableStateOf<List<DeviceMapEstimate>>(emptyList()) }
    var selectedDeviceAddresses by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectedEstimates = estimates.filter { it.address in selectedDeviceAddresses }
    var showDeviceSearch by remember { mutableStateOf(false) }
    var readDetailAddress by remember { mutableStateOf<String?>(null) }
    var focusRequest by remember { mutableStateOf<MapFocusRequest?>(null) }

    // Refreshes the stored READ snapshots; called on entry and after each new read completes so a
    // freshly-read unit shows its data without leaving the Map.
    suspend fun refreshReads() {
        readsByAddress = withContext(Dispatchers.IO) { deviceReadStore.allGroupedByAddress() }
    }

    LaunchedEffect(deviceReadStore) { refreshReads() }
    LaunchedEffect(deviceReadStore, state) {
        // A read only lands while connected; re-pull when the session returns to a settled state.
        if (state is BleSessionState.Ready || state is BleSessionState.Idle) {
            refreshReads()
        }
    }

    LaunchedEffect(historyStore) {
        val loaded = withContext(Dispatchers.IO) { historyStore.recent() }
        history = loaded
        val clustered = withContext(Dispatchers.Default) { estimateDeviceLocations(loaded) }
        // Nearest-to-current-location first when a fix is already available; otherwise the
        // clustering's own most-recently-seen order is a reasonable fallback.
        val here = currentLocation
        val prioritized = if (here != null) {
            clustered.sortedBy { estimate ->
                approxDistanceSqMeters(here.latitude, here.longitude, estimate)
            }
        } else {
            clustered
        }
        estimates = emptyList()
        prioritized.chunked(MAP_ESTIMATE_REVEAL_BLOCK_SIZE).forEach { block ->
            estimates = estimates + block
            withFrameNanos {}
        }
    }

    DisposableEffect(locationGranted) {
        if (locationGranted) {
            locationTracker.start()
        }
        onDispose { locationTracker.stop() }
    }

    // Only tracked while heading-up is active, to avoid burning battery on the rotation
    // sensor when the map is left in the default north-up orientation.
    DisposableEffect(headingUp) {
        if (headingUp) {
            orientationTracker.start()
        }
        onDispose { orientationTracker.stop() }
    }

    LaunchedEffect(controller, locationGranted) {
        controller.scanObservations.collect { observation ->
            val location = currentLocation ?: return@collect
            if (!location.isFresh()) return@collect
            val stored = observation.toLocalMapObservation(location, historyStore)
                ?: return@collect
            history = listOf(stored) + history
            // Re-cluster only this device's own observations rather than the whole history, so
            // a live sighting updates the map without re-processing everything already loaded.
            val normalizedAddress = stored.deviceAddress.uppercase()
            val updated = estimateDeviceLocations(
                history.filter { it.deviceAddress.uppercase() == normalizedAddress },
            ).firstOrNull()
            if (updated != null) {
                // Keep the list nearest-first as live sightings arrive, rather than appending each
                // update to the end: a change near the user should surface at the top, matching the
                // nearest-first order the initial history load establishes.
                val here = currentLocation
                val merged = estimates.filterNot { it.address == normalizedAddress } + updated
                estimates = if (here != null) {
                    merged.sortedBy { approxDistanceSqMeters(here.latitude, here.longitude, it) }
                } else {
                    merged
                }
                KarrTrace.log(KarrTrace.MAP) {
                    val rank = estimates.indexOfFirst { it.address == normalizedAddress } + 1
                    "live ${updated.label} rssi=${updated.rssi} -> rank $rank/${estimates.size}" +
                        if (here == null) " (no fix, unsorted)" else " nearest-first"
                }
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        KarrMapView(
            estimates = estimates,
            phoneLocation = phoneLocation,
            focusRequest = focusRequest,
            mapOrientationDegrees = if (headingUp) (360f - deviceHeadingDegrees) % 360f else 0f,
            onEstimatesSelected = { hits ->
                selectedDeviceAddresses = hits.map { it.address }.toSet()
                // A cluster (more than one hit from a single tap) zooms in to fit its members,
                // matching MeshCore's tap-to-expand behavior — the closer view then naturally
                // splits them back into individual markers via the zoom-gated clustering above.
                if (hits.size > 1) {
                    val latitudes = hits.map { it.latitude }
                    val longitudes = hits.map { it.longitude }
                    focusRequest = MapFocusRequest(
                        point = GeoPoint(latitudes.average(), longitudes.average()),
                        zoom = null,
                        nonce = (focusRequest?.nonce ?: 0) + 1,
                        boundingBox = BoundingBox(
                            latitudes.max(),
                            longitudes.max(),
                            latitudes.min(),
                            longitudes.min(),
                        ),
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("map-view"),
        )
        MapBottomPanel(
            state = state,
            locationGranted = locationGranted,
            selectedEstimates = selectedEstimates,
            readsByAddress = readsByAddress,
            onOpenReadData = { address -> readDetailAddress = address },
            headingUp = headingUp,
            onToggleHeadingUp = { headingUp = !headingUp },
            onEnableLocation = {
                requestLocationPermissions { granted ->
                    locationGranted = granted
                    if (!granted) {
                        scope.launch {
                            snackbar.showSnackbar("Location permission was not granted.")
                        }
                    }
                }
            },
            onScan = {
                requestBlePermissions { granted ->
                    if (granted) {
                        controller.startScan().onFailure { failure ->
                            scope.launch {
                                snackbar.showSnackbar(failure.message ?: "BLE scan failed.")
                            }
                        }
                    } else {
                        scope.launch {
                            snackbar.showSnackbar("Bluetooth permission was not granted.")
                        }
                    }
                }
            },
            onStopScan = controller::stopScan,
            onSearchDevices = { showDeviceSearch = true },
            onRecenter = {
                phoneLocation?.let { location ->
                    focusRequest = MapFocusRequest(
                        point = GeoPoint(location.latitude, location.longitude),
                        zoom = DEFAULT_ZOOM,
                        nonce = (focusRequest?.nonce ?: 0) + 1,
                    )
                }
            },
            canRecenter = phoneLocation != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }

    if (showDeviceSearch) {
        DeviceSearchDialog(
            estimates = estimates,
            onDismiss = { showDeviceSearch = false },
            onSelect = { estimate ->
                selectedDeviceAddresses = setOf(estimate.address)
                focusRequest = MapFocusRequest(
                    point = GeoPoint(estimate.latitude, estimate.longitude),
                    zoom = SEARCH_FOCUS_ZOOM,
                    nonce = (focusRequest?.nonce ?: 0) + 1,
                )
                showDeviceSearch = false
            },
        )
    }

    readDetailAddress?.let { address ->
        val reads = readsByAddress[address].orEmpty()
        if (reads.isEmpty()) {
            readDetailAddress = null
        } else {
            UnitReadDataDialog(
                label = estimates.firstOrNull { it.address == address }?.label ?: address,
                address = address,
                reads = reads,
                onDismiss = { readDetailAddress = null },
            )
        }
    }
}

@Composable
private fun MapBottomPanel(
    state: BleSessionState,
    locationGranted: Boolean,
    selectedEstimates: List<DeviceMapEstimate>,
    readsByAddress: Map<String, List<DeviceReadRecord>>,
    onOpenReadData: (String) -> Unit,
    onEnableLocation: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onSearchDevices: () -> Unit,
    headingUp: Boolean,
    onToggleHeadingUp: () -> Unit,
    onRecenter: () -> Unit,
    canRecenter: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .testTag("map-bottom-panel"),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (selectedEstimates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    selectedEstimates.forEach { estimate ->
                        val hasReads = readsByAddress[estimate.address]?.isNotEmpty() == true
                        EstimateDetail(
                            estimate = estimate,
                            hasReadData = hasReads,
                            onClick = if (hasReads) {
                                { onOpenReadData(estimate.address) }
                            } else {
                                null
                            },
                        )
                    }
                }
                HorizontalDivider()
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!locationGranted) {
                    Button(onClick = onEnableLocation) {
                        Text("Enable location")
                    }
                }
                FilledIconButton(
                    onClick = if (state is BleSessionState.Scanning) onStopScan else onScan,
                    enabled = state is BleSessionState.Scanning || state.canMapScan(),
                ) {
                    Icon(
                        painter = painterResource(
                            if (state is BleSessionState.Scanning) {
                                R.drawable.ic_stop
                            } else {
                                R.drawable.ic_wifi
                            },
                        ),
                        contentDescription = if (state is BleSessionState.Scanning) {
                            "Stop"
                        } else {
                            "Scan"
                        },
                    )
                }
                OutlinedIconButton(
                    onClick = onSearchDevices,
                    modifier = Modifier.testTag("map-search-devices"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = "Search",
                    )
                }
                if (headingUp) {
                    FilledIconButton(
                        onClick = onToggleHeadingUp,
                        modifier = Modifier.testTag("map-compass"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_compass),
                            contentDescription = "North up",
                        )
                    }
                } else {
                    OutlinedIconButton(
                        onClick = onToggleHeadingUp,
                        modifier = Modifier.testTag("map-compass"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_compass),
                            contentDescription = "Compass",
                        )
                    }
                }
                OutlinedIconButton(
                    onClick = onRecenter,
                    enabled = canRecenter,
                    modifier = Modifier.testTag("map-recenter"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_recenter),
                        contentDescription = "Recenter",
                    )
                }
            }
            Text(
                text = "© OpenStreetMap contributors",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DeviceSearchDialog(
    estimates: List<DeviceMapEstimate>,
    onDismiss: () -> Unit,
    onSelect: (DeviceMapEstimate) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(estimates, query) {
        estimates
            .filter { it.label.contains(query, ignoreCase = true) }
            .sortedByDescending(DeviceMapEstimate::lastSeenUtc)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search devices") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Device name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("map-search-field"),
                )
                if (results.isEmpty()) {
                    Text(
                        if (query.isBlank()) {
                            "No stored devices yet."
                        } else {
                            "No stored devices match \"$query\"."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(results, key = DeviceMapEstimate::address) { estimate ->
                            Surface(
                                onClick = { onSelect(estimate) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("map-search-result:${estimate.address}"),
                                tonalElevation = 1.dp,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        estimate.label,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Text(
                                        estimate.address,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Last seen ${formatLastSeen(estimate.lastSeenUtc)}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun EstimateDetail(
    estimate: DeviceMapEstimate,
    hasReadData: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .testTag("map-selected-estimate:${estimate.address}")
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    Column(
        modifier = rowModifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            estimate.label,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            "${estimate.address}  RSSI ${estimate.rssi} dBm  " +
                "radius ${estimate.radiusMeters.toInt()} m",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            estimate.evidence,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Last seen ${formatLastSeen(estimate.lastSeenUtc)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (hasReadData) {
            Text(
                "View unit data ›",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("map-view-unit-data:${estimate.address}"),
            )
        }
    }
}

@Composable
private fun UnitReadDataDialog(
    label: String,
    address: String,
    reads: List<DeviceReadRecord>,
    onDismiss: () -> Unit,
) {
    val ordered = remember(reads) { reads.sortedByDescending(DeviceReadRecord::capturedUtc) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    address,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .testTag("map-unit-read-data"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(ordered, key = DeviceReadRecord::command) { record ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                record.heading,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            record.lines.forEach { line ->
                                Text(
                                    line,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                "Read ${formatLastSeen(record.capturedUtc)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

private fun formatLastSeen(instant: Instant): String =
    LAST_SEEN_FORMAT.format(instant)

@Composable
private fun KarrMapView(
    estimates: List<DeviceMapEstimate>,
    phoneLocation: PhoneLocation?,
    focusRequest: MapFocusRequest?,
    mapOrientationDegrees: Float,
    onEstimatesSelected: (List<DeviceMapEstimate>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val minTouchRadiusPx = with(density) { MIN_TOUCH_TARGET_RADIUS.toPx() }
    val clusterCellPx = with(density) { CLUSTER_CELL_SIZE.toPx() }
    val clusterRadiusPx = with(density) { CLUSTER_MARKER_RADIUS.toPx() }
    val darkTheme = isSystemInDarkTheme()

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            configureOsmMap(viewContext)
            val mapView = MapView(viewContext).apply {
                setBackgroundColor(
                    if (darkTheme) Color(0xff1b1b1b).toArgb() else Color(0xffe5e7eb).toArgb(),
                )
                setTileSource(TileSourceFactory.MAPNIK)
                setUseDataConnection(true)
                setMultiTouchControls(true)
                setDestroyMode(false)
                @Suppress("DEPRECATION")
                setBuiltInZoomControls(false)
                setTilesScaledToDpi(true)
                setMinZoomLevel(OSM_MIN_ZOOM)
                setMaxZoomLevel(OSM_MAX_ZOOM)
                controller.setZoom(DEFAULT_ZOOM)
                mapOverlay.setColorFilter(if (darkTheme) OSM_DARK_MODE_COLOR_FILTER else null)
                setMapOrientation(mapOrientationDegrees)
            }
            val overlay = ConfidenceOverlay()
            mapView.overlays.add(overlay)
            val createdHost = MapHost(
                mapView = mapView,
                overlay = overlay,
                lifecycle = lifecycleOwner.lifecycle,
            )
            mapView.tag = createdHost
            phoneLocation?.let { location ->
                mapView.controller.setCenter(
                    GeoPoint(location.latitude, location.longitude),
                )
                createdHost.centeredOnce = true
            }
            mapView
        },
        update = { mapView ->
            val currentHost = mapView.tag as MapHost
            mapView.mapOverlay.setColorFilter(if (darkTheme) OSM_DARK_MODE_COLOR_FILTER else null)
            mapView.setMapOrientation(mapOrientationDegrees)
            currentHost.overlay.estimates = estimates
            currentHost.overlay.phoneLocation = phoneLocation
            currentHost.overlay.minTouchRadiusPx = minTouchRadiusPx
            currentHost.overlay.darkTheme = darkTheme
            currentHost.overlay.clusterCellPx = clusterCellPx
            currentHost.overlay.clusterRadiusPx = clusterRadiusPx
            currentHost.overlay.onEstimatesTapped = onEstimatesSelected
            if (!currentHost.centeredOnce) {
                val location = phoneLocation
                if (location != null) {
                    mapView.controller.setZoom(DEFAULT_ZOOM)
                    mapView.controller.setCenter(
                        GeoPoint(location.latitude, location.longitude),
                    )
                    currentHost.centeredOnce = true
                }
            }
            focusRequest?.let { request ->
                if (request.nonce != currentHost.appliedFocusNonce) {
                    val bounds = request.boundingBox
                    if (bounds != null) {
                        mapView.zoomToBoundingBox(bounds, true, CLUSTER_FOCUS_PADDING_PX)
                    } else {
                        request.zoom?.let(mapView.controller::setZoom)
                        mapView.controller.setCenter(request.point)
                    }
                    currentHost.appliedFocusNonce = request.nonce
                    currentHost.centeredOnce = true
                }
            }
            mapView.invalidate()
        },
        onRelease = { mapView ->
            (mapView.tag as MapHost).release()
        },
    )
}

@Synchronized
private fun configureOsmMap(context: Context) {
    if (osmMapConfigured) return
    val baseDirectory = File(context.cacheDir, "osm_map").apply {
        check(exists() || mkdirs()) { "Unable to create OSM map cache directory." }
    }
    val tileDirectory = File(baseDirectory, "tiles").apply {
        check(exists() || mkdirs()) { "Unable to create OSM tile cache directory." }
    }
    Configuration.getInstance().apply {
        userAgentValue = MAP_USER_AGENT
        osmdroidBasePath = baseDirectory
        osmdroidTileCache = tileDirectory
        tileFileSystemCacheMaxBytes = OSM_TILE_CACHE_BYTES
        tileFileSystemCacheTrimBytes = OSM_TILE_CACHE_TRIM_BYTES
    }
    osmMapConfigured = true
}

private class MapHost(
    val mapView: MapView,
    val overlay: ConfidenceOverlay,
    private val lifecycle: Lifecycle,
) {
    var centeredOnce: Boolean = false
    var appliedFocusNonce: Int = 0

    private var resumed = false
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP,
            Lifecycle.Event.ON_DESTROY,
            -> pause()
            else -> Unit
        }
    }

    init {
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            resume()
        }
    }

    fun release() {
        lifecycle.removeObserver(observer)
        pause()
        overlay.onDetach(mapView)
        mapView.onDetach()
    }

    private fun resume() {
        if (!resumed) {
            mapView.onResume()
            resumed = true
        }
    }

    private fun pause() {
        if (resumed) {
            mapView.onPause()
            resumed = false
        }
    }
}

private class ConfidenceOverlay : Overlay() {
    var estimates: List<DeviceMapEstimate> = emptyList()
    var phoneLocation: PhoneLocation? = null
    var minTouchRadiusPx: Float = 0f
    var darkTheme: Boolean = false
    // Markers whose screen positions land in the same cell are combined into one numbered
    // bubble, so nearby devices merge together at a wide zoom and split apart as you zoom in
    // and their screen-space distance grows past a cell's width — the same grid-bucketing
    // approach most map SDKs' marker clustering uses, kept to a single O(n) pass per draw.
    var clusterCellPx: Float = 0f
    var clusterRadiusPx: Float = 0f
    var onEstimatesTapped: ((List<DeviceMapEstimate>) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val clusterTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        markerPaint.color = (if (darkTheme) MARKER_DOT_DARK else MARKER_DOT_LIGHT).toArgb()
        phonePaint.color = (if (darkTheme) PHONE_DOT_DARK else PHONE_DOT_LIGHT).toArgb()
        guidePaint.color = (if (darkTheme) GUIDE_RING_DARK else GUIDE_RING_LIGHT)
            .toArgb()
            .withAlpha(90)
        clusterTextPaint.color = (if (darkTheme) MARKER_DOT_DARK else MARKER_DOT_LIGHT).toArgb()
        clusterTextPaint.textSize = clusterRadiusPx * CLUSTER_TEXT_SIZE_FACTOR
        phoneLocation?.let { location ->
            val point = projection.toPixels(
                GeoPoint(location.latitude, location.longitude),
                Point(),
            )
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 10f, phonePaint)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 18f, guidePaint)
        }
        clusterEstimates(mapView, projection).forEach { cluster ->
            val singleton = cluster.members.singleOrNull()
            if (singleton != null) {
                drawSingleEstimate(canvas, projection, singleton.first, singleton.second)
            } else {
                drawCluster(canvas, cluster)
            }
        }
    }

    private fun drawSingleEstimate(
        canvas: Canvas,
        projection: Projection,
        estimate: DeviceMapEstimate,
        centerPoint: Point,
    ) {
        val radiusPx = projectedRadiusPx(projection, estimate, centerPoint)
        val color = tierColor(estimate.tier == MapCandidateTier.CONFIRMED_FORMAT)
        fillPaint.color = color.withAlpha(if (darkTheme) 90 else 48)
        strokePaint.color = color.withAlpha(if (darkTheme) 235 else 190)
        canvas.drawCircle(centerPoint.x.toFloat(), centerPoint.y.toFloat(), radiusPx, fillPaint)
        canvas.drawCircle(centerPoint.x.toFloat(), centerPoint.y.toFloat(), radiusPx, strokePaint)
        canvas.drawCircle(centerPoint.x.toFloat(), centerPoint.y.toFloat(), 6f, markerPaint)
    }

    private fun drawCluster(canvas: Canvas, cluster: MapCluster) {
        val confirmed = cluster.members.any { (estimate, _) ->
            estimate.tier == MapCandidateTier.CONFIRMED_FORMAT
        }
        val color = tierColor(confirmed)
        fillPaint.color = color.withAlpha(if (darkTheme) 210 else 190)
        strokePaint.color = color.withAlpha(255)
        canvas.drawCircle(cluster.centerX, cluster.centerY, clusterRadiusPx, fillPaint)
        canvas.drawCircle(cluster.centerX, cluster.centerY, clusterRadiusPx, strokePaint)
        val textY = cluster.centerY - (clusterTextPaint.descent() + clusterTextPaint.ascent()) / 2
        canvas.drawText(
            cluster.members.size.toString(),
            cluster.centerX,
            textY,
            clusterTextPaint,
        )
    }

    private fun tierColor(confirmed: Boolean): Int = when {
        confirmed && darkTheme -> CONFIRMED_MARKER_DARK
        confirmed -> CONFIRMED_MARKER_LIGHT
        darkTheme -> POSSIBLE_MARKER_DARK
        else -> POSSIBLE_MARKER_LIGHT
    }.toArgb()

    // Ported from MeshCore's map clustering (github.com/zjs81/meshcore-open,
    // lib/screens/map_screen.dart): individual markers stay individual above
    // CLUSTER_OFF_ZOOM no matter how close together they are, and below it, the desired
    // on-screen cell size is converted into degrees at the current zoom (assuming standard
    // 256px web-mercator tiles) so bucketing is by lat/lon rather than by re-projecting every
    // marker to screen pixels just to find its cell.
    private fun clusterEstimates(mapView: MapView, projection: Projection): List<MapCluster> {
        if (estimates.isEmpty()) return emptyList()
        val zoom = mapView.zoomLevelDouble
        if (zoom >= CLUSTER_OFF_ZOOM || clusterCellPx <= 0f) {
            return estimates.map { estimate ->
                MapCluster(
                    listOf(
                        estimate to projection.toPixels(
                            GeoPoint(estimate.latitude, estimate.longitude),
                            Point(),
                        ),
                    ),
                )
            }
        }
        val cellDegrees = 360.0 / (256.0 * 2.0.pow(zoom)) * clusterCellPx
        val buckets = LinkedHashMap<Pair<Int, Int>, MutableList<DeviceMapEstimate>>()
        estimates.forEach { estimate ->
            val cellKey = floor(estimate.latitude / cellDegrees).toInt() to
                floor(estimate.longitude / cellDegrees).toInt()
            buckets.getOrPut(cellKey) { mutableListOf() }.add(estimate)
        }
        return buckets.values.map { members ->
            MapCluster(
                members.map { estimate ->
                    estimate to projection.toPixels(
                        GeoPoint(estimate.latitude, estimate.longitude),
                        Point(),
                    )
                },
            )
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val projection = mapView.projection
        val hitCluster = clusterEstimates(mapView, projection).firstOrNull { cluster ->
            val singleton = cluster.members.singleOrNull()
            val touchRadiusPx = if (singleton != null) {
                max(projectedRadiusPx(projection, singleton.first, singleton.second), minTouchRadiusPx)
            } else {
                max(clusterRadiusPx, minTouchRadiusPx)
            }
            val dx = e.x - cluster.centerX
            val dy = e.y - cluster.centerY
            (dx * dx + dy * dy) <= touchRadiusPx * touchRadiusPx
        }
        val hits = hitCluster?.members.orEmpty().map { it.first }
        onEstimatesTapped?.invoke(hits)
        return hits.isNotEmpty()
    }
}

private class MapCluster(val members: List<Pair<DeviceMapEstimate, Point>>) {
    val centerX: Float = members.map { it.second.x }.average().toFloat()
    val centerY: Float = members.map { it.second.y }.average().toFloat()
}

private fun projectedRadiusPx(
    projection: Projection,
    estimate: DeviceMapEstimate,
    centerPoint: Point,
): Float {
    val longitudeMeters = max(
        1.0,
        METERS_PER_DEGREE_LATITUDE *
            cos(Math.toRadians(estimate.latitude)).coerceAtLeast(0.01),
    )
    val edge = projection.toPixels(
        GeoPoint(
            estimate.latitude,
            estimate.longitude + estimate.radiusMeters / longitudeMeters,
        ),
        Point(),
    )
    return abs(edge.x - centerPoint.x).coerceAtLeast(12).toFloat()
}

internal data class PhoneLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val provider: String,
    val elapsedRealtimeMillis: Long,
)

internal data class DeviceMapEstimate(
    val address: String,
    val label: String,
    val tier: MapCandidateTier,
    val evidence: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val rssi: Int,
    val lastSeenUtc: Instant,
)

// nonce distinguishes repeat requests to focus the same point, since a data-class equality
// check alone wouldn't trigger a re-center when the same device is searched twice in a row.
// A null zoom leaves the map's current zoom level untouched (used by recenter-on-me). A
// non-null boundingBox takes priority over point/zoom (used to zoom into a tapped cluster).
private data class MapFocusRequest(
    val point: GeoPoint,
    val zoom: Double?,
    val nonce: Int,
    val boundingBox: BoundingBox? = null,
)

private class FrameworkLocationTracker(
    context: Context,
    private val onLocation: (PhoneLocation) -> Unit,
) : LocationListener {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun start() {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> locationManager.isProviderEnabled(provider) }
            .forEach { provider ->
                locationManager.getLastKnownLocation(provider)?.let(::onLocationChanged)
                locationManager.requestLocationUpdates(
                    provider,
                    LOCATION_INTERVAL_MILLIS,
                    LOCATION_DISTANCE_METERS,
                    this,
                    Looper.getMainLooper(),
                )
            }
    }

    fun stop() {
        locationManager.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        onLocation(
            PhoneLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) {
                    location.accuracy
                } else {
                    DEFAULT_LOCATION_ACCURACY_METERS
                },
                provider = location.provider ?: "unknown",
                elapsedRealtimeMillis = location.elapsedRealtimeNanos / 1_000_000L,
            ),
        )
    }
}

// Tracks the phone's compass heading (0 = true north, clockwise) using the fused rotation-vector
// sensor, remapped for the display's current rotation so heading-up mode stays correct in both
// portrait and landscape.
private class FrameworkOrientationTracker(
    context: Context,
    private val onHeading: (Float) -> Unit,
) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    fun start() {
        rotationVectorSensor?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val (remapX, remapY) = remapAxesFor(appContext.currentDisplayRotation())
        SensorManager.remapCoordinateSystem(rotationMatrix, remapX, remapY, remappedRotationMatrix)
        SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)
        val azimuthDegrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        onHeading((azimuthDegrees + 360f) % 360f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

private fun remapAxesFor(displayRotation: Int): Pair<Int, Int> = when (displayRotation) {
    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
}

// Context#getDisplay() throws on a non-visual context (e.g. the application context this
// tracker is built with), so the display is looked up via DisplayManager instead, which works
// from any context.
private fun Context.currentDisplayRotation(): Int {
    val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    return displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
}

private fun BleScanObservation.toLocalMapObservation(
    location: PhoneLocation,
    historyStore: LocalMapHistoryStore,
): LocalMapObservation? {
    val tier = when (device.classification.tier) {
        KarrCandidateTier.CONFIRMED_FORMAT -> MapCandidateTier.CONFIRMED_FORMAT
        KarrCandidateTier.POSSIBLE -> MapCandidateTier.POSSIBLE
        KarrCandidateTier.OTHER -> return null
    }
    val range = estimatedBleRangeMeters(device.rssi)
    val confidence = confidenceRadiusMeters(
        rangeMeters = range,
        phoneAccuracyMeters = location.accuracyMeters,
    )
    return historyStore.add(
        observedUtc = Instant.ofEpochMilli(observedUtcMillis),
        deviceAddress = device.address,
        deviceName = device.name,
        classificationTier = tier,
        classificationEvidence = device.classification.evidence,
        rssi = device.rssi,
        phoneLatitude = location.latitude,
        phoneLongitude = location.longitude,
        phoneAccuracyMeters = location.accuracyMeters,
        locationProvider = location.provider,
        estimatedRangeMeters = range,
        confidenceRadiusMeters = confidence,
    )
}

internal fun estimatedBleRangeMeters(
    rssi: Int,
    txPowerAtOneMeter: Int = RSSI_ONE_METER_DBM,
    pathLossExponent: Double = PATH_LOSS_EXPONENT,
): Double {
    if (rssi >= 0) return MAX_RANGE_METERS
    val estimate = 10.0.pow((txPowerAtOneMeter - rssi) / (10.0 * pathLossExponent))
    return estimate.coerceIn(MIN_RANGE_METERS, MAX_RANGE_METERS)
}

internal fun confidenceRadiusMeters(
    rangeMeters: Double,
    phoneAccuracyMeters: Float,
): Double = (rangeMeters + phoneAccuracyMeters).coerceIn(
    MIN_CONFIDENCE_RADIUS_METERS,
    MAX_CONFIDENCE_RADIUS_METERS,
)

internal fun estimateDeviceLocations(
    observations: List<LocalMapObservation>,
): List<DeviceMapEstimate> = observations
    // MAC casing varies by source (live BLE scans report uppercase; imported WiGLE CSVs are
    // often lowercase), so group case-insensitively or the same device shows up as two pins.
    .groupBy { it.deviceAddress.uppercase() }
    .map { (normalizedAddress, group) ->
        val sorted = group.sortedByDescending(LocalMapObservation::observedUtc)
        val weighted = sorted.fold(WeightedLocation()) { accumulator, observation ->
            val confidence = max(observation.confidenceRadiusMeters, 1.0)
            val signalWeight = (observation.rssi + 100).coerceIn(1, 80).toDouble()
            val weight = signalWeight / (confidence * confidence)
            accumulator.add(observation, weight)
        }
        val latest = sorted.first()
        DeviceMapEstimate(
            address = normalizedAddress,
            label = latest.deviceName ?: "Unnamed KARR candidate",
            tier = latest.classificationTier,
            evidence = latest.classificationEvidence,
            latitude = weighted.latitude,
            longitude = weighted.longitude,
            radiusMeters = sorted.minOf(LocalMapObservation::confidenceRadiusMeters)
                .coerceAtLeast(MIN_CONFIDENCE_RADIUS_METERS),
            rssi = latest.rssi,
            lastSeenUtc = latest.observedUtc,
        )
    }
    .sortedByDescending(DeviceMapEstimate::lastSeenUtc)

// Squared equirectangular-approximation distance: cheap, and sufficient for ordering estimates
// by proximity rather than reporting an exact figure, so it skips the sqrt entirely.
private fun approxDistanceSqMeters(
    fromLatitude: Double,
    fromLongitude: Double,
    estimate: DeviceMapEstimate,
): Double {
    val avgLatRadians = Math.toRadians((fromLatitude + estimate.latitude) / 2.0)
    val dx = (estimate.longitude - fromLongitude) * cos(avgLatRadians) * METERS_PER_DEGREE_LATITUDE
    val dy = (estimate.latitude - fromLatitude) * METERS_PER_DEGREE_LATITUDE
    return dx * dx + dy * dy
}

private data class WeightedLocation(
    val latitudeSum: Double = 0.0,
    val longitudeSum: Double = 0.0,
    val weightSum: Double = 0.0,
) {
    val latitude: Double
        get() = latitudeSum / weightSum
    val longitude: Double
        get() = longitudeSum / weightSum

    fun add(observation: LocalMapObservation, weight: Double): WeightedLocation =
        copy(
            latitudeSum = latitudeSum + observation.phoneLatitude * weight,
            longitudeSum = longitudeSum + observation.phoneLongitude * weight,
            weightSum = weightSum + weight,
        )
}

private fun Context.hasForegroundLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

private fun PhoneLocation.isFresh(): Boolean =
    android.os.SystemClock.elapsedRealtime() - elapsedRealtimeMillis <= LOCATION_FRESHNESS_MILLIS

private fun BleSessionState.canMapScan(): Boolean =
    this is BleSessionState.Idle ||
        this is BleSessionState.Error ||
        this is BleSessionState.ReconnectFailed

private fun Int.withAlpha(alpha: Int): Int =
    (this and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)

private const val MAP_USER_AGENT =
    "KARR-Key/1.0.3 (+https://github.com/neednotapply/KARR-Key)"
private const val OSM_TILE_CACHE_BYTES = 50L * 1024L * 1024L
private const val OSM_TILE_CACHE_TRIM_BYTES = 40L * 1024L * 1024L
private const val OSM_MIN_ZOOM = 0.0
private const val OSM_MAX_ZOOM = 19.0
private const val DEFAULT_ZOOM = OSM_MAX_ZOOM

// Inverts luminance, then rotates hue 180° to undo the resulting color shift (the same
// invert()+hue-rotate(180deg) trick browsers use for auto dark mode), so OSM's light raster
// tiles render as a dark map without needing a separate dark tile server or API key.
private val OSM_DARK_MODE_COLOR_FILTER = ColorMatrixColorFilter(
    floatArrayOf(
        0.574f, -1.430f, -0.144f, 0f, 255f,
        -0.426f, -0.430f, -0.144f, 0f, 255f,
        -0.426f, -1.430f, 0.856f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)
// Light-mode marker colors sit against light OSM tiles and don't need extra punch. Dark-mode
// variants are brighter/more saturated so markers stay legible against the dark-filtered map
// at wide zoom levels, where each marker covers only a few pixels.
private val CONFIRMED_MARKER_LIGHT = Color(0xff0f766e)
private val CONFIRMED_MARKER_DARK = Color(0xff2dd4bf)
private val POSSIBLE_MARKER_LIGHT = Color(0xffb45309)
private val POSSIBLE_MARKER_DARK = Color(0xfffbbf24)
private val MARKER_DOT_LIGHT = Color(0xff111827)
private val MARKER_DOT_DARK = Color(0xfff8fafc)
private val PHONE_DOT_LIGHT = Color(0xff2563eb)
private val PHONE_DOT_DARK = Color(0xff60a5fa)
private val GUIDE_RING_LIGHT = Color(0xff64748b)
private val GUIDE_RING_DARK = Color(0xff94a3b8)
private const val SEARCH_FOCUS_ZOOM = 18.0
private val LAST_SEEN_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneId.systemDefault())
private const val LOCATION_INTERVAL_MILLIS = 1_000L
private const val LOCATION_DISTANCE_METERS = 1f
private const val LOCATION_FRESHNESS_MILLIS = 30_000L
private const val DEFAULT_LOCATION_ACCURACY_METERS = 100f
private const val RSSI_ONE_METER_DBM = -59
private const val PATH_LOSS_EXPONENT = 2.4
private const val MIN_RANGE_METERS = 1.0
private const val MAX_RANGE_METERS = 120.0
private const val MIN_CONFIDENCE_RADIUS_METERS = 5.0
private const val MAX_CONFIDENCE_RADIUS_METERS = 250.0
private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
private const val MAP_ESTIMATE_REVEAL_BLOCK_SIZE = 30
private val MIN_TOUCH_TARGET_RADIUS = 24.dp
private val CLUSTER_CELL_SIZE = 64.dp
// Above this zoom, markers always render individually — kept close to OSM_MAX_ZOOM (19) so
// only the closest zoom step is exempt; clustering is allowed at every zoom below that,
// unlike MeshCore's own, much wider-zoomed-out _clusterOffZoom of 12.5.
private const val CLUSTER_OFF_ZOOM = 18.0
private const val CLUSTER_FOCUS_PADDING_PX = 96
private val CLUSTER_MARKER_RADIUS = 22.dp
private const val CLUSTER_TEXT_SIZE_FACTOR = 0.9f
private var osmMapConfigured = false
