package com.karr.key.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.karr.key.R
import com.karr.key.ble.BleCommandExchange
import com.karr.key.ble.BleCommandExchangeResult
import com.karr.key.ble.BleController
import com.karr.key.ble.BleModeChangeExchange
import com.karr.key.ble.BleSessionState
import com.karr.key.core.KarrTrace
import com.karr.key.core.ResearchAccess
import com.karr.key.core.Session
import com.karr.key.data.AutoConnectReadPreferences
import com.karr.key.data.LocalDeviceProfileStore
import com.karr.key.data.LocalDeviceReadStore
import com.karr.key.data.LocalMapHistoryStore
import com.karr.key.data.LocalOperationStore
import com.karr.key.data.KeyRepository
import com.karr.key.data.MapHistoryImportSummary
import com.karr.key.protocol.DeviceKeyPurpose
import com.karr.key.protocol.DeviceKeyProvisioning
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.KarrVulnerabilityStatus
import com.karr.key.protocol.QtResponseDecoder
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredWorkflowCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val KARR_FIRMWARE_UPDATE_URL =
    "https://www.karrsecurity.com/karr-security-firmware-update-instructions"
private const val UCSD_SECURITY_RESEARCH_URL =
    "https://today.ucsd.edu/story/2-million-cars-with-anti-theft-systems-installed-by-dealers-are-at-higher-risk-of-theft"
internal const val WATCH_DOGS_MODE_URL = "https://fuckyou.gay/?KARR-Key"
private const val WATCH_DOGS_MODE_TAPS = 7
private const val WATCH_DOGS_COUNTDOWN_MILLIS = 2_500L

internal data class WatchDogsModeProgress(
    val nextTapCount: Int,
    val remainingTaps: Int,
    val activated: Boolean,
) {
    val message: String
        get() = when {
            activated -> "Watch Dogs mode enabled."
            remainingTaps == 1 -> "You are now 1 tap away from Watch Dogs mode."
            else -> "You are now $remainingTaps taps away from Watch Dogs mode."
        }
}

internal fun advanceWatchDogsMode(currentTapCount: Int): WatchDogsModeProgress {
    val completedTaps = currentTapCount.coerceIn(0, WATCH_DOGS_MODE_TAPS - 1) + 1
    val activated = completedTaps == WATCH_DOGS_MODE_TAPS
    return WatchDogsModeProgress(
        nextTapCount = if (activated) 0 else completedTaps,
        remainingTaps = WATCH_DOGS_MODE_TAPS - completedTaps,
        activated = activated,
    )
}

@Composable
fun KarrKeyApp(
    repository: KeyRepository,
    bleController: BleController,
    deviceKeys: DeviceKeyProvisioning,
    operationStore: LocalOperationStore,
    mapHistoryStore: LocalMapHistoryStore,
    deviceReadStore: LocalDeviceReadStore,
    deviceProfileStore: LocalDeviceProfileStore,
    autoConnectReadPreferences: AutoConnectReadPreferences,
    requestBlePermissions: (((Boolean) -> Unit) -> Unit),
    requestMapLocationPermissions: (((Boolean) -> Unit) -> Unit),
    requestMapHistoryExport: (((Result<Int>) -> Unit) -> Unit),
    requestMapHistoryImport: (((Result<MapHistoryImportSummary>) -> Unit) -> Unit),
) {
    var provisioningRevision by remember { mutableIntStateOf(0) }
    val session = remember(repository, deviceKeys, provisioningRevision) {
        ResearchAccess.restrictSession(
            base = repository.localSession(),
            customerKeyProvisioned = deviceKeys.isProvisioned(DeviceKeyPurpose.CUSTOMER),
            dealerKeyProvisioned = deviceKeys.isProvisioned(DeviceKeyPurpose.DEALER),
        )
    }
    val bleState by bleController.state.collectAsState()
    val deviceAuthenticated = bleState is BleSessionState.Ready
    val readyState = bleState as? BleSessionState.Ready
    val unitModel = readyState?.unitModel
    val unitMode = readyState?.unitMode
    val snackbar = remember { SnackbarHostState() }
    val navigation = rememberSaveable(saver = KarrNavigationState.Saver) {
        KarrNavigationState()
    }
    var wasDeviceAuthenticated by remember(bleController) {
        mutableStateOf(deviceAuthenticated)
    }
    var operationsQuery by rememberSaveable { mutableStateOf("") }
    var operationsFilters by rememberSaveable(stateSaver = OperationFilters.Saver) {
        mutableStateOf(OperationFilters())
    }
    val operationsListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var autoConnectReadEnabled by remember {
        mutableStateOf(autoConnectReadPreferences.enabled)
    }
    var autoConnectSendOperationId by remember {
        mutableStateOf(autoConnectReadPreferences.operationId)
    }
    val autoReadExchange = remember(bleController) { BleCommandExchange(bleController) }
    val autoModeChangeExchange = remember(bleController) { BleModeChangeExchange(bleController) }
    // Fallback for the auto-connect sweep when no valid operation is selected. READ_STATE also
    // captures the unit's own reported GPS from its extended state, letting the map plot it. The
    // catalog derives operation ids from the command name (READ_STATE -> "read-state").
    val readStateOperation = remember {
        RecoveredWorkflowCatalog.operationById("read-state")
    }
    // Per-device cooldown so the sweep doesn't immediately reconnect to the unit it just read
    // (still the nearest/strongest signal right after disconnecting) instead of moving on to
    // others nearby. Keyed by normalized address, valued by SystemClock.elapsedRealtime().
    val autoSweepCooldownUntil = remember { mutableMapOf<String, Long>() }
    // Last reported GPS recorded per unit, so a static stale fix isn't re-written every sweep pass;
    // only a changed position adds a new "recently reported" annotation point.
    val lastReportedGps = remember { mutableMapOf<String, Pair<Double, Double>>() }
    // Last vulnerability verdict and unit-model code persisted per unit, so a device profile isn't
    // re-written on every sweep pass; only a changed value hits the store. Separate maps because the
    // verdict is learned at AuthenticationRequired and the model only later, at Ready.
    val lastVulnerability = remember { mutableMapOf<String, KarrVulnerabilityStatus>() }
    val lastModelWrite = remember { mutableMapOf<String, Int?>() }

    // Records every KARR sighting to map history during ANY scan, on ANY tab. Hoisted here (like
    // the sweep below) so map persistence isn't tied to the Map screen being composed; the Map
    // screen only builds observations for its live display. Uses its own lightweight location fix.
    val context = LocalContext.current
    var recorderLocation by remember { mutableStateOf<PhoneLocation?>(null) }
    val recorderLocationTracker = remember(context) {
        FrameworkLocationTracker(context) { location -> recorderLocation = location }
    }
    DisposableEffect(context) {
        if (context.hasForegroundLocationPermission()) {
            recorderLocationTracker.start()
        }
        onDispose { recorderLocationTracker.stop() }
    }
    // Throttle writes: a unit seen several times a second while parked would otherwise bloat the DB,
    // and the map decrypts every stored row on open (6 keystore ops per row), so unchecked growth
    // makes the map slow to populate. Record a unit again only once the phone has moved a little or
    // enough time has passed — a dense track while driving, sparse while stationary.
    val lastMapRecord = remember { mutableMapOf<String, RecorderMark>() }
    LaunchedEffect(bleController, mapHistoryStore) {
        bleController.scanObservations.collect { observation ->
            val location = recorderLocation ?: return@collect
            if (!location.isFresh()) return@collect
            val stored = observation.toLocalMapObservation(location) ?: return@collect
            val now = SystemClock.elapsedRealtime()
            val last = lastMapRecord[stored.deviceAddress]
            if (last != null &&
                now - last.elapsedRealtimeMillis < MAP_RECORD_MAX_INTERVAL_MILLIS &&
                approxDistanceMeters(
                    last.latitude,
                    last.longitude,
                    location.latitude,
                    location.longitude,
                ) < MAP_RECORD_MIN_METERS
            ) {
                return@collect
            }
            lastMapRecord[stored.deviceAddress] =
                RecorderMark(now, location.latitude, location.longitude)
            withContext(Dispatchers.IO) { mapHistoryStore.add(stored) }
            KarrTrace.log(KarrTrace.MAP) {
                "record ${stored.deviceName ?: stored.deviceAddress} rssi=${stored.rssi}"
            }
        }
    }

    LaunchedEffect(deviceAuthenticated) {
        // Auto-connect/read drives the app hands-free, so it shouldn't also get yanked over to
        // Operations on every connection the way a manual Device-screen connect does.
        if (!autoConnectReadEnabled) {
            navigation.openOperationsAfterSuccessfulConnection(
                wasAuthenticated = wasDeviceAuthenticated,
                isAuthenticated = deviceAuthenticated,
            )
        }
        wasDeviceAuthenticated = deviceAuthenticated
    }

    // Lives here rather than on the Device screen so it keeps sweeping regardless of which tab
    // is currently visible, instead of being cancelled the moment the user navigates away.
    LaunchedEffect(autoConnectReadEnabled, bleController) {
        if (!autoConnectReadEnabled) return@LaunchedEffect
        bleController.scanObservations.collect { observation ->
            val device = observation.device
            if (!device.confirmedConnectionEligible) return@collect
            if (bleController.state.value !is BleSessionState.Scanning) return@collect
            val cooldownUntil = autoSweepCooldownUntil[device.address.uppercase()]
            if (cooldownUntil != null && SystemClock.elapsedRealtime() < cooldownUntil) {
                KarrTrace.log(KarrTrace.SWEEP) {
                    val remaining = (cooldownUntil - SystemClock.elapsedRealtime()) / 1000
                    "skip ${device.name ?: device.address} (cooldown ${remaining}s)"
                }
                return@collect
            }
            KarrTrace.log(KarrTrace.SWEEP) {
                "connect ${device.name ?: device.address} rssi=${device.rssi}"
            }
            bleController.connect(device.address).onFailure { failure ->
                KarrTrace.warn(KarrTrace.SWEEP, "connect ${device.address} rejected: ${failure.message}")
                scope.launch {
                    snackbar.showSnackbar(failure.message ?: "Auto-connect failed.")
                }
            }
        }
    }

    // Record what a connection reveals about a unit — its auth vulnerability (from the challenge
    // width at AuthenticationRequired) and, once authenticated, its model — for EVERY connection,
    // manual or auto-connect. Kept separate from the auto-connect sweep (which only runs when
    // auto-connect-read is on and only sends a command) so a hand-connected unit — a neighbour's
    // patched car reached from the Device screen — still lands its patched/vulnerable status on the
    // map. A patched unit issues a wider-than-legacy challenge and is rejected before Ready, so the
    // AuthenticationRequired capture is the only place its status can be observed.
    LaunchedEffect(bleController) {
        bleController.state.collect { current ->
            when (current) {
                is BleSessionState.AuthenticationRequired -> {
                    val vulnerability =
                        KarrVulnerabilityStatus.fromChallengeBytes(current.challengeBytes)
                    val key = current.address.uppercase()
                    if (lastVulnerability[key] != vulnerability) {
                        lastVulnerability[key] = vulnerability
                        withContext(Dispatchers.IO) {
                            deviceProfileStore.record(
                                deviceAddress = current.address,
                                unitModelCode = null,
                                vulnerability = vulnerability,
                            )
                        }
                    }
                }
                is BleSessionState.Ready -> {
                    // Reaching Ready means the legacy auth succeeded (vulnerable); READ_MODE also gave
                    // the authoritative model. Persist both for the map label.
                    val key = current.address.uppercase()
                    val modelCode = current.unitModel?.code
                    if (lastModelWrite[key] != modelCode) {
                        lastModelWrite[key] = modelCode
                        withContext(Dispatchers.IO) {
                            deviceProfileStore.record(
                                deviceAddress = current.address,
                                unitModelCode = modelCode,
                                vulnerability = KarrVulnerabilityStatus.VULNERABLE,
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    LaunchedEffect(autoConnectReadEnabled, autoConnectSendOperationId, bleController) {
        if (!autoConnectReadEnabled) return@LaunchedEffect
        // Resolve against the auto-connect list so only valid, non-experimental operations are ever
        // auto-sent; fall back to the state read if the stored id is missing or no longer offered.
        val operation = RecoveredWorkflowCatalog.autoConnectOperations
            .firstOrNull { it.id == autoConnectSendOperationId }
            ?: readStateOperation
        if (operation == null) {
            KarrTrace.warn(KarrTrace.SWEEP, "auto-connect operation unavailable; sweep cannot send")
            return@LaunchedEffect
        }
        // Address of the unit currently being connected/authenticated, so a failure that lands
        // before Ready (an updated unit refusing the UART link is the common case) can be cooled
        // down and skipped instead of stalling or tight-looping the sweep.
        var pendingAddress: String? = null
        // Ready is re-emitted several times per connection (link-diagnostic and READ_MODE heartbeat
        // updates each copy it), so guard against duplicate reads within one connection. Reset when
        // the session leaves Ready, so revisiting a unit on a later sweep reads it again.
        var handledCurrentReady = false
        bleController.state.collect { current ->
            when (current) {
                is BleSessionState.Connecting -> {
                    pendingAddress = current.address
                    handledCurrentReady = false
                }
                is BleSessionState.DiscoveringUart -> pendingAddress = current.address
                is BleSessionState.UartSubscribed -> pendingAddress = current.address
                // Profile recording (vulnerability/model) now lives in the always-on effect above, so
                // it happens on manual connects too; here the sweep only tracks the pending address.
                is BleSessionState.AuthenticationRequired -> pendingAddress = current.address
                is BleSessionState.Authenticating -> pendingAddress = current.address
                is BleSessionState.Ready -> {
                    if (handledCurrentReady) return@collect
                    handledCurrentReady = true
                    pendingAddress = null
                    val ready = current
                    KarrTrace.log(KarrTrace.SWEEP) {
                        "ready ${ready.name ?: ready.address} " +
                            "model=${ready.unitModel?.displayName ?: "?"} " +
                            "mode=${ready.unitMode?.name ?: "?"}; reading"
                    }
                    // The unit's profile (model + vulnerable) is persisted by the always-on effect
                    // above; the sweep just performs the auto-send.
                    // Talks to the unit directly instead of going through DeviceOperationExecutor:
                    // that path awaits snackbar dismissal (several seconds each, queued behind any
                    // snackbar in flight), which stalled disconnect()/startScan() long enough that
                    // the sweep looked stuck online rather than moving on to the next device.
                    // Drive the unit to a mode the selected operation supports before sending it,
                    // following the recovered CHANGE_MODE transition path (multi-hop), each hop
                    // confirmed by a fresh READ_MODE. Only mode blocks are resolved this way;
                    // role/hardware blocks and unauthorized transition paths still stop the send.
                    val initialAccess = operation.executionAccess(
                        session = session,
                        deviceAuthenticated = true,
                        unitModel = ready.unitModel,
                        unitMode = ready.unitMode,
                    )
                    val drive = autoModeChangeExchange.driveToRequiredMode(
                        session = session,
                        action = initialAccess.action,
                        unitModel = ready.unitModel,
                        startMode = ready.unitMode,
                        onHop = { from, to ->
                            KarrTrace.log(KarrTrace.SWEEP) {
                                "mode ${from?.name ?: "?"} -> ${to.name} for ${operation.label}"
                            }
                        },
                    )
                    drive.failure?.let { failure ->
                        KarrTrace.warn(
                            KarrTrace.SWEEP,
                            "mode switch failed: ${failure::class.simpleName}",
                        )
                    }
                    val access = if (drive.changed) {
                        operation.executionAccess(
                            session = session,
                            deviceAuthenticated = true,
                            unitModel = ready.unitModel,
                            unitMode = drive.finalMode,
                        )
                    } else {
                        initialAccess
                    }
                    if (access.blockingReason == null) {
                        val action = access.action
                        val result = autoReadExchange.sendAndAwait(
                            packet = action.packet(),
                            expectedCommand = action.command,
                        )
                        if (result is BleCommandExchangeResult.Acknowledged) {
                            val decoded = QtResponseDecoder.decode(result.response, ready.unitModel)
                            deviceReadStore.recordReadIfApplicable(
                                controller = bleController,
                                command = action.command,
                                mutating = action.mutating,
                                decoded = decoded,
                                fallbackUnitModel = ready.unitModel,
                            )
                            // READ_STATE's self-reported GPS is a stale last-known fix (static, ~1 km
                            // off), so it is NOT the plotted position — the map locates from RSSI.
                            // But it's a recently-visited location worth keeping: record it as a
                            // secondary annotation (marker + line to the RSSI pin, tap to jump).
                            // Deduped so a static fix isn't re-written on every pass.
                            decoded?.vehiclePosition?.let { position ->
                                val key = ready.address.uppercase()
                                val coord = position.latitude to position.longitude
                                if (lastReportedGps[key] != coord) {
                                    lastReportedGps[key] = coord
                                    withContext(Dispatchers.IO) {
                                        mapHistoryStore.addVehicleReportedPosition(
                                            deviceAddress = ready.address,
                                            deviceName = ready.name,
                                            latitude = position.latitude,
                                            longitude = position.longitude,
                                        )
                                    }
                                    KarrTrace.log(KarrTrace.MAP) {
                                        "reported GPS ${ready.name ?: ready.address} -> " +
                                            "${position.latitude}, ${position.longitude}"
                                    }
                                }
                            }
                        }
                    } else {
                        KarrTrace.log(KarrTrace.SWEEP) {
                            "send skipped for ${ready.address}: ${access.blockingReason}"
                        }
                    }
                    // Applied regardless of read outcome, so a device that fails or times out isn't
                    // retried every pass, and with two-plus units the sweep rotates between them.
                    autoSweepCooldownUntil[ready.address.uppercase()] =
                        SystemClock.elapsedRealtime() + AUTO_SWEEP_COOLDOWN_MILLIS
                    KarrTrace.log(KarrTrace.SWEEP) { "disconnect ${ready.address}; resume scan" }
                    bleController.disconnect()
                    bleController.startScan().onFailure { failure ->
                        scope.launch {
                            snackbar.showSnackbar(failure.message ?: "Could not resume scanning.")
                        }
                    }
                }
                is BleSessionState.Error -> {
                    // Pre-Ready connect/UART failure. No cooldown was set above, so cool down the
                    // attempted unit and resume scanning: one refusing unit must not stall the sweep
                    // or get retried on a tight loop.
                    val failed = pendingAddress
                    pendingAddress = null
                    handledCurrentReady = false
                    if (failed != null) {
                        autoSweepCooldownUntil[failed.uppercase()] =
                            SystemClock.elapsedRealtime() + AUTO_SWEEP_COOLDOWN_MILLIS
                    }
                    KarrTrace.warn(
                        KarrTrace.SWEEP,
                        "error on ${failed ?: "unknown"}: ${current.message}; cooldown + resume scan",
                    )
                    bleController.startScan()
                }
                else -> {
                    handledCurrentReady = false
                }
            }
        }
    }

    KeyShell(
        session = session,
        deviceAuthenticated = deviceAuthenticated,
        unitModel = unitModel,
        unitMode = unitMode,
        bleState = bleState,
        navigation = navigation,
        bleController = bleController,
        deviceKeys = deviceKeys,
        operationStore = operationStore,
        mapHistoryStore = mapHistoryStore,
        deviceReadStore = deviceReadStore,
        deviceProfileStore = deviceProfileStore,
        autoConnectReadEnabled = autoConnectReadEnabled,
        onAutoConnectReadEnabledChanged = { enabled ->
            autoConnectReadEnabled = enabled
            autoConnectReadPreferences.enabled = enabled
        },
        autoConnectSendOperationId = autoConnectSendOperationId,
        onAutoConnectSendOperationSelected = { operationId ->
            autoConnectSendOperationId = operationId
            autoConnectReadPreferences.operationId = operationId
        },
        onProvisioningChanged = { provisioningRevision++ },
        requestBlePermissions = requestBlePermissions,
        requestMapLocationPermissions = requestMapLocationPermissions,
        requestMapHistoryExport = requestMapHistoryExport,
        requestMapHistoryImport = requestMapHistoryImport,
        snackbar = snackbar,
        operationsQuery = operationsQuery,
        operationsFilters = operationsFilters,
        onOperationsQueryChanged = { operationsQuery = it },
        onOperationsFiltersChanged = { operationsFilters = it },
        operationsListState = operationsListState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyShell(
    session: Session,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode?,
    bleState: BleSessionState,
    navigation: KarrNavigationState,
    bleController: BleController,
    deviceKeys: DeviceKeyProvisioning,
    operationStore: LocalOperationStore,
    mapHistoryStore: LocalMapHistoryStore,
    deviceReadStore: LocalDeviceReadStore,
    deviceProfileStore: LocalDeviceProfileStore,
    autoConnectReadEnabled: Boolean,
    onAutoConnectReadEnabledChanged: (Boolean) -> Unit,
    autoConnectSendOperationId: String,
    onAutoConnectSendOperationSelected: (String) -> Unit,
    onProvisioningChanged: () -> Unit,
    requestBlePermissions: (((Boolean) -> Unit) -> Unit),
    requestMapLocationPermissions: (((Boolean) -> Unit) -> Unit),
    requestMapHistoryExport: (((Result<Int>) -> Unit) -> Unit),
    requestMapHistoryImport: (((Result<MapHistoryImportSummary>) -> Unit) -> Unit),
    snackbar: SnackbarHostState,
    operationsQuery: String,
    operationsFilters: OperationFilters,
    onOperationsQueryChanged: (String) -> Unit,
    onOperationsFiltersChanged: (OperationFilters) -> Unit,
    operationsListState: androidx.compose.foundation.lazy.LazyListState,
) {
    val scope = rememberCoroutineScope()
    val currentOperation = (navigation.currentRoute as? AppRoute.OperationDetail)
        ?.operationId
        ?.let(RecoveredWorkflowCatalog::operationById)
    val topBarTitle = shellTopBarTitle(
        route = navigation.currentRoute,
        operationTitle = currentOperation?.label,
    )
    var latestOperationExecutionResult by remember {
        mutableStateOf<OperationExecutionResult?>(null)
    }

    BoxWithConstraints {
        val navigationLayout = if (maxWidth < 600.dp) {
            NavigationSuiteType.NavigationBar
        } else {
            NavigationSuiteType.NavigationRail
        }
        NavigationSuiteScaffold(
            layoutType = navigationLayout,
            navigationSuiteItems = {
                TopLevelDestination.entries.forEach { destination ->
                    item(
                        selected = navigation.selectedTopLevel == destination,
                        onClick = { navigation.selectTopLevel(destination) },
                        icon = {
                            Icon(
                                painter = painterResource(destination.iconRes),
                                contentDescription = null,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            },
        ) {
            Scaffold(
            topBar = {
                topBarTitle?.let { title ->
                    TopAppBar(
                        title = {
                            Text(
                                text = title,
                                maxLines = 1,
                            )
                        },
                        navigationIcon = {
                            val currentRoute = navigation.currentRoute
                            if (
                                currentRoute is AppRoute.OperationDetail ||
                                currentRoute is AppRoute.CommandConsole
                            ) {
                                IconButton(onClick = { navigation.navigateUp() }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_back),
                                        contentDescription = "Navigate up",
                                    )
                                }
                            }
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .then(swipeNavigationModifier(navigation)),
            ) {
                NavDisplay(
                    backStack = navigation.currentBackStack,
                    onBack = { navigation.navigateUp() },
                    transitionSpec = {
                        horizontalPageSlide(
                            forward = navigation.topLevelTransitionForward,
                        )
                    },
                    popTransitionSpec = {
                        horizontalPageSlide(forward = false)
                    },
                    predictivePopTransitionSpec = {
                        horizontalPageSlide(forward = false)
                    },
                    entryProvider = entryProvider {
                        entry<AppRoute.Home> {
                            DashboardScreen(
                                session = session,
                                bleState = bleState,
                                deviceAuthenticated = deviceAuthenticated,
                                onOpenDevice = {
                                    navigation.openDevice()
                                    if (bleController.state.value.shouldStartHomeScan()) {
                                        requestBlePermissions { granted ->
                                            if (granted) {
                                                if (
                                                    bleController.state.value
                                                        .shouldStartHomeScan()
                                                ) {
                                                    bleController.startScan().onFailure { failure ->
                                                        scope.launch {
                                                            snackbar.showSnackbar(
                                                                failure.message
                                                                    ?: "BLE scan failed.",
                                                            )
                                                        }
                                                    }
                                                }
                                            } else {
                                                scope.launch {
                                                    snackbar.showSnackbar(
                                                        "Bluetooth permission was not granted.",
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                onOpenAccessKeys = {
                                    navigation.openSettings(SettingsSection.ACCESS_KEYS)
                                },
                            )
                        }
                        entry<AppRoute.Operations> {
                            OperationsRouteContent(
                                selectedOperation = null,
                                session = session,
                                deviceAuthenticated = deviceAuthenticated,
                                bleState = bleState,
                                unitModel = unitModel,
                                unitMode = unitMode,
                                controller = bleController,
                                operationStore = operationStore,
                                deviceReadStore = deviceReadStore,
                                snackbar = snackbar,
                                navigation = navigation,
                                query = operationsQuery,
                                filters = operationsFilters,
                                onQueryChanged = onOperationsQueryChanged,
                                onFiltersChanged = onOperationsFiltersChanged,
                                listState = operationsListState,
                                latestExecutionResult = latestOperationExecutionResult,
                                onLatestExecutionResultChanged = {
                                    latestOperationExecutionResult = it
                                },
                                onOpenCommandConsole = navigation::openCommandConsole,
                            )
                        }
                        entry<AppRoute.CommandConsole> {
                            CommandConsoleScreen(
                                controller = bleController,
                                deviceAuthenticated = deviceAuthenticated,
                                session = session,
                                unitModel = unitModel,
                                unitMode = unitMode,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        entry<AppRoute.Map> {
                            MapScreen(
                                controller = bleController,
                                historyStore = mapHistoryStore,
                                deviceReadStore = deviceReadStore,
                                deviceProfileStore = deviceProfileStore,
                                requestBlePermissions = requestBlePermissions,
                                requestLocationPermissions = requestMapLocationPermissions,
                                snackbar = snackbar,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        entry<AppRoute.Settings> { route ->
                            SettingsScreen(
                                controller = bleController,
                                deviceKeys = deviceKeys,
                                mapHistoryStore = mapHistoryStore,
                                deviceReadStore = deviceReadStore,
                                deviceProfileStore = deviceProfileStore,
                                section = route.section,
                                onSectionSelected = navigation::updateSettingsSection,
                                onProvisioningChanged = onProvisioningChanged,
                                requestMapHistoryExport = requestMapHistoryExport,
                                requestMapHistoryImport = requestMapHistoryImport,
                                snackbar = snackbar,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        entry<AppRoute.Device> {
                            DeviceCenterScreen(
                                controller = bleController,
                                autoConnectReadEnabled = autoConnectReadEnabled,
                                onAutoConnectReadEnabledChanged = onAutoConnectReadEnabledChanged,
                                autoConnectSendOperationId = autoConnectSendOperationId,
                                onAutoConnectSendOperationSelected = onAutoConnectSendOperationSelected,
                                requestPermissions = requestBlePermissions,
                                snackbar = snackbar,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        entry<AppRoute.OperationDetail> { route ->
                            val operation =
                                RecoveredWorkflowCatalog.operationById(route.operationId)
                            if (operation == null) {
                                MissingOperationState(onBack = { navigation.navigateUp() })
                            } else {
                                OperationsRouteContent(
                                    selectedOperation = operation,
                                    session = session,
                                    deviceAuthenticated = deviceAuthenticated,
                                    bleState = bleState,
                                    unitModel = unitModel,
                                    unitMode = unitMode,
                                    controller = bleController,
                                    operationStore = operationStore,
                                    deviceReadStore = deviceReadStore,
                                    snackbar = snackbar,
                                    navigation = navigation,
                                    query = operationsQuery,
                                    filters = operationsFilters,
                                    onQueryChanged = onOperationsQueryChanged,
                                    onFiltersChanged = onOperationsFiltersChanged,
                                    listState = operationsListState,
                                    latestExecutionResult = latestOperationExecutionResult,
                                    onLatestExecutionResultChanged = {
                                        latestOperationExecutionResult = it
                                    },
                                    onOpenCommandConsole = navigation::openCommandConsole,
                                )
                            }
                        }
                    },
                )
            }
            }
        }
    }
}

@Composable
private fun OperationsRouteContent(
    selectedOperation: RecoveredDeviceOperation?,
    session: Session,
    deviceAuthenticated: Boolean,
    bleState: BleSessionState,
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode?,
    controller: BleController,
    operationStore: LocalOperationStore,
    deviceReadStore: LocalDeviceReadStore,
    snackbar: SnackbarHostState,
    navigation: KarrNavigationState,
    query: String,
    filters: OperationFilters,
    onQueryChanged: (String) -> Unit,
    onFiltersChanged: (OperationFilters) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    latestExecutionResult: OperationExecutionResult?,
    onLatestExecutionResultChanged: (OperationExecutionResult) -> Unit,
    onOpenCommandConsole: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val executor = remember(controller, operationStore, deviceReadStore, snackbar) {
        DeviceOperationExecutor(
            controller = controller,
            operationStore = operationStore,
            deviceReadStore = deviceReadStore,
            snackbar = snackbar,
        )
    }
    var executingOperationId by remember { mutableStateOf<String?>(null) }
    AdaptiveOperationsScreen(
        session = session,
        selectedOperation = selectedOperation,
        deviceAuthenticated = deviceAuthenticated,
        unitModel = unitModel,
        unitMode = unitMode,
        query = query,
        filters = filters,
        listState = listState,
        onQueryChanged = onQueryChanged,
        onFiltersChanged = onFiltersChanged,
        onOpenCommandConsole = onOpenCommandConsole,
        onOpenOperation = navigation::openOperation,
        onExecuteOperation = { operation ->
            if (executingOperationId == null) {
                executingOperationId = operation.id
                scope.launch {
                    try {
                        executor.execute(
                            operation = operation,
                            session = session,
                            deviceAuthenticated = deviceAuthenticated,
                            unitModel = unitModel,
                            unitMode = unitMode,
                            callbacks = if (operation.mutating) {
                                OperationExecutionCallbacks()
                            } else {
                                var status: String? = null
                                var decodedResponse: com.karr.key.protocol.QtDecodedResponse? = null
                                var rawResponse: String? = null
                                OperationExecutionCallbacks(
                                    onStatus = { value -> status = value },
                                    onDecodedResponse = { value -> decodedResponse = value },
                                    onRawResponse = { value -> rawResponse = value },
                                    onSucceeded = {
                                        onLatestExecutionResultChanged(
                                            OperationExecutionResult(
                                                operationId = operation.id,
                                                status = status,
                                                decodedResponse = decodedResponse,
                                                rawResponse = rawResponse,
                                            ),
                                        )
                                        navigation.openOperation(operation)
                                    },
                                )
                            },
                        )
                    } finally {
                        executingOperationId = null
                    }
                }
            }
        },
        executingOperationId = executingOperationId,
        detailContent = { operation ->
            DeviceOperationScreen(
                session = session,
                operation = operation,
                deviceAuthenticated = deviceAuthenticated,
                bleState = bleState,
                unitModel = unitModel,
                unitMode = unitMode,
                controller = controller,
                operationStore = operationStore,
                deviceReadStore = deviceReadStore,
                snackbar = snackbar,
                onOpenOperation = navigation::openOperation,
                initialExecutionResult = latestExecutionResult,
            )
        },
    )
}

@Composable
internal fun DashboardScreen(
    session: Session,
    bleState: BleSessionState,
    deviceAuthenticated: Boolean,
    onOpenDevice: () -> Unit,
    onOpenAccessKeys: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var watchDogsTapCount by rememberSaveable { mutableIntStateOf(0) }
    var watchDogsMessage by remember { mutableStateOf<String?>(null) }
    var watchDogsMessageRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(watchDogsMessageRevision) {
        if (watchDogsMessage != null) {
            delay(WATCH_DOGS_COUNTDOWN_MILLIS)
            watchDogsMessage = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        ResponsiveContent(Modifier.fillMaxSize()) { contentModifier ->
            Column(
                modifier = contentModifier
                    .fillMaxSize()
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home-device-card"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 170.dp)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Device",
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home-device-title"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            Button(
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("home-device-action"),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                onClick = if (session.roles.isEmpty()) {
                                    onOpenAccessKeys
                                } else {
                                    onOpenDevice
                                },
                            ) {
                                if (session.roles.isNotEmpty() && !deviceAuthenticated) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_wifi),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Text(
                                    text = if (session.roles.isEmpty()) {
                                        "Set up access"
                                    } else if (deviceAuthenticated) {
                                        "View device"
                                    } else {
                                        "Scan"
                                    },
                                    modifier = if (
                                        session.roles.isNotEmpty() && !deviceAuthenticated
                                    ) {
                                        Modifier.padding(start = 6.dp)
                                    } else {
                                        Modifier
                                    },
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                    val status = bleState.homeStatus(
                        accessConfigured = session.roles.isNotEmpty(),
                    )
                    Text(
                        status,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home-device-status")
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = status
                            },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "What is KARR/SWDS?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "KARR/SWDS security devices are dealer-installed vehicle alarm " +
                                "and control modules. They connect to door locks, the alarm, and " +
                                "other vehicle functions, and communicate with phone apps over " +
                                "Bluetooth. Many owners do not know one is installed in their " +
                                "vehicle.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Why this matters",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "These devices use universal authentication—the same access " +
                                "works across devices—instead of credentials unique to each " +
                                "vehicle. This app demonstrates why that can expose vehicle " +
                                "controls. Technical details and supporting research are available " +
                                "below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { uriHandler.openUri(UCSD_SECURITY_RESEARCH_URL) },
                        ) {
                            Text("Security research")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { uriHandler.openUri(KARR_FIRMWARE_UPDATE_URL) },
                        ) {
                            Text("Firmware updates")
                        }
                    }
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("credits-attribution")
                            .pointerInput(watchDogsTapCount) {
                                detectTapGestures {
                                    val progress = advanceWatchDogsMode(watchDogsTapCount)
                                    watchDogsTapCount = progress.nextTapCount
                                    if (progress.activated) {
                                        watchDogsMessage = null
                                        Toast.makeText(
                                            context,
                                            progress.message,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        uriHandler.openUri(WATCH_DOGS_MODE_URL)
                                    } else {
                                        watchDogsMessage = progress.message
                                        watchDogsMessageRevision++
                                    }
                                }
                            },
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.watch_dogs_mark),
                            contentDescription = "NeedNotApply profile picture",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(72.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                "Developed by",
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "NeedNotApply",
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "for The Pirates' Plunder",
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Image(
                            painter = painterResource(R.drawable.pirates_plunder_mark),
                            contentDescription = "The Pirates' Plunder skull and crossed tusks",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(72.dp),
                        )
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            uriHandler.openUri("https://www.discord.gg/thepirates")
                        },
                    ) {
                        Text("Join us on Discord")
                    }
                }
            }
        }
        }

        watchDogsMessage?.let { message ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag("watch-dogs-countdown")
                    .semantics {
                        liveRegion = LiveRegionMode.Assertive
                        stateDescription = message
                    },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                tonalElevation = 6.dp,
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MissingOperationState(onBack: () -> Unit) {
    ResponsiveContent(Modifier.fillMaxSize()) { contentModifier ->
        Column(
            modifier = contentModifier.padding(top = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(
                title = "Operation unavailable",
                supportingText = "This operation is no longer in the local catalog.",
            )
            Button(onClick = onBack) {
                Text("Back to operations")
            }
        }
    }
}

internal fun BleSessionState.homeStatus(accessConfigured: Boolean = true): String {
    if (!accessConfigured && (this is BleSessionState.Idle || this is BleSessionState.Scanning)) {
        return "Enter an access key before connecting to a KARR unit."
    }
    return when (this) {
        BleSessionState.Idle -> "Ready to scan."
        BleSessionState.Scanning -> "Scanning for compatible KARR units."
        is BleSessionState.Reconnecting ->
            "Reconnecting to $serial (attempt $attempt of $maxAttempts): $step."
        is BleSessionState.ReconnectFailed ->
            "Automatic reconnect to $serial stopped: $message"
        is BleSessionState.Connecting -> "Connecting to $address."
        is BleSessionState.DiscoveringUart -> "Discovering the KARR UART service."
        is BleSessionState.UartSubscribed ->
            "Bluetooth connected; waiting for unit authentication."
        is BleSessionState.AuthenticationRequired ->
            "The unit requested application authentication."
        is BleSessionState.Authenticating -> "Authenticating the connected unit."
        is BleSessionState.Ready -> listOfNotNull(
            unitModel?.displayName ?: "Model not identified",
            unitMode?.let { mode -> "${mode.displayName} mode" },
        ).joinToString(" • ")
        is BleSessionState.Error -> "Device error: $message"
    }
}

internal fun BleSessionState.shouldStartHomeScan(): Boolean =
    this is BleSessionState.Idle ||
        this is BleSessionState.Error ||
        this is BleSessionState.ReconnectFailed

internal fun shellTopBarTitle(
    route: AppRoute,
    operationTitle: String? = null,
): String? = when (route) {
    AppRoute.Home,
    AppRoute.Operations,
    AppRoute.Map,
    is AppRoute.Settings,
    AppRoute.Device,
    -> null
    is AppRoute.OperationDetail -> operationTitle ?: "Operation"
    AppRoute.CommandConsole -> "Command console"
}

// Map owns horizontal drag for panning/zooming, so no gesture is installed at all while it's the
// active tab — that's what keeps swiping able to enter Map but never leave it by drag.
@Composable
private fun swipeNavigationModifier(navigation: KarrNavigationState): Modifier {
    if (navigation.selectedTopLevel == TopLevelDestination.MAP) return Modifier
    val thresholdPx = with(LocalDensity.current) { SWIPE_NAVIGATION_THRESHOLD.toPx() }
    return Modifier.pointerInput(navigation.selectedTopLevel, navigation.currentRoute) {
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onHorizontalDrag = { change, dragAmount ->
                totalDrag += dragAmount
                change.consume()
            },
            onDragEnd = {
                when {
                    totalDrag <= -thresholdPx -> navigation.swipeForward()
                    totalDrag >= thresholdPx -> navigation.swipeBackward()
                }
            },
        )
    }
}

private fun horizontalPageSlide(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return slideInHorizontally(
        animationSpec = tween(PAGE_SLIDE_DURATION_MILLIS),
        initialOffsetX = { fullWidth -> direction * fullWidth },
    ) togetherWith slideOutHorizontally(
        animationSpec = tween(PAGE_SLIDE_DURATION_MILLIS),
        targetOffsetX = { fullWidth -> -direction * fullWidth },
    )
}

private const val PAGE_SLIDE_DURATION_MILLIS = 300
private val SWIPE_NAVIGATION_THRESHOLD = 56.dp
// Re-read/rotate interval per unit. 20s keeps read-speed testing responsive on one or two units
// while still rotating past a just-read unit to others in range; raise it for field surveying.
private const val AUTO_SWEEP_COOLDOWN_MILLIS = 20 * 1_000L

// Map recorder throttle: re-record a unit only after ~8 m of movement, or every 15 s regardless,
// so a stationary unit refreshes its last-seen without flooding the DB.
private const val MAP_RECORD_MIN_METERS = 8.0
private const val MAP_RECORD_MAX_INTERVAL_MILLIS = 15_000L
private const val METERS_PER_DEGREE = 111_320.0

private data class RecorderMark(
    val elapsedRealtimeMillis: Long,
    val latitude: Double,
    val longitude: Double,
)

private fun approxDistanceMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val avgLat = Math.toRadians((lat1 + lat2) / 2.0)
    val dx = (lon2 - lon1) * Math.cos(avgLat) * METERS_PER_DEGREE
    val dy = (lat2 - lat1) * METERS_PER_DEGREE
    return Math.sqrt(dx * dx + dy * dy)
}
