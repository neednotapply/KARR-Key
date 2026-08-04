package com.karr.key.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.MotionEvent
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import com.karr.key.data.LocalMapHistoryStore
import com.karr.key.data.LocalMapObservation
import com.karr.key.data.MapCandidateTier
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

@Composable
internal fun MapScreen(
    controller: BleController,
    historyStore: LocalMapHistoryStore,
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
    var phoneLocation by remember { mutableStateOf<PhoneLocation?>(null) }
    val currentLocation by rememberUpdatedState(phoneLocation)
    var locationGranted by remember {
        mutableStateOf(context.hasForegroundLocationPermission())
    }
    val locationTracker = remember(context) {
        FrameworkLocationTracker(context) { location -> phoneLocation = location }
    }
    val estimates = remember(history) { estimateDeviceLocations(history) }
    var selectedDeviceAddresses by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectedEstimates = estimates.filter { it.address in selectedDeviceAddresses }
    var showDeviceSearch by remember { mutableStateOf(false) }
    var focusRequest by remember { mutableStateOf<MapFocusRequest?>(null) }

    LaunchedEffect(historyStore) {
        history = withContext(Dispatchers.IO) { historyStore.recent() }
    }

    DisposableEffect(locationGranted) {
        if (locationGranted) {
            locationTracker.start()
        }
        onDispose { locationTracker.stop() }
    }

    LaunchedEffect(controller, locationGranted) {
        controller.scanObservations.collect { observation ->
            val location = currentLocation ?: return@collect
            if (!location.isFresh()) return@collect
            val stored = observation.toLocalMapObservation(location, historyStore)
                ?: return@collect
            history = listOf(stored) + history
        }
    }

    Box(modifier.fillMaxSize()) {
        KarrMapView(
            estimates = estimates,
            phoneLocation = phoneLocation,
            focusRequest = focusRequest,
            onEstimatesSelected = { hits ->
                selectedDeviceAddresses = hits.map { it.address }.toSet()
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("map-view"),
        )
        MapBottomPanel(
            state = state,
            locationGranted = locationGranted,
            selectedEstimates = selectedEstimates,
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
                        zoom = null,
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
}

@Composable
private fun MapBottomPanel(
    state: BleSessionState,
    locationGranted: Boolean,
    selectedEstimates: List<DeviceMapEstimate>,
    onEnableLocation: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onSearchDevices: () -> Unit,
    onRecenter: () -> Unit,
    canRecenter: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("map-bottom-panel"),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selectedEstimates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    selectedEstimates.forEach { estimate ->
                        EstimateDetail(estimate)
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
                Button(
                    onClick = if (state is BleSessionState.Scanning) onStopScan else onScan,
                    enabled = state is BleSessionState.Scanning || state.canMapScan(),
                ) {
                    Text(if (state is BleSessionState.Scanning) "Stop" else "Scan")
                }
                OutlinedButton(
                    onClick = onSearchDevices,
                    modifier = Modifier.testTag("map-search-devices"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Search",
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                OutlinedButton(
                    onClick = onRecenter,
                    enabled = canRecenter,
                    modifier = Modifier.testTag("map-recenter"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_recenter),
                        contentDescription = "Recenter on my location",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = "© OpenStreetMap contributors",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("map-selected-estimate"),
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
    }
}

private fun formatLastSeen(instant: Instant): String =
    LAST_SEEN_FORMAT.format(instant)

@Composable
private fun KarrMapView(
    estimates: List<DeviceMapEstimate>,
    phoneLocation: PhoneLocation?,
    focusRequest: MapFocusRequest?,
    onEstimatesSelected: (List<DeviceMapEstimate>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val minTouchRadiusPx = with(density) { MIN_TOUCH_TARGET_RADIUS.toPx() }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            configureOsmMap(viewContext)
            val mapView = MapView(viewContext).apply {
                setBackgroundColor(Color(0xffe5e7eb).toArgb())
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
            currentHost.overlay.estimates = estimates
            currentHost.overlay.phoneLocation = phoneLocation
            currentHost.overlay.minTouchRadiusPx = minTouchRadiusPx
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
                    request.zoom?.let(mapView.controller::setZoom)
                    mapView.controller.setCenter(request.point)
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
        color = Color(0xff111827).toArgb()
    }
    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color(0xff2563eb).toArgb()
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color(0xff64748b).toArgb().withAlpha(90)
        strokeWidth = 1.5f
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        phoneLocation?.let { location ->
            val point = projection.toPixels(
                GeoPoint(location.latitude, location.longitude),
                Point(),
            )
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 10f, phonePaint)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 18f, guidePaint)
        }
        estimates.forEach { estimate ->
            val centerPoint = projection.toPixels(
                GeoPoint(estimate.latitude, estimate.longitude),
                Point(),
            )
            val radiusPx = projectedRadiusPx(projection, estimate, centerPoint)
            val color = if (estimate.tier == MapCandidateTier.CONFIRMED_FORMAT) {
                Color(0xff0f766e)
            } else {
                Color(0xffb45309)
            }.toArgb()
            fillPaint.color = color.withAlpha(48)
            strokePaint.color = color.withAlpha(190)
            canvas.drawCircle(
                centerPoint.x.toFloat(),
                centerPoint.y.toFloat(),
                radiusPx,
                fillPaint,
            )
            canvas.drawCircle(
                centerPoint.x.toFloat(),
                centerPoint.y.toFloat(),
                radiusPx,
                strokePaint,
            )
            canvas.drawCircle(
                centerPoint.x.toFloat(),
                centerPoint.y.toFloat(),
                6f,
                markerPaint,
            )
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val projection = mapView.projection
        val hits = estimates.filter { estimate ->
            val centerPoint = projection.toPixels(
                GeoPoint(estimate.latitude, estimate.longitude),
                Point(),
            )
            val radiusPx = projectedRadiusPx(projection, estimate, centerPoint)
            val touchRadiusPx = max(radiusPx, minTouchRadiusPx)
            val dx = e.x - centerPoint.x
            val dy = e.y - centerPoint.y
            (dx * dx + dy * dy) <= touchRadiusPx * touchRadiusPx
        }
        onEstimatesTapped?.invoke(hits)
        return hits.isNotEmpty()
    }
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
// A null zoom leaves the map's current zoom level untouched (used by recenter-on-me).
private data class MapFocusRequest(val point: GeoPoint, val zoom: Double?, val nonce: Int)

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
    "KARR-Key/1.0.2 (+https://github.com/neednotapply/KARR-Key)"
private const val OSM_TILE_CACHE_BYTES = 50L * 1024L * 1024L
private const val OSM_TILE_CACHE_TRIM_BYTES = 40L * 1024L * 1024L
private const val OSM_MIN_ZOOM = 0.0
private const val OSM_MAX_ZOOM = 19.0
private const val DEFAULT_ZOOM = 17.0
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
private val MIN_TOUCH_TARGET_RADIUS = 24.dp
private var osmMapConfigured = false
