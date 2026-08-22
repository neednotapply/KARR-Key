package com.karr.key.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
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
import androidx.compose.foundation.layout.Row
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
import com.karr.key.data.DeviceProfile
import com.karr.key.data.DeviceReadRecord
import com.karr.key.data.LocalDeviceProfileStore
import com.karr.key.data.LocalDeviceReadStore
import com.karr.key.data.LocalMapHistoryStore
import com.karr.key.data.LocalMapObservation
import com.karr.key.data.MapCandidateTier
import com.karr.key.data.VEHICLE_GPS_PROVIDER
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.KarrVulnerabilityStatus
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
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
    deviceProfileStore: LocalDeviceProfileStore,
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
    // What connecting to each unit proved (model + vulnerable/patched), keyed by address. Used to
    // replace the scan-time format guess on the device card with real, connection-confirmed data.
    var profilesByAddress by remember(deviceProfileStore) {
        mutableStateOf<Map<String, DeviceProfile>>(emptyMap())
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
    // Older/read-only imports can retain the decoded READ_STATE snapshot without a corresponding
    // vehicle-GPS map-history row. Recover that position from the stored read so the coordinate
    // affordance and its map line do not disappear merely because the secondary history row is
    // absent.
    val displayedEstimates = remember(estimates, readsByAddress) {
        estimates.map { estimate ->
            if (estimate.reportedPosition != null) {
                estimate
            } else {
                estimate.copy(
                    reportedPosition = reportedPositionFromReads(
                        readsByAddress[estimate.address].orEmpty(),
                    ),
                )
            }
        }
    }
    val selectedEstimates = displayedEstimates.filter { it.address in selectedDeviceAddresses }
    // The unit whose reported-GPS line is currently revealed. Set by tapping the coords in that
    // unit's card; cleared by any other action (selecting a unit, a map button) so the line is
    // transient and only shows for the location you just asked to view.
    var revealedReportedAddress by remember { mutableStateOf<String?>(null) }
    // Uppercased addresses whose full location-history trail is currently drawn on the map. Toggled
    // per device from its card in the bottom panel; reset when the tap/search selection changes.
    var historyShownAddresses by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeviceSearch by remember { mutableStateOf(false) }
    var readDetailAddress by remember { mutableStateOf<String?>(null) }
    var focusRequest by remember { mutableStateOf<MapFocusRequest?>(null) }

    // Refreshes the stored READ snapshots; called on entry and after each new read completes so a
    // freshly-read unit shows its data without leaving the Map.
    suspend fun refreshReads() {
        readsByAddress = withContext(Dispatchers.IO) { deviceReadStore.allGroupedByAddress() }
        profilesByAddress = withContext(Dispatchers.IO) { deviceProfileStore.all() }
    }

    LaunchedEffect(deviceReadStore) { refreshReads() }
    LaunchedEffect(deviceReadStore, state) {
        // A read or device profile only lands while connected; re-pull when the session settles.
        // Scanning is included so an auto-sweep's just-written profile shows on the next scan resume.
        if (state is BleSessionState.Ready ||
            state is BleSessionState.Idle ||
            state is BleSessionState.Scanning
        ) {
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

    // Display-only: builds an in-memory observation to update live markers while the Map screen is
    // open. Persistence to history is owned by the app-level recorder in KarrKeyApp so sightings
    // are stored during any scan on any tab, not only while this screen is composed.
    LaunchedEffect(controller, locationGranted) {
        controller.scanObservations.collect { observation ->
            val location = currentLocation ?: return@collect
            if (!location.isFresh()) return@collect
            val stored = observation.toLocalMapObservation(location)
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

    // How many sightings each unit has (its own stale GPS reports excluded — those are the separate
    // reported-position annotation), used to label and enable the per-unit "location history" toggle.
    val historyPointCounts = remember(history) {
        history
            .filterNot { it.locationProvider == VEHICLE_GPS_PROVIDER }
            .groupingBy { it.deviceAddress.uppercase() }
            .eachCount()
    }
    // Oldest-first sighting points for each unit whose history is revealed, drawn as its trail.
    val historyTrackByAddress = remember(history, historyShownAddresses) {
        if (historyShownAddresses.isEmpty()) {
            emptyMap()
        } else {
            history
                .filterNot { it.locationProvider == VEHICLE_GPS_PROVIDER }
                .filter { it.deviceAddress.uppercase() in historyShownAddresses }
                .groupBy { it.deviceAddress.uppercase() }
                .mapValues { (_, group) ->
                    group.sortedBy(LocalMapObservation::observedUtc)
                        .map { GeoPoint(it.phoneLatitude, it.phoneLongitude) }
                }
        }
    }
    val toggleHistory: (String) -> Unit = { address ->
        val normalized = address.uppercase()
        if (normalized in historyShownAddresses) {
            historyShownAddresses = historyShownAddresses - normalized
        } else {
            historyShownAddresses = historyShownAddresses + normalized
            // Fit the map to the whole trail so its spread is visible the moment it is turned on.
            val points = history
                .filterNot { it.locationProvider == VEHICLE_GPS_PROVIDER }
                .filter { it.deviceAddress.uppercase() == normalized }
                .map { GeoPoint(it.phoneLatitude, it.phoneLongitude) }
            if (points.size > 1) {
                focusRequest = MapFocusRequest(
                    point = points.last(),
                    zoom = null,
                    nonce = (focusRequest?.nonce ?: 0) + 1,
                    boundingBox = BoundingBox.fromGeoPointsSafe(points),
                )
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        KarrMapView(
            estimates = displayedEstimates,
            phoneLocation = phoneLocation,
            focusRequest = focusRequest,
            mapOrientationDegrees = if (headingUp) (360f - deviceHeadingDegrees) % 360f else 0f,
            revealedReportedAddress = revealedReportedAddress,
            vulnerabilityByAddress = remember(profilesByAddress) {
                profilesByAddress.mapValues { (_, profile) -> profile.vulnerability }
            },
            historyTrackByAddress = historyTrackByAddress,
            onEstimatesSelected = { hits, expand ->
                selectedDeviceAddresses = hits.map { it.address }.toSet()
                revealedReportedAddress = null
                historyShownAddresses = emptySet()
                KarrTrace.log(KarrTrace.MAP) {
                    "tap -> ${hits.size} device(s): ${hits.joinToString { it.label }}" +
                        if (expand) " (expand cluster)" else ""
                }
                // A numbered grid cluster expands (zoom to fit) to declutter, matching MeshCore's
                // tap-to-expand behavior. Overlapping individual circles instead select every
                // device under the tap, so the panel shows results for all of them.
                if (expand && hits.size > 1) {
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
            onReportedPositionTapped = { reported ->
                KarrTrace.log(KarrTrace.MAP) {
                    "jump to reported ${reported.latitude}, ${reported.longitude}"
                }
                focusRequest = MapFocusRequest(
                    point = reported,
                    zoom = null,
                    nonce = (focusRequest?.nonce ?: 0) + 1,
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("map-view"),
        )
        MapBottomPanel(
            state = state,
            locationGranted = locationGranted,
            selectedEstimates = selectedEstimates,
            profilesByAddress = profilesByAddress,
            onOpenReadData = { address -> readDetailAddress = address },
            headingUp = headingUp,
            onToggleHeadingUp = {
                headingUp = !headingUp
                revealedReportedAddress = null
            },
            onEnableLocation = {
                revealedReportedAddress = null
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
                revealedReportedAddress = null
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
            onStopScan = {
                revealedReportedAddress = null
                controller.stopScan()
            },
            onSearchDevices = {
                revealedReportedAddress = null
                showDeviceSearch = true
            },
            onRecenter = {
                revealedReportedAddress = null
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
            estimates = displayedEstimates,
            profilesByAddress = profilesByAddress,
            onDismiss = { showDeviceSearch = false },
            onSelect = { estimate ->
                selectedDeviceAddresses = setOf(estimate.address)
                revealedReportedAddress = null
                historyShownAddresses = emptySet()
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
        val estimate = displayedEstimates.firstOrNull { it.address == address }
        val reads = readsByAddress[address].orEmpty()
        val reported = estimate?.reportedPosition
        UnitReadDataDialog(
            label = estimate?.label ?: address,
            address = address,
            rssi = estimate?.rssi,
            radiusMeters = estimate?.radiusMeters,
            reads = reads,
            reportedPosition = reported,
            historyShown = address.uppercase() in historyShownAddresses,
            historyPointCount = historyPointCounts[address.uppercase()] ?: 0,
            onToggleHistory = {
                toggleHistory(address)
                // Close so the map — now showing or hiding this unit's trail — is visible, the
                // same act-then-close flow the Reported GPS row uses.
                readDetailAddress = null
            },
            // Tapping the coords here is the one place the reported-GPS line is revealed:
            // recenter on the reported fix and close this dialog so the map is visible.
            onReportedCoordsClick = reported?.let { point ->
                {
                    revealedReportedAddress = address
                    focusRequest = MapFocusRequest(
                        point = point,
                        zoom = null,
                        nonce = (focusRequest?.nonce ?: 0) + 1,
                    )
                    readDetailAddress = null
                }
            },
            onDismiss = { readDetailAddress = null },
        )
    }
}

@Composable
private fun MapBottomPanel(
    state: BleSessionState,
    locationGranted: Boolean,
    selectedEstimates: List<DeviceMapEstimate>,
    profilesByAddress: Map<String, DeviceProfile>,
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
                        EstimateDetail(
                            estimate = estimate,
                            evidence = deviceEvidenceLine(
                                estimate.evidence,
                                estimate.label,
                                profilesByAddress[estimate.address],
                            ),
                            vulnerability = profilesByAddress[estimate.address]?.vulnerability,
                            onClick = { onOpenReadData(estimate.address) },
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
    profilesByAddress: Map<String, DeviceProfile>,
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
                            val profile = profilesByAddress[estimate.address]
                            Surface(
                                onClick = { onSelect(estimate) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("map-search-result:${estimate.address}"),
                                tonalElevation = 1.dp,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Same status marker as the map: 🛡️/⚠️ once proven, else the
                                    // neutral dot that stands for a never-connected unit.
                                    Box(
                                        modifier = Modifier.size(24.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val glyph = profile?.vulnerability?.statusGlyph()
                                        if (glyph != null) {
                                            Text(glyph, style = MaterialTheme.typography.titleMedium)
                                        } else {
                                            Text(
                                                "•",
                                                style = MaterialTheme.typography.titleLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            estimate.label,
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        Text(
                                            deviceEvidenceLine(
                                                estimate.evidence,
                                                estimate.label,
                                                profile,
                                            ),
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

/**
 * The device-card evidence line. Once a connection has profiled the unit, the line states what was
 * proven — vulnerable/patched plus the authoritative unit model. Otherwise it shows the scan-time
 * classification, upgrading the legacy generic "DR/QT" wording to the actual family (recoverable
 * from the device name) so pre-existing and imported records read specifically too.
 */
/**
 * The status glyph for a unit's proven authentication vulnerability, shown on the map marker and in
 * the search list. Null when the unit has never been connected, since a scan cannot reveal it — the
 * caller keeps the plain dot in that case.
 */
internal fun KarrVulnerabilityStatus.statusGlyph(): String? = when (this) {
    KarrVulnerabilityStatus.VULNERABLE -> "⚠️"
    KarrVulnerabilityStatus.PATCHED -> "🛡️"
    KarrVulnerabilityStatus.UNKNOWN -> null
}

internal fun deviceEvidenceLine(
    scanEvidence: String,
    deviceName: String?,
    profile: DeviceProfile?,
): String {
    val baseEvidence = familySpecificScanEvidence(scanEvidence, deviceName)
    if (profile == null) return baseEvidence
    val modelName = profile.unitModelCode
        ?.let(KarrUnitModel::fromCode)
        ?.takeIf(KarrUnitModel::isRecognized)
        ?.displayName
    return when (profile.vulnerability) {
        KarrVulnerabilityStatus.VULNERABLE ->
            if (modelName != null) "Vulnerable · $modelName" else "Vulnerable KARR unit"
        KarrVulnerabilityStatus.PATCHED ->
            if (modelName != null) "Patched · $modelName" else "Patched KARR unit"
        KarrVulnerabilityStatus.UNKNOWN -> baseEvidence
    }
}

// Older records (and imports made before this) stored the generic "Recovered DR/QT serial format".
// The family is in the device name (e.g. "DR D029A54D"), so upgrade just that one legacy string in
// place at display time — no re-scan or database migration needed. Other classifications, and the
// already-specific strings new scans produce, pass through untouched.
private const val LEGACY_DR_QT_SERIAL_EVIDENCE = "Recovered DR/QT serial format"

private fun familySpecificScanEvidence(scanEvidence: String, deviceName: String?): String {
    if (scanEvidence != LEGACY_DR_QT_SERIAL_EVIDENCE) return scanEvidence
    val family = deviceName?.trim()?.uppercase()?.take(2)
    return if (family == "DR" || family == "QT") "Recovered $family serial format" else scanEvidence
}

// Mirrors a search-list row: a leading status glyph (🛡️/⚠️, or the neutral dot for a
// never-connected unit) beside the name, evidence, and last-seen. The MAC, RSSI, and radius now
// live one level down in the unit-data dialog, which the whole row opens.
@Composable
private fun EstimateDetail(
    estimate: DeviceMapEstimate,
    evidence: String,
    vulnerability: KarrVulnerabilityStatus?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("map-selected-estimate:${estimate.address}")
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            val glyph = vulnerability?.statusGlyph()
            if (glyph != null) {
                Text(glyph, style = MaterialTheme.typography.titleMedium)
            } else {
                Text(
                    "•",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                estimate.label,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                evidence,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Last seen ${formatLastSeen(estimate.lastSeenUtc)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
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
    rssi: Int?,
    radiusMeters: Double?,
    reads: List<DeviceReadRecord>,
    reportedPosition: GeoPoint?,
    historyShown: Boolean,
    historyPointCount: Int,
    onToggleHistory: () -> Unit,
    onReportedCoordsClick: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val ordered = remember(reads) { reads.sortedByDescending(DeviceReadRecord::capturedUtc) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // MAC, plus the RSSI and radius that used to sit on the map card — relocated here to
                // keep the card itself compact.
                Text(
                    buildString {
                        append(address)
                        if (rssi != null) append("  RSSI $rssi dBm")
                        if (radiusMeters != null) append("  radius ${radiusMeters.toInt()} m")
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                // Reported GPS lives here (one level below the map card): tapping it draws the
                // line from the RSSI pin to this stale fix, recenters, and closes the dialog.
                reportedPosition?.let { reported ->
                    Text(
                        "Reported GPS ${
                            String.format(
                                Locale.US,
                                "%.5f, %.5f",
                                reported.latitude,
                                reported.longitude,
                            )
                        } ›",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .testTag("map-reported-gps:$address")
                            .then(
                                if (onReportedCoordsClick != null) {
                                    Modifier.clickable(onClick = onReportedCoordsClick)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
                // Location history lives here, one level below the map card and beside Reported GPS:
                // draws every sighting of this unit as a trail on the map. Only offered when the unit
                // was seen at more than one point. The call site closes the dialog on tap so the
                // trail is visible; the toggle label reflects the current on/off state.
                if (historyPointCount > 1) {
                    Text(
                        if (historyShown) {
                            "Hide location history"
                        } else {
                            "Show location history ($historyPointCount) ›"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .testTag("map-toggle-history:$address")
                            .clickable(onClick = onToggleHistory),
                    )
                }
                if (ordered.isEmpty()) {
                    Text(
                        "No unit reads stored yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
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
                                // The clickable Reported GPS row above already shows this fix, so
                                // drop the decoder's redundant "Position:" line when that row is
                                // present; keep it as a fallback when there is no clickable row.
                                record.lines
                                    .filterNot { reportedPosition != null && it.startsWith("Position:") }
                                    .forEach { line ->
                                        Text(
                                            line,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                vinFromLines(record.lines)?.let { vin ->
                                    VinIdentityContent(vin = vin)
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

/** Returns the newest valid unit-reported coordinate retained in decoded READ_STATE lines. */
internal fun reportedPositionFromReads(reads: List<DeviceReadRecord>): GeoPoint? = reads
    .sortedByDescending(DeviceReadRecord::capturedUtc)
    .asSequence()
    .flatMap { record -> record.lines.asSequence() }
    .mapNotNull(::reportedPositionFromLine)
    .firstOrNull()

private fun reportedPositionFromLine(line: String): GeoPoint? {
    val match = REPORTED_POSITION_REGEX.matchEntire(line.trim()) ?: return null
    val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
    val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return GeoPoint(latitude, longitude)
}

@Composable
private fun KarrMapView(
    estimates: List<DeviceMapEstimate>,
    phoneLocation: PhoneLocation?,
    focusRequest: MapFocusRequest?,
    mapOrientationDegrees: Float,
    revealedReportedAddress: String?,
    vulnerabilityByAddress: Map<String, KarrVulnerabilityStatus>,
    historyTrackByAddress: Map<String, List<GeoPoint>>,
    onEstimatesSelected: (List<DeviceMapEstimate>, Boolean) -> Unit,
    onReportedPositionTapped: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val minTouchRadiusPx = with(density) { MIN_TOUCH_TARGET_RADIUS.toPx() }
    val clusterCellPx = with(density) { CLUSTER_CELL_SIZE.toPx() }
    val clusterRadiusPx = with(density) { CLUSTER_MARKER_RADIUS.toPx() }
    val statusGlyphPx = with(density) { MARKER_STATUS_GLYPH_SIZE.toPx() }
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
            currentHost.overlay.statusGlyphPx = statusGlyphPx
            currentHost.overlay.vulnerabilityByAddress = vulnerabilityByAddress
            currentHost.overlay.historyTrackByAddress = historyTrackByAddress
            currentHost.overlay.revealedReportedAddress = revealedReportedAddress
            currentHost.overlay.onEstimatesTapped = onEstimatesSelected
            currentHost.overlay.onReportedPositionTapped = onReportedPositionTapped
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
        set(value) {
            if (field != value) {
                field = value
                groups = null
            }
        }
    var phoneLocation: PhoneLocation? = null
    var minTouchRadiusPx: Float = 0f
    var darkTheme: Boolean = false
    // Markers whose screen positions land in the same cell are combined into one numbered
    // bubble, so nearby devices merge together at a wide zoom and split apart as you zoom in
    // and their screen-space distance grows past a cell's width — the same grid-bucketing
    // approach most map SDKs' marker clustering uses, kept to a single O(n) pass per draw.
    var clusterCellPx: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                groups = null
            }
        }
    var clusterRadiusPx: Float = 0f
    var onEstimatesTapped: ((List<DeviceMapEstimate>, Boolean) -> Unit)? = null
    var onReportedPositionTapped: ((GeoPoint) -> Unit)? = null
    // Address of the unit whose reported-GPS line + marker are currently revealed; null draws none.
    var revealedReportedAddress: String? = null
    // Proven auth status per address (from connection profiles). A singleton marker draws its status
    // glyph (🛡️/⚠️) instead of the plain dot; addresses that were never connected stay a plain dot.
    var vulnerabilityByAddress: Map<String, KarrVulnerabilityStatus> = emptyMap()
    var statusGlyphPx: Float = 0f
    // Time-ordered sighting points per address whose location history is currently revealed; each is
    // drawn as a breadcrumb trail (polyline + a dot per sighting) so the user can see everywhere a
    // unit was seen, not just its single weighted pin. Empty draws none.
    var historyTrackByAddress: Map<String, List<GeoPoint>> = emptyMap()

    // Which cell a marker lands in depends on the zoom, and osmdroid drives the zoom continuously
    // through a pinch (and through an animated zoom), so re-bucketing every frame makes markers hop
    // between cells mid-gesture: bubble counts tick up and down and groups merge and split under the
    // fingers. The grouping is therefore pinned to the zoom it was computed at and only re-formed
    // once the zoom stops moving, so the groups settle once, on release, instead of per frame.
    private var groupingZoom: Double? = null
    private var groups: List<List<DeviceMapEstimate>>? = null
    private var settleCheck: Runnable? = null
    private var settleCheckZoom: Double = 0.0

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
    // Draws the vulnerable/patched emoji at a singleton marker's center. Color emoji ignore the
    // paint color and render their own glyph, so no color is set here.
    private val statusGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    // Dashed line + hollow marker for the unit's self-reported (stale) GPS, distinct from the solid
    // RSSI pin so it reads as "recently reported here", not "currently here".
    private val reportedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val reportedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val reportedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    // Solid breadcrumb trail (distinct from the reported-GPS dashed line) connecting a unit's past
    // sightings in time order, with a dot at each.
    private val historyLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val historyDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
        if (statusGlyphPx > 0f) statusGlyphPaint.textSize = statusGlyphPx
        phoneLocation?.let { location ->
            val point = projection.toPixels(
                GeoPoint(location.latitude, location.longitude),
                Point(),
            )
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 10f, phonePaint)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 18f, guidePaint)
        }
        if (historyTrackByAddress.isNotEmpty()) {
            // Drawn before the markers so the current pins and clusters sit on top of the trail.
            drawHistoryTracks(canvas, projection)
        }
        val reportedColor = (if (darkTheme) REPORTED_MARKER_DARK else REPORTED_MARKER_LIGHT).toArgb()
        clusterEstimates(mapView, projection).forEach { cluster ->
            // Draw the revealed unit's reported-GPS line + marker whether it is a lone pin or has
            // merged into a numbered cluster. The line often points far away, so following it means
            // zooming out — exactly when the unit joins a cluster — and the line must survive that.
            cluster.members
                .firstOrNull { it.first.address == revealedReportedAddress }
                ?.let { member ->
                    drawReportedAnnotation(canvas, projection, member.first, member.second, reportedColor)
                }
            val singleton = cluster.members.singleOrNull()
            if (singleton != null) {
                drawSingleEstimate(canvas, projection, singleton.first, singleton.second)
            } else {
                drawCluster(canvas, cluster)
            }
        }
    }

    // Draws each revealed unit's location-history trail: a polyline through its past sightings in
    // time order with a dot at each, so the spread the pin is estimated from is visible.
    private fun drawHistoryTracks(canvas: Canvas, projection: Projection) {
        val trailColor = (if (darkTheme) MARKER_DOT_DARK else MARKER_DOT_LIGHT).toArgb()
        historyLinePaint.color = trailColor.withAlpha(150)
        historyDotPaint.color = trailColor.withAlpha(220)
        historyTrackByAddress.values.forEach { points ->
            var previous: Point? = null
            points.forEach { geoPoint ->
                val pixel = projection.toPixels(geoPoint, Point())
                previous?.let { start ->
                    canvas.drawLine(
                        start.x.toFloat(),
                        start.y.toFloat(),
                        pixel.x.toFloat(),
                        pixel.y.toFloat(),
                        historyLinePaint,
                    )
                }
                canvas.drawCircle(
                    pixel.x.toFloat(),
                    pixel.y.toFloat(),
                    HISTORY_DOT_RADIUS_PX,
                    historyDotPaint,
                )
                previous = Point(pixel.x, pixel.y)
            }
        }
    }

    // Draws the dashed line from the RSSI pin to the unit's self-reported (stale) GPS and a hollow
    // marker there, so the reported location reads as a separate "recently here" annotation.
    private fun drawReportedAnnotation(
        canvas: Canvas,
        projection: Projection,
        estimate: DeviceMapEstimate,
        rssiPoint: Point,
        color: Int,
    ) {
        val reported = estimate.reportedPosition ?: return
        val reportedPoint = projection.toPixels(reported, Point())
        reportedLinePaint.color = color.withAlpha(170)
        canvas.drawLine(
            rssiPoint.x.toFloat(),
            rssiPoint.y.toFloat(),
            reportedPoint.x.toFloat(),
            reportedPoint.y.toFloat(),
            reportedLinePaint,
        )
        reportedFillPaint.color = color.withAlpha(if (darkTheme) 80 else 60)
        reportedStrokePaint.color = color.withAlpha(235)
        canvas.drawCircle(
            reportedPoint.x.toFloat(),
            reportedPoint.y.toFloat(),
            REPORTED_MARKER_RADIUS_PX,
            reportedFillPaint,
        )
        canvas.drawCircle(
            reportedPoint.x.toFloat(),
            reportedPoint.y.toFloat(),
            REPORTED_MARKER_RADIUS_PX,
            reportedStrokePaint,
        )
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
        val glyph = vulnerabilityByAddress[estimate.address]?.statusGlyph()
        if (glyph != null && statusGlyphPx > 0f) {
            // Center the emoji on its cap height the same way the cluster number is centered.
            val baseline =
                centerPoint.y - (statusGlyphPaint.descent() + statusGlyphPaint.ascent()) / 2f
            canvas.drawText(glyph, centerPoint.x.toFloat(), baseline, statusGlyphPaint)
        } else {
            canvas.drawCircle(centerPoint.x.toFloat(), centerPoint.y.toFloat(), 6f, markerPaint)
        }
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

    // Groups at the pinned zoom rather than the map's live one, which holds the grouping still for
    // the length of a pinch or a zoom animation. Only the grouping is held: members are re-projected
    // every frame, so a held bubble stays glued to its markers as the map scales rather than hanging
    // at a stale screen position.
    private fun clusterEstimates(mapView: MapView, projection: Projection): List<MapCluster> {
        if (estimates.isEmpty()) return emptyList()
        val liveZoom = mapView.zoomLevelDouble
        val pinnedZoom = groupingZoom
        val zoom: Double
        if (pinnedZoom == null) {
            zoom = liveZoom
            groupingZoom = liveZoom
        } else {
            zoom = pinnedZoom
            if (pinnedZoom != liveZoom) {
                // The zoom is in flight: keep the groups it started with and watch for it to stop.
                scheduleSettleCheck(mapView, liveZoom)
            }
        }
        val members = groups ?: groupEstimates(zoom).also { groups = it }
        return members.map { group ->
            MapCluster(
                group.map { estimate ->
                    estimate to projection.toPixels(
                        GeoPoint(estimate.latitude, estimate.longitude),
                        Point(),
                    )
                },
            )
        }
    }

    // Ported from MeshCore's map clustering (github.com/zjs81/meshcore-open,
    // lib/screens/map_screen.dart): individual markers stay individual above
    // CLUSTER_OFF_ZOOM no matter how close together they are, and below it, the desired
    // on-screen cell size is converted into degrees at the given zoom (assuming standard
    // 256px web-mercator tiles) so bucketing is by lat/lon rather than by re-projecting every
    // marker to screen pixels just to find its cell.
    private fun groupEstimates(zoom: Double): List<List<DeviceMapEstimate>> {
        if (zoom >= CLUSTER_OFF_ZOOM || clusterCellPx <= 0f) return estimates.map { listOf(it) }
        val cellDegrees = 360.0 / (256.0 * 2.0.pow(zoom)) * clusterCellPx
        val buckets = LinkedHashMap<Pair<Int, Int>, MutableList<DeviceMapEstimate>>()
        estimates.forEach { estimate ->
            val cellKey = floor(estimate.latitude / cellDegrees).toInt() to
                floor(estimate.longitude / cellDegrees).toInt()
            buckets.getOrPut(cellKey) { mutableListOf() }.add(estimate)
        }
        return buckets.values.toList()
    }

    // Waits for the zoom to hold still rather than hooking the gesture, so a pinch, a double-tap
    // zoom and an animated zoomToBoundingBox all release the same way. Self-reposting, so the map
    // re-groups after the last frame of a zoom without needing another draw to notice.
    private fun scheduleSettleCheck(mapView: MapView, liveZoom: Double) {
        if (settleCheck != null) return
        settleCheckZoom = liveZoom
        val check = Runnable {
            settleCheck = null
            val settledZoom = mapView.zoomLevelDouble
            if (settledZoom != settleCheckZoom || mapView.isAnimating()) {
                scheduleSettleCheck(mapView, settledZoom)
            } else {
                groupingZoom = null
                groups = null
                mapView.invalidate()
            }
        }
        settleCheck = check
        mapView.postDelayed(check, CLUSTER_SETTLE_MILLIS)
    }

    override fun onDetach(mapView: MapView) {
        settleCheck?.let(mapView::removeCallbacks)
        settleCheck = null
        super.onDetach(mapView)
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val projection = mapView.projection
        val clusters = clusterEstimates(mapView, projection)

        // Tapping the revealed unit's reported-GPS marker re-centers the map on it. The marker is
        // drawn whether the unit is a lone pin or inside a numbered cluster, so hit-test it in
        // either case. Checked first so the small annotation marker wins over a circle behind it.
        val reportedHit = clusters
            .asSequence()
            .mapNotNull { cluster ->
                cluster.members.firstOrNull { it.first.address == revealedReportedAddress }
            }
            .mapNotNull { it.first.reportedPosition }
            .firstOrNull { reported ->
                val point = projection.toPixels(reported, Point())
                val radius = max(REPORTED_MARKER_RADIUS_PX, minTouchRadiusPx)
                val dx = e.x - point.x
                val dy = e.y - point.y
                (dx * dx + dy * dy) <= radius * radius
            }
        if (reportedHit != null) {
            onReportedPositionTapped?.invoke(reportedHit)
            return true
        }

        // A numbered grid bubble (multi-member cluster, only present when zoomed out) is a
        // tap-to-expand target: return its members and signal the caller to zoom in.
        val bubble = clusters.firstOrNull { cluster ->
            cluster.members.size > 1 && run {
                val radius = max(clusterRadiusPx, minTouchRadiusPx)
                val dx = e.x - cluster.centerX
                val dy = e.y - cluster.centerY
                (dx * dx + dy * dy) <= radius * radius
            }
        }
        if (bubble != null) {
            onEstimatesTapped?.invoke(bubble.members.map { it.first }, true)
            return true
        }

        // Otherwise select EVERY individual circle under the tap, so overlapping circles return
        // results for all of them rather than only the first one hit-tested.
        val hits = clusters
            .filter { it.members.size == 1 }
            .filter { cluster ->
                val member = cluster.members.first()
                val touchRadiusPx =
                    max(projectedRadiusPx(projection, member.first, member.second), minTouchRadiusPx)
                val dx = e.x - cluster.centerX
                val dy = e.y - cluster.centerY
                (dx * dx + dy * dy) <= touchRadiusPx * touchRadiusPx
            }
            .map { it.members.first().first }
        onEstimatesTapped?.invoke(hits, false)
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
    // The unit's most recent self-reported GPS (stale last-known fix), shown as a secondary
    // annotation connected to the RSSI position by a line. Null when the unit never reported one.
    val reportedPosition: GeoPoint? = null,
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

internal class FrameworkLocationTracker(
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

// Pure conversion (no persistence): builds an in-memory observation for a KARR candidate, or null
// for a non-candidate. Persistence is owned by the app-level recorder in KarrKeyApp so sightings
// are stored during any scan on any tab; the Map screen uses this only to update its live display.
internal fun BleScanObservation.toLocalMapObservation(
    location: PhoneLocation,
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
    return LocalMapObservation(
        id = UUID.randomUUID().toString(),
        observedUtc = Instant.ofEpochMilli(observedUtcMillis),
        deviceAddress = device.address.uppercase(),
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
        // A unit's self-reported GPS (extended READ_STATE) proved on hardware to be a stale
        // last-known fix — static across reads and ~1 km from the unit's real position — so ignore
        // those observations and locate from phone-side RSSI, which reflects where we actually saw
        // the unit. Fall back to the raw group only if a unit somehow has no phone-side sighting.
        val sorted = group
            .filterNot { it.locationProvider == VEHICLE_GPS_PROVIDER }
            .ifEmpty { group }
            .sortedByDescending(LocalMapObservation::observedUtc)
        // Weight recent sightings far more than old ones. Without this the pin sits at the
        // signal-weighted centroid of EVERY past sighting, so while driving — past a unit, or with
        // your own unit aboard — each new scan only nudges the pin toward you instead of jumping to
        // where the unit was just seen ("drifts toward but never reaches"). Age is measured from the
        // group's freshest sighting, not "now", so a unit not seen for a while keeps its last fix
        // instead of decaying to nothing.
        val latestUtcMillis = sorted.first().observedUtc.toEpochMilli()
        val weighted = sorted.fold(WeightedLocation()) { accumulator, observation ->
            val confidence = max(observation.confidenceRadiusMeters, 1.0)
            val signalWeight = (observation.rssi + 100).coerceIn(1, 80).toDouble()
            val ageSeconds = (latestUtcMillis - observation.observedUtc.toEpochMilli()) / 1000.0
            val recencyWeight = exp(-ageSeconds / RECENCY_DECAY_SECONDS)
            val weight = signalWeight * recencyWeight / (confidence * confidence)
            accumulator.add(observation, weight)
        }
        val latest = sorted.first()
        // Circle radius reflects how far the unit is *now*, not the tightest bound ever recorded.
        // It is a recency-weighted mean of the per-sighting confidence radii (the same decay the
        // position uses), so a weak, distant fresh signal draws a wide circle — an area that can
        // still contain the unit — and it shrinks as newer, stronger sightings arrive while you
        // close in. Using `min` instead let one close pass collapse the circle to the floor forever.
        var radiusWeightSum = 0.0
        var radiusValueSum = 0.0
        sorted.forEach { observation ->
            val ageSeconds = (latestUtcMillis - observation.observedUtc.toEpochMilli()) / 1000.0
            val recencyWeight = exp(-ageSeconds / RECENCY_DECAY_SECONDS)
            radiusWeightSum += recencyWeight
            radiusValueSum += observation.confidenceRadiusMeters * recencyWeight
        }
        val currentRadiusMeters = if (radiusWeightSum > 0.0) {
            radiusValueSum / radiusWeightSum
        } else {
            latest.confidenceRadiusMeters
        }
        // The unit's most recent self-reported GPS (if any) is surfaced as a secondary annotation,
        // not the plotted position — it's a stale last-known fix, useful as a recently-visited spot.
        val reportedPosition = group
            .filter { it.locationProvider == VEHICLE_GPS_PROVIDER }
            .maxByOrNull(LocalMapObservation::observedUtc)
            ?.let { GeoPoint(it.phoneLatitude, it.phoneLongitude) }
        DeviceMapEstimate(
            address = normalizedAddress,
            label = latest.deviceName ?: "Unnamed KARR candidate",
            tier = latest.classificationTier,
            evidence = latest.classificationEvidence,
            latitude = weighted.latitude,
            longitude = weighted.longitude,
            radiusMeters = currentRadiusMeters.coerceAtLeast(MIN_CONFIDENCE_RADIUS_METERS),
            rssi = latest.rssi,
            lastSeenUtc = latest.observedUtc,
            reportedPosition = reportedPosition,
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

internal fun Context.hasForegroundLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

internal fun PhoneLocation.isFresh(): Boolean =
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
// Violet, distinct from the teal/amber tier colors, for a unit's self-reported (stale) GPS marker
// and its connecting line.
private val REPORTED_MARKER_LIGHT = Color(0xff7c3aed)
private val REPORTED_MARKER_DARK = Color(0xffa78bfa)
private const val REPORTED_MARKER_RADIUS_PX = 10f
private const val HISTORY_DOT_RADIUS_PX = 5f
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
// Recency time constant for the plotted position: a sighting's weight decays by e^-1 every
// RECENCY_DECAY_SECONDS of age relative to the group's freshest sighting, so the pin follows where a
// unit was most recently seen rather than the centroid of the whole drive. Larger = smoother but
// laggier while moving; smaller = tracks tighter but jumpier (roughly RECENCY_DECAY_SECONDS × speed
// of lag behind a co-moving unit).
private const val RECENCY_DECAY_SECONDS = 10.0
private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
private val REPORTED_POSITION_REGEX = Regex(
    "^Position:\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*,\\s*" +
        "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))$",
)
private const val MAP_ESTIMATE_REVEAL_BLOCK_SIZE = 30
private val MIN_TOUCH_TARGET_RADIUS = 24.dp
private val CLUSTER_CELL_SIZE = 64.dp
// Above this zoom, markers always render individually — kept close to OSM_MAX_ZOOM (19) so
// only the closest zoom step is exempt; clustering is allowed at every zoom below that,
// unlike MeshCore's own, much wider-zoomed-out _clusterOffZoom of 12.5.
private const val CLUSTER_OFF_ZOOM = 18.0
private const val CLUSTER_FOCUS_PADDING_PX = 96
// How long the zoom must hold still before the groups re-form. Comfortably longer than the gap
// between frames of a pinch or a zoom animation, short enough to read as part of letting go.
private const val CLUSTER_SETTLE_MILLIS = 120L
private val CLUSTER_MARKER_RADIUS = 22.dp
private const val CLUSTER_TEXT_SIZE_FACTOR = 0.9f
// Size of the 🛡️/⚠️ status emoji drawn at a singleton marker's center, in place of the plain dot.
private val MARKER_STATUS_GLYPH_SIZE = 20.dp
private var osmMapConfigured = false
