package com.karr.key.desktop.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.karr.key.ble.BleController
import com.karr.key.ble.BleSessionState
import com.karr.key.data.DeviceProfile
import com.karr.key.data.DeviceReadRecord
import com.karr.key.desktop.data.DesktopDeviceProfileStore
import com.karr.key.desktop.data.DesktopDeviceReadStore
import com.karr.key.desktop.data.DesktopMapHistoryStore
import com.karr.key.desktop.resources.Res
import com.karr.key.desktop.resources.ic_recenter
import com.karr.key.desktop.resources.ic_search
import com.karr.key.desktop.resources.ic_stop
import com.karr.key.desktop.resources.ic_wifi
import com.karr.key.desktop.ui.VinIdentityContent
import com.karr.key.desktop.ui.vinFromLines
import com.karr.key.protocol.KarrVulnerabilityStatus
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jxmapviewer.JXMapViewer
import org.jxmapviewer.OSMTileFactoryInfo
import org.jxmapviewer.input.PanMouseInputListener
import org.jxmapviewer.input.ZoomMouseWheelListenerCursor
import org.jxmapviewer.viewer.DefaultTileFactory
import org.jxmapviewer.viewer.GeoPosition

private val LAST_SEEN_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private class ClickHandler { var onClick: (Double, Double) -> Unit = { _, _ -> } }

private sealed interface FocusOp {
    data class Center(val position: GeoPosition, val zoom: Int? = null) : FocusOp
    data class Fit(val positions: List<GeoPosition>) : FocusOp
}

// JXMapViewer zoom is inverted for the OSM tile factory: 0 = max zoom-in
// (street/building), 19 = min (whole world). "Locate me" / recenter go all the
// way in (per the user's request); a specific unit gets a close view.
private const val MAX_ZOOM = 0
private const val MIN_ZOOM = 19
private const val DEVICE_ZOOM = 2

/**
 * Desktop Map with the Android map's controls and fine-tuned features: an OSM
 * slippy view (JXMapViewer) with confidence circles, vulnerability-glyph markers,
 * tap-to-expand clusters, reported-GPS annotations, per-unit history trails, a
 * device search, and a unit-read-data dialog. Desktop has no GPS, so the phone
 * marker / heading-up / recenter-on-me controls are replaced by "fit all", and
 * live sightings are not recorded (import history from Settings › Map).
 */
@Composable
fun DesktopMapScreen(
    controller: BleController,
    mapHistoryStore: DesktopMapHistoryStore,
    deviceReadStore: DesktopDeviceReadStore,
    deviceProfileStore: DesktopDeviceProfileStore,
    snackbar: SnackbarHostState,
    myLocation: GeoPoint?,
    onMyLocationChange: (GeoPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val dark = isSystemInDarkTheme()

    var estimates by remember { mutableStateOf<List<DeviceMapEstimate>>(emptyList()) }
    var profiles by remember { mutableStateOf<Map<String, DeviceProfile>>(emptyMap()) }
    var readsByAddress by remember { mutableStateOf<Map<String, List<DeviceReadRecord>>>(emptyMap()) }
    var history by remember { mutableStateOf<List<com.karr.key.data.LocalMapObservation>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }

    var selectedAddresses by remember { mutableStateOf<Set<String>>(emptySet()) }
    var revealedReportedAddress by remember { mutableStateOf<String?>(null) }
    var historyShownAddresses by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var focus by remember { mutableStateOf<FocusOp?>(null) }
    var focusNonce by remember { mutableStateOf(0) }
    var locating by remember { mutableStateOf(false) }
    var settingLocation by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        val loaded = withContext(Dispatchers.IO) {
            val h = mapHistoryStore.recent(limit = 5_000)
            Triple(h, deviceProfileStore.all(), deviceReadStore.allGroupedByAddress())
        }
        history = loaded.first
        profiles = loaded.second
        readsByAddress = loaded.third
        estimates = withContext(Dispatchers.Default) { estimateDeviceLocations(loaded.first) }
    }

    // Re-center on the persisted "you are here" when the Map tab is (re)opened.
    LaunchedEffect(Unit) {
        myLocation?.let { focus = FocusOp.Center(GeoPosition(it.latitude, it.longitude), MAX_ZOOM); focusNonce++ }
    }

    // Recover a reported position from stored reads when the history row is absent.
    val displayedEstimates = remember(estimates, readsByAddress) {
        estimates.map { e ->
            if (e.reportedPosition != null) e
            else e.copy(reportedPosition = reportedPositionFromReads(readsByAddress[e.address].orEmpty()))
        }
    }
    // Device positions come from stored/imported map history + reads, which carry
    // real GPS (recorded with the phone's GPS, or the unit's own reported GPS), so
    // the RSSI estimate and its confidence circle are accurate — shown just as on
    // the phone. The desktop never records NEW sightings at its own coarse IP
    // location (live map recording is off), so no device is ever mis-placed there.
    // Imported data has no vulnerability profile (that only comes from a live auth
    // challenge). But a device with stored reads was successfully authenticated,
    // which requires the legacy challenge — so it was vulnerable; a patched unit
    // never yields reads. Use the stored profile when known, else infer from reads.
    val vulnerabilityByAddress = remember(profiles, readsByAddress, displayedEstimates) {
        displayedEstimates.associate { e ->
            val stored = profiles[e.address]?.vulnerability
            e.address to when {
                stored != null && stored != KarrVulnerabilityStatus.UNKNOWN -> stored
                readsByAddress[e.address].orEmpty().isNotEmpty() -> KarrVulnerabilityStatus.VULNERABLE
                else -> KarrVulnerabilityStatus.UNKNOWN
            }
        }
    }
    val historyTrackByAddress = remember(history, historyShownAddresses) {
        if (historyShownAddresses.isEmpty()) emptyMap()
        else history
            .filterNot { it.locationProvider == com.karr.key.data.VEHICLE_GPS_PROVIDER }
            .filter { it.deviceAddress.uppercase() in historyShownAddresses }
            .groupBy { it.deviceAddress.uppercase() }
            .mapValues { (_, g) -> g.sortedBy { it.observedUtc }.map { GeoPoint(it.phoneLatitude, it.phoneLongitude) } }
    }
    val historyPointCounts = remember(history) {
        history.filterNot { it.locationProvider == com.karr.key.data.VEHICLE_GPS_PROVIDER }
            .groupingBy { it.deviceAddress.uppercase() }.eachCount()
    }
    val painter = remember { DeviceOverlayPainter() }
    val clickHandler = remember { ClickHandler() }
    val viewer = remember { createMapViewer(painter, clickHandler) }
    val appliedNonce = remember { intArrayOf(-1) }

    clickHandler.onClick = { x, y ->
        if (settingLocation) {
            // Manual location: place "you are here" wherever the user clicks — an
            // exact fix that beats the coarse IP estimate. Keeps the current view.
            val geo = viewer.convertPointToGeoPosition(java.awt.Point(x.toInt(), y.toInt()))
            onMyLocationChange(GeoPoint(geo.latitude, geo.longitude))
            settingLocation = false
            scope.launch { snackbar.showSnackbar("Location set.") }
        } else {
            when (val hit = painter.hitTest(viewer, x, y)) {
                is MapHit.ReportedTapped ->
                    { focus = FocusOp.Center(GeoPosition(hit.position.latitude, hit.position.longitude), DEVICE_ZOOM); focusNonce++ }
                is MapHit.ClusterExpand -> {
                    selectedAddresses = hit.members.map { it.address }.toSet()
                    revealedReportedAddress = null; historyShownAddresses = emptySet()
                    focus = FocusOp.Fit(hit.members.map { GeoPosition(it.latitude, it.longitude) }); focusNonce++
                }
                is MapHit.Selected -> {
                    selectedAddresses = hit.hits.map { it.address }.toSet()
                    revealedReportedAddress = null; historyShownAddresses = emptySet()
                }
            }
        }
    }

    fun fitAll() {
        if (displayedEstimates.isNotEmpty()) {
            focus = FocusOp.Fit(displayedEstimates.map { GeoPosition(it.latitude, it.longitude) }); focusNonce++
        }
    }
    fun locate() {
        if (locating) return
        locating = true
        scope.launch {
            DesktopLocationProvider.estimate()
                .onSuccess { est ->
                    onMyLocationChange(GeoPoint(est.latitude, est.longitude))
                    focus = FocusOp.Center(GeoPosition(est.latitude, est.longitude), MAX_ZOOM); focusNonce++
                    snackbar.showSnackbar("Approximate location: ${est.label ?: "found from your connection"}")
                }
                .onFailure { snackbar.showSnackbar(it.message ?: "Could not estimate location.") }
            locating = false
        }
    }
    fun toggleHistory(address: String) {
        val n = address.uppercase()
        historyShownAddresses = if (n in historyShownAddresses) historyShownAddresses - n else historyShownAddresses + n
        val pts = history.filterNot { it.locationProvider == com.karr.key.data.VEHICLE_GPS_PROVIDER }
            .filter { it.deviceAddress.uppercase() == n }.map { GeoPosition(it.phoneLatitude, it.phoneLongitude) }
        if (n in historyShownAddresses && pts.size > 1) { focus = FocusOp.Fit(pts); focusNonce++ }
    }

    // A sidebar beside the map, never overlapping it: a Compose SwingPanel is a
    // native component that always paints on top of overlapping Compose content,
    // so overlaid controls would be hidden. The sidebar scrolls, so every control
    // stays reachable at any window size or screen resolution.
    Row(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(340.dp).fillMaxHeight()
                .verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Map", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val scanning = state is BleSessionState.Scanning
                FilledIconButton(onClick = {
                    if (scanning) controller.stopScan()
                    else controller.startScan().onFailure { f ->
                        scope.launch { snackbar.showSnackbar(f.message ?: "BLE scan failed.") }
                    }
                }) {
                    Icon(painterResource(if (scanning) Res.drawable.ic_stop else Res.drawable.ic_wifi),
                        contentDescription = if (scanning) "Stop" else "Scan")
                }
                OutlinedIconButton(
                    onClick = { myLocation?.let { focus = FocusOp.Center(GeoPosition(it.latitude, it.longitude), MAX_ZOOM); focusNonce++ } },
                    enabled = myLocation != null,
                ) {
                    Icon(painterResource(Res.drawable.ic_recenter), contentDescription = "Recenter on me")
                }
                TextButton(onClick = { locate() }, enabled = !locating) {
                    Text(if (locating) "Locating…" else if (myLocation == null) "Locate me" else "Update location")
                }
                TextButton(onClick = { settingLocation = !settingLocation }) {
                    Text(if (settingLocation) "Click the map…" else "Set location")
                }
                TextButton(onClick = { viewer.zoom = (viewer.zoom - 1).coerceAtLeast(MAX_ZOOM); viewer.repaint() }) { Text("Zoom in") }
                TextButton(onClick = { viewer.zoom = (viewer.zoom + 1).coerceAtMost(MIN_ZOOM); viewer.repaint() }) { Text("Zoom out") }
                TextButton(onClick = { fitAll() }, enabled = displayedEstimates.isNotEmpty()) { Text("Fit all") }
                TextButton(onClick = { refresh++ }) { Text("Refresh") }
            }
            OutlinedTextField(query, { query = it; selectedAddresses = emptySet() },
                label = { Text("Search devices") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (selectedAddresses.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${selectedAddresses.size} selected on map",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { selectedAddresses = emptySet() }) { Text("Show all") }
                }
            }
            HorizontalDivider()
            // A map tap filters the list to the tapped device(s); otherwise the
            // search query filters. "Show all" clears the selection.
            val filtered = if (selectedAddresses.isNotEmpty()) {
                displayedEstimates.filter { it.address in selectedAddresses }
            } else {
                displayedEstimates.filter { it.label.contains(query, ignoreCase = true) }
            }
            if (filtered.isEmpty()) {
                Text(
                    if (displayedEstimates.isEmpty()) {
                        "No stored sightings yet. Import a WiGLE CSV from Settings › Map, or use " +
                            "Locate me to center the map on your area."
                    } else {
                        "No devices match \"$query\"."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                filtered.forEach { estimate ->
                    val selected = estimate.address in selectedAddresses
                    EstimateDetail(
                        estimate = estimate,
                        evidence = deviceEvidenceLine(estimate.evidence, estimate.label, profiles[estimate.address]),
                        vulnerability = vulnerabilityByAddress[estimate.address],
                        selected = selected,
                        onClick = {
                            selectedAddresses = setOf(estimate.address)
                            revealedReportedAddress = null; historyShownAddresses = emptySet()
                            focus = FocusOp.Center(GeoPosition(estimate.latitude, estimate.longitude), DEVICE_ZOOM); focusNonce++
                        },
                    )
                    if (selected) {
                        UnitReadDataContent(
                            address = estimate.address,
                            rssi = estimate.rssi,
                            radiusMeters = estimate.radiusMeters,
                            reads = readsByAddress[estimate.address].orEmpty(),
                            reportedPosition = estimate.reportedPosition,
                            historyShown = estimate.address.uppercase() in historyShownAddresses,
                            historyPointCount = historyPointCounts[estimate.address.uppercase()] ?: 0,
                            onToggleHistory = { toggleHistory(estimate.address) },
                            onReportedCoordsClick = estimate.reportedPosition?.let { pos ->
                                {
                                    revealedReportedAddress = estimate.address
                                    focus = FocusOp.Center(GeoPosition(pos.latitude, pos.longitude), DEVICE_ZOOM); focusNonce++
                                }
                            },
                        )
                    }
                }
            }
            Text("© OpenStreetMap contributors",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
        }
        VerticalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            SwingPanel(
                modifier = Modifier.fillMaxSize(),
                factory = { viewer },
                update = { v ->
                    painter.estimates = displayedEstimates
                    painter.vulnerabilityByAddress = vulnerabilityByAddress
                    painter.revealedReportedAddress = revealedReportedAddress
                    painter.historyTracks = historyTrackByAddress
                    painter.selectedAddresses = selectedAddresses
                    painter.myLocation = myLocation
                    painter.darkTheme = dark
                    if (appliedNonce[0] != focusNonce) {
                        appliedNonce[0] = focusNonce
                        when (val f = focus) {
                            is FocusOp.Center -> {
                                v.addressLocation = f.position
                                f.zoom?.let { v.zoom = it }
                            }
                            is FocusOp.Fit -> if (f.positions.isNotEmpty()) v.zoomToBestFit(f.positions.toSet(), 0.7)
                            null -> Unit
                        }
                    }
                    v.repaint()
                },
            )
        }
    }
}

@Composable
private fun EstimateDetail(
    estimate: DeviceMapEstimate,
    evidence: String,
    vulnerability: KarrVulnerabilityStatus?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small) else Modifier)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Text(vulnerability?.statusGlyph() ?: "•", style = MaterialTheme.typography.titleMedium)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(estimate.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(evidence, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("Last seen ${LAST_SEEN_FORMAT.format(estimate.lastSeenUtc)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("View unit data ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun UnitReadDataContent(
    address: String,
    rssi: Int?,
    radiusMeters: Double?,
    reads: List<DeviceReadRecord>,
    reportedPosition: GeoPoint?,
    historyShown: Boolean,
    historyPointCount: Int,
    onToggleHistory: () -> Unit,
    onReportedCoordsClick: (() -> Unit)?,
) {
    val ordered = remember(reads) { reads.sortedByDescending { it.capturedUtc } }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(buildString {
            append(address)
            if (rssi != null) append("  RSSI $rssi dBm")
            if (radiusMeters != null) append("  radius ${radiusMeters.toInt()} m")
        }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (reportedPosition != null) {
            Text("Reported GPS ${String.format(Locale.US, "%.5f, %.5f", reportedPosition.latitude, reportedPosition.longitude)} ›",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                modifier = if (onReportedCoordsClick != null) Modifier.clickable(onClick = onReportedCoordsClick) else Modifier)
        }
        if (historyPointCount > 1) {
            Text(if (historyShown) "Hide location history" else "Show location history ($historyPointCount) ›",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(onClick = onToggleHistory))
        }
        if (ordered.isEmpty()) {
            Text("No unit reads stored yet.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        } else {
            ordered.forEach { record ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(record.heading, style = MaterialTheme.typography.labelLarge)
                    record.lines.filterNot { reportedPosition != null && it.startsWith("Position:") }
                        .forEach { line ->
                            Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    vinFromLines(record.lines)?.let { vin -> VinIdentityContent(vin = vin) }
                    Text("Read ${LAST_SEEN_FORMAT.format(record.capturedUtc)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// OSM's tile policy blocks the default Java user agent (the "blank tiles" symptom) and no longer
// serves plain HTTP; force HTTPS and set a proper identifying User-Agent on every tile request.
private const val MAP_USER_AGENT = "KARR-Key/1.0.5 (+https://github.com/neednotapply/KARR-Key)"

private fun createMapViewer(painter: DeviceOverlayPainter, clickHandler: ClickHandler): JXMapViewer {
    val viewer = JXMapViewer()
    val info = OSMTileFactoryInfo("OpenStreetMap", "https://tile.openstreetmap.org")
    val tileFactory = object : DefaultTileFactory(info) {
        override fun addCustomRequestProperties(connection: java.net.URLConnection) {
            connection.setRequestProperty("User-Agent", MAP_USER_AGENT)
        }
    }
    tileFactory.setThreadPoolSize(4)
    viewer.tileFactory = tileFactory
    viewer.zoom = 12
    viewer.addressLocation = GeoPosition(39.5, -98.35)
    viewer.overlayPainter = painter
    val pan = PanMouseInputListener(viewer)
    viewer.addMouseListener(pan)
    viewer.addMouseMotionListener(pan)
    // Zoom toward the cursor (only fires while the pointer is over the map).
    viewer.addMouseWheelListener(ZoomMouseWheelListenerCursor(viewer))
    viewer.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            clickHandler.onClick(e.x.toDouble(), e.y.toDouble())
        }
    })
    return viewer
}
