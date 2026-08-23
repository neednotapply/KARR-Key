package com.karr.key.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.karr.key.ble.BleController
import com.karr.key.ble.BleSessionState
import com.karr.key.core.ResearchAccess
import com.karr.key.core.Session
import com.karr.key.data.KeyRepository
import com.karr.key.data.MapHistoryImportSummary
import com.karr.key.desktop.data.DesktopAutoConnectPreferences
import com.karr.key.desktop.data.DesktopDeviceProfileStore
import com.karr.key.desktop.data.DesktopDeviceReadStore
import com.karr.key.desktop.data.DesktopMapHistoryStore
import com.karr.key.desktop.data.DesktopOperationStore
import com.karr.key.desktop.map.DesktopMapScreen
import com.karr.key.desktop.map.GeoPoint
import com.karr.key.desktop.resources.Res
import com.karr.key.desktop.resources.ic_arrow_back
import com.karr.key.desktop.terminal.TerminalPanel
import com.karr.key.desktop.terminal.TraceLog
import com.karr.key.protocol.DeviceKeyProvisioning
import com.karr.key.protocol.DeviceKeyPurpose
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.KarrVulnerabilityStatus
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredWorkflowCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

/**
 * The desktop shell. Mirrors the Android KarrKeyApp wiring (session derivation,
 * per-connection vulnerability tracking, operation execution, screen routing)
 * but with a mouse-first navigation rail and a collapsible Terminal dock instead
 * of the mobile navigation bar and swipe gestures. Real BLE permissions are an
 * OS concern the native controller handles, so the desktop permission shim just
 * grants.
 */
@Composable
fun KarrDesktopApp(
    repository: KeyRepository,
    controller: BleController,
    deviceKeys: DeviceKeyProvisioning,
    operationStore: DesktopOperationStore,
    mapHistoryStore: DesktopMapHistoryStore,
    deviceReadStore: DesktopDeviceReadStore,
    deviceProfileStore: DesktopDeviceProfileStore,
    autoConnectPreferences: DesktopAutoConnectPreferences,
    traceLog: TraceLog,
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
    val bleState by controller.state.collectAsState()
    val deviceAuthenticated = bleState is BleSessionState.Ready
    val readyState = bleState as? BleSessionState.Ready
    val unitModel = readyState?.unitModel
    val unitMode = readyState?.unitMode

    val snackbar = remember { SnackbarHostState() }
    val navigation = remember { KarrNavigationState() }
    val scope = rememberCoroutineScope()
    var terminalVisible by remember { mutableStateOf(false) }
    // Hoisted so the map's estimated location survives leaving and returning to the Map tab.
    var mapLocation by remember { mutableStateOf<GeoPoint?>(null) }

    var autoConnectReadEnabled by remember { mutableStateOf(autoConnectPreferences.enabled) }
    var autoConnectSendOperationId by remember { mutableStateOf(autoConnectPreferences.operationId) }
    var operationsQuery by remember { mutableStateOf("") }
    var operationsFilters by remember { mutableStateOf(OperationFilters()) }
    val operationsListState = rememberLazyListState()

    val vulnerabilityBadges = remember { mutableStateMapOf<String, KarrVulnerabilityStatus>() }
    LaunchedEffect(deviceProfileStore) {
        withContext(Dispatchers.IO) { deviceProfileStore.all() }.forEach { (address, profile) ->
            if (profile.vulnerability != KarrVulnerabilityStatus.UNKNOWN) {
                vulnerabilityBadges[address.uppercase()] = profile.vulnerability
            }
        }
    }

    // Record what each connection reveals — vulnerability (challenge width) and, once Ready, the
    // model — for the Device badges and the map profile, matching the Android behaviour.
    LaunchedEffect(controller) {
        controller.state.collect { current ->
            when (current) {
                is BleSessionState.AuthenticationRequired -> {
                    val vulnerability =
                        KarrVulnerabilityStatus.fromChallengeBytes(current.challengeBytes)
                    val key = current.address.uppercase()
                    if (vulnerability != KarrVulnerabilityStatus.UNKNOWN &&
                        vulnerabilityBadges[key] != vulnerability
                    ) {
                        vulnerabilityBadges[key] = vulnerability
                        withContext(Dispatchers.IO) {
                            deviceProfileStore.record(current.address, null, vulnerability)
                        }
                    }
                }
                is BleSessionState.Ready -> {
                    val key = current.address.uppercase()
                    vulnerabilityBadges[key] = KarrVulnerabilityStatus.VULNERABLE
                    withContext(Dispatchers.IO) {
                        deviceProfileStore.record(
                            current.address,
                            current.unitModel?.code,
                            KarrVulnerabilityStatus.VULNERABLE,
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    val requestPermissions: (((Boolean) -> Unit) -> Unit) = { onResult -> onResult(true) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = navigation.selectedTopLevel == destination,
                        onClick = { navigation.selectTopLevel(destination) },
                        icon = {
                            Icon(
                                painter = painterResource(destination.icon),
                                contentDescription = null,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                DesktopTopBar(
                    navigation = navigation,
                    operationTitle = (navigation.currentRoute as? AppRoute.OperationDetail)
                        ?.let { RecoveredWorkflowCatalog.operationById(it.operationId)?.label },
                    terminalVisible = terminalVisible,
                    onToggleTerminal = { terminalVisible = !terminalVisible },
                )
                HorizontalDivider()

                Scaffold(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    snackbarHost = { SnackbarHost(snackbar) },
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when (val route = navigation.currentRoute) {
                            AppRoute.Home -> DesktopDashboard(
                                session = session,
                                bleState = bleState,
                                deviceAuthenticated = deviceAuthenticated,
                                onOpenDevice = {
                                    navigation.openDevice()
                                    if (bleState.shouldStartHomeScan()) controller.startScan()
                                },
                                onOpenAccessKeys = { navigation.openSettings(SettingsSection.ACCESS_KEYS) },
                            )
                            AppRoute.Device -> DeviceCenterScreen(
                                controller = controller,
                                vulnerabilityByAddress = vulnerabilityBadges,
                                autoConnectReadEnabled = autoConnectReadEnabled,
                                onAutoConnectReadEnabledChanged = {
                                    autoConnectReadEnabled = it
                                    autoConnectPreferences.enabled = it
                                },
                                autoConnectSendOperationId = autoConnectSendOperationId,
                                onAutoConnectSendOperationSelected = {
                                    autoConnectSendOperationId = it
                                    autoConnectPreferences.operationId = it
                                },
                                requestPermissions = requestPermissions,
                                snackbar = snackbar,
                                modifier = Modifier.fillMaxSize(),
                            )
                            AppRoute.Operations -> OperationsRouteContent(
                                selectedOperation = null,
                                session = session,
                                deviceAuthenticated = deviceAuthenticated,
                                bleState = bleState,
                                unitModel = unitModel,
                                unitMode = unitMode,
                                controller = controller,
                                operationStore = operationStore,
                                deviceReadStore = deviceReadStore,
                                snackbar = snackbar,
                                navigation = navigation,
                                query = operationsQuery,
                                filters = operationsFilters,
                                onQueryChanged = { operationsQuery = it },
                                onFiltersChanged = { operationsFilters = it },
                                listState = operationsListState,
                            )
                            is AppRoute.OperationDetail -> {
                                val operation = RecoveredWorkflowCatalog.operationById(route.operationId)
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
                                        controller = controller,
                                        operationStore = operationStore,
                                        deviceReadStore = deviceReadStore,
                                        snackbar = snackbar,
                                        navigation = navigation,
                                        query = operationsQuery,
                                        filters = operationsFilters,
                                        onQueryChanged = { operationsQuery = it },
                                        onFiltersChanged = { operationsFilters = it },
                                        listState = operationsListState,
                                    )
                                }
                            }
                            AppRoute.CommandConsole -> CommandConsoleScreen(
                                controller = controller,
                                deviceAuthenticated = deviceAuthenticated,
                                session = session,
                                unitModel = unitModel,
                                unitMode = unitMode,
                                modifier = Modifier.fillMaxSize(),
                            )
                            is AppRoute.Settings -> SettingsScreen(
                                controller = controller,
                                deviceKeys = deviceKeys,
                                mapHistoryStore = mapHistoryStore,
                                deviceReadStore = deviceReadStore,
                                deviceProfileStore = deviceProfileStore,
                                section = route.section,
                                onSectionSelected = navigation::updateSettingsSection,
                                onProvisioningChanged = { provisioningRevision++ },
                                requestMapHistoryExport = requestMapHistoryExport,
                                requestMapHistoryImport = requestMapHistoryImport,
                                snackbar = snackbar,
                                modifier = Modifier.fillMaxSize(),
                            )
                            AppRoute.Map -> DesktopMapScreen(
                                controller = controller,
                                mapHistoryStore = mapHistoryStore,
                                deviceReadStore = deviceReadStore,
                                deviceProfileStore = deviceProfileStore,
                                snackbar = snackbar,
                                myLocation = mapLocation,
                                onMyLocationChange = { mapLocation = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                if (terminalVisible) {
                    HorizontalDivider()
                    TerminalPanel(
                        log = traceLog,
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        onClose = { terminalVisible = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopTopBar(
    navigation: KarrNavigationState,
    operationTitle: String?,
    terminalVisible: Boolean,
    onToggleTerminal: () -> Unit,
) {
    val route = navigation.currentRoute
    val title = shellTopBarTitle(route, operationTitle)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (route is AppRoute.OperationDetail || route is AppRoute.CommandConsole) {
            IconButton(onClick = { navigation.navigateUp() }) {
                Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = "Navigate up")
            }
        }
        Text("KARR Key", style = MaterialTheme.typography.titleMedium)
        title?.let {
            Text(
                "  ·  $it",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(modifier = Modifier.weight(1f))
        TextButton(onClick = onToggleTerminal) {
            Text(if (terminalVisible) "Hide terminal" else "Show terminal")
        }
    }
}

@Composable
private fun MapPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Map", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The desktop map (OpenStreetMap tiles, RSSI estimation, WiGLE import/export) is ported " +
                "in the next milestone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DesktopDashboard(
    session: Session,
    bleState: BleSessionState,
    deviceAuthenticated: Boolean,
    onOpenDevice: () -> Unit,
    onOpenAccessKeys: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    ResponsiveContent(Modifier.fillMaxSize()) { contentModifier ->
        Column(
            modifier = contentModifier.fillMaxSize().padding(vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Device",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Button(
                            onClick = if (session.roles.isEmpty()) onOpenAccessKeys else onOpenDevice,
                        ) {
                            Text(
                                when {
                                    session.roles.isEmpty() -> "Set up access"
                                    deviceAuthenticated -> "View device"
                                    else -> "Scan"
                                },
                            )
                        }
                    }
                    Text(
                        bleState.homeStatus(accessConfigured = session.roles.isNotEmpty()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "What is KARR/SWDS?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "KARR/SWDS security devices are dealer-installed vehicle alarm and control " +
                            "modules. They connect to door locks, the alarm, and other vehicle " +
                            "functions, and communicate with phone apps over Bluetooth. Many owners " +
                            "do not know one is installed in their vehicle.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "These devices use universal authentication—the same access works across " +
                            "devices—instead of credentials unique to each vehicle. This app " +
                            "demonstrates why that can expose vehicle controls.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                uriHandler.openUri(
                                    "https://today.ucsd.edu/story/2-million-cars-with-anti-theft-" +
                                        "systems-installed-by-dealers-are-at-higher-risk-of-theft",
                                )
                            },
                        ) { Text("Security research") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                uriHandler.openUri(
                                    "https://www.karrsecurity.com/karr-security-firmware-update-instructions",
                                )
                            },
                        ) { Text("Firmware updates") }
                    }
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
    operationStore: DesktopOperationStore,
    deviceReadStore: DesktopDeviceReadStore,
    snackbar: SnackbarHostState,
    navigation: KarrNavigationState,
    query: String,
    filters: OperationFilters,
    onQueryChanged: (String) -> Unit,
    onFiltersChanged: (OperationFilters) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
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
    var latestExecutionResult by remember { mutableStateOf<OperationExecutionResult?>(null) }
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
        onOpenCommandConsole = navigation::openCommandConsole,
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
                                        latestExecutionResult = OperationExecutionResult(
                                            operationId = operation.id,
                                            status = status,
                                            decodedResponse = decodedResponse,
                                            rawResponse = rawResponse,
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
            Button(onClick = onBack) { Text("Back to operations") }
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
        is BleSessionState.ReconnectFailed -> "Automatic reconnect to $serial stopped: $message"
        is BleSessionState.Connecting -> "Connecting to $address."
        is BleSessionState.DiscoveringUart -> "Discovering the KARR UART service."
        is BleSessionState.UartSubscribed -> "Bluetooth connected; waiting for unit authentication."
        is BleSessionState.AuthenticationRequired -> "The unit requested application authentication."
        is BleSessionState.Authenticating -> "Authenticating the connected unit."
        is BleSessionState.Ready -> listOfNotNull(
            unitModel?.displayName ?: "Model not identified",
            unitMode?.let { mode -> "${mode.displayName} mode" },
        ).joinToString(" • ")
        is BleSessionState.PatchedUnitDetected ->
            "Patched unit — it refused the legacy handshake and is not vulnerable to this technique."
        is BleSessionState.Error -> "Device error: $message"
    }
}

internal fun BleSessionState.shouldStartHomeScan(): Boolean =
    this is BleSessionState.Idle ||
        this is BleSessionState.Error ||
        this is BleSessionState.ReconnectFailed

internal fun shellTopBarTitle(route: AppRoute, operationTitle: String? = null): String? = when (route) {
    AppRoute.Home, AppRoute.Operations, AppRoute.Map, is AppRoute.Settings, AppRoute.Device -> null
    is AppRoute.OperationDetail -> operationTitle ?: "Operation"
    AppRoute.CommandConsole -> "Command console"
}
