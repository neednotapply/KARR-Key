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
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.karr.key.ble.BleController
import com.karr.key.ble.BleScanObservation
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.KarrCandidateTier
import com.karr.key.data.LocalMapHistoryStore
import com.karr.key.data.LocalMapObservation
import com.karr.key.data.MapCandidateTier
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

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
    var history by remember(historyStore) { mutableStateOf(historyStore.recent()) }
    var phoneLocation by remember { mutableStateOf<PhoneLocation?>(null) }
    val currentLocation by rememberUpdatedState(phoneLocation)
    var locationGranted by remember {
        mutableStateOf(context.hasForegroundLocationPermission())
    }
    val locationTracker = remember(context) {
        FrameworkLocationTracker(context) { location -> phoneLocation = location }
    }
    val estimates = remember(history) { estimateDeviceLocations(history) }

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
            modifier = Modifier
                .fillMaxSize()
                .testTag("map-view"),
        )
        MapControls(
            state = state,
            locationGranted = locationGranted,
            phoneLocation = phoneLocation,
            estimateCount = estimates.size,
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
            onClear = {
                scope.launch {
                    withContext(Dispatchers.IO) { historyStore.clear() }
                    history = emptyList()
                    snackbar.showSnackbar("Map history cleared.")
                }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp),
        )
        MapEstimateList(
            estimates = estimates,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }
}

@Composable
private fun MapControls(
    state: BleSessionState,
    locationGranted: Boolean,
    phoneLocation: PhoneLocation?,
    estimateCount: Int,
    onEnableLocation: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Map",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = mapStatus(locationGranted, phoneLocation, estimateCount),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "\u00a9 OpenStreetMap contributors",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onEnableLocation,
                    enabled = !locationGranted,
                ) {
                    Text("Enable location")
                }
                Button(
                    onClick = onScan,
                    enabled = state.canMapScan(),
                ) {
                    Text("Scan")
                }
                OutlinedButton(
                    onClick = onStopScan,
                    enabled = state is BleSessionState.Scanning,
                ) {
                    Text("Stop")
                }
                OutlinedButton(
                    onClick = onClear,
                    enabled = estimateCount > 0,
                ) {
                    Text("Clear map history")
                }
            }
        }
    }
}

@Composable
private fun MapEstimateList(
    estimates: List<DeviceMapEstimate>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (estimates.isEmpty()) {
                item {
                    Text(
                        "No mapped KARR candidates yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                items(estimates, key = { it.address }) { estimate ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    }
                }
            }
        }
    }
}

@Composable
private fun KarrMapView(
    estimates: List<DeviceMapEstimate>,
    phoneLocation: PhoneLocation?,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            configureOsmMap(viewContext)
            val frame = FrameLayout(viewContext)
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
            val overlay = ConfidenceOverlayView(viewContext)
            val createdHost = MapHost(
                mapView = mapView,
                overlay = overlay,
                lifecycle = lifecycleOwner.lifecycle,
            )
            overlay.map = mapView
            mapView.addMapListener(
                object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        overlay.invalidate()
                        return false
                    }

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        overlay.invalidate()
                        return false
                    }
                },
            )
            frame.tag = createdHost
            frame.addView(
                mapView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            frame.addView(
                overlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            phoneLocation?.let { location ->
                mapView.controller.setCenter(
                    GeoPoint(location.latitude, location.longitude),
                )
                createdHost.centeredOnce = true
            }
            frame
        },
        update = { frame ->
            val currentHost = frame.tag as MapHost
            currentHost.overlay.estimates = estimates
            currentHost.overlay.phoneLocation = phoneLocation
            if (!currentHost.centeredOnce) {
                val location = phoneLocation
                if (location != null) {
                    currentHost.mapView.controller.setZoom(DEFAULT_ZOOM)
                    currentHost.mapView.controller.setCenter(
                        GeoPoint(location.latitude, location.longitude),
                    )
                    currentHost.centeredOnce = true
                }
            }
            currentHost.mapView.invalidate()
            currentHost.overlay.invalidate()
        },
        onRelease = { frame ->
            (frame.tag as MapHost).release()
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
    val overlay: ConfidenceOverlayView,
    private val lifecycle: Lifecycle,
) {
    var centeredOnce: Boolean = false

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

private class ConfidenceOverlayView(context: Context) : View(context) {
    var map: MapView? = null
    var estimates: List<DeviceMapEstimate> = emptyList()
    var phoneLocation: PhoneLocation? = null

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

    override fun onDraw(canvas: Canvas) {
        val projection = map?.projection ?: return
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
            val radiusPx = abs(edge.x - centerPoint.x).coerceAtLeast(12).toFloat()
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
    .groupBy(LocalMapObservation::deviceAddress)
    .map { (_, group) ->
        val sorted = group.sortedByDescending(LocalMapObservation::observedUtc)
        val weighted = sorted.fold(WeightedLocation()) { accumulator, observation ->
            val confidence = max(observation.confidenceRadiusMeters, 1.0)
            val signalWeight = (observation.rssi + 100).coerceIn(1, 80).toDouble()
            val weight = signalWeight / (confidence * confidence)
            accumulator.add(observation, weight)
        }
        val latest = sorted.first()
        DeviceMapEstimate(
            address = latest.deviceAddress,
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

private fun mapStatus(
    locationGranted: Boolean,
    phoneLocation: PhoneLocation?,
    estimateCount: Int,
): String = when {
    !locationGranted -> "Location is required before scan samples can be mapped."
    phoneLocation == null -> "Waiting for a local GPS or network location fix."
    estimateCount == 1 -> "1 KARR candidate mapped from local history."
    else -> "$estimateCount KARR candidates mapped from local history."
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
private const val OSM_MIN_ZOOM = 14.0
private const val OSM_MAX_ZOOM = 19.0
private const val DEFAULT_ZOOM = 17.0
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
private var osmMapConfigured = false
