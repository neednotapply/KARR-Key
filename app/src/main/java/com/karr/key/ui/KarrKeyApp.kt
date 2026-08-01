package com.karr.key.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalUriHandler
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
import com.karr.key.ble.BleController
import com.karr.key.ble.BleSessionState
import com.karr.key.core.ResearchAccess
import com.karr.key.core.Session
import com.karr.key.data.LocalOperationStore
import com.karr.key.data.KeyRepository
import com.karr.key.protocol.DeviceKeyPurpose
import com.karr.key.protocol.DeviceKeyProvisioning
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredWorkflowCatalog
import kotlinx.coroutines.launch

private const val KARR_FIRMWARE_UPDATE_URL =
    "https://www.karrsecurity.com/karr-security-firmware-update-instructions"
private const val UCSD_SECURITY_RESEARCH_URL =
    "https://today.ucsd.edu/story/2-million-cars-with-anti-theft-systems-installed-by-dealers-are-at-higher-risk-of-theft"

@Composable
fun KarrKeyApp(
    repository: KeyRepository,
    bleController: BleController,
    deviceKeys: DeviceKeyProvisioning,
    operationStore: LocalOperationStore,
    requestBlePermissions: (((Boolean) -> Unit) -> Unit),
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
    val unitModel = (bleState as? BleSessionState.Ready)?.unitModel
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

    LaunchedEffect(deviceAuthenticated) {
        navigation.openOperationsAfterSuccessfulConnection(
            wasAuthenticated = wasDeviceAuthenticated,
            isAuthenticated = deviceAuthenticated,
        )
        wasDeviceAuthenticated = deviceAuthenticated
    }

    KeyShell(
        session = session,
        deviceAuthenticated = deviceAuthenticated,
        unitModel = unitModel,
        bleState = bleState,
        navigation = navigation,
        bleController = bleController,
        deviceKeys = deviceKeys,
        operationStore = operationStore,
        onProvisioningChanged = { provisioningRevision++ },
        requestBlePermissions = requestBlePermissions,
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
    bleState: BleSessionState,
    navigation: KarrNavigationState,
    bleController: BleController,
    deviceKeys: DeviceKeyProvisioning,
    operationStore: LocalOperationStore,
    onProvisioningChanged: () -> Unit,
    requestBlePermissions: (((Boolean) -> Unit) -> Unit),
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
    val topBarTitle = when (navigation.currentRoute) {
        AppRoute.Home -> "KARR Key"
        AppRoute.Operations -> "Device operations"
        is AppRoute.Device -> "Device"
        is AppRoute.OperationDetail -> currentOperation?.label ?: "Operation"
    }
    val topLevelSwipeEnabled = navigation.currentRoute !is AppRoute.OperationDetail

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
                TopAppBar(
                    title = {
                        Text(
                            text = topBarTitle,
                            maxLines = 1,
                        )
                    },
                    navigationIcon = {
                        if (navigation.currentRoute is AppRoute.OperationDetail) {
                            IconButton(onClick = { navigation.navigateUp() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_back),
                                    contentDescription = "Navigate up",
                                )
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .pointerInput(topLevelSwipeEnabled) {
                        if (!topLevelSwipeEnabled) return@pointerInput
                        var totalHorizontalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalHorizontalDrag = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                totalHorizontalDrag += dragAmount
                            },
                            onDragEnd = {
                                when {
                                    totalHorizontalDrag <= -TOP_LEVEL_SWIPE_THRESHOLD.toPx() ->
                                        navigation.moveTopLevelBy(1)
                                    totalHorizontalDrag >= TOP_LEVEL_SWIPE_THRESHOLD.toPx() ->
                                        navigation.moveTopLevelBy(-1)
                                }
                            },
                            onDragCancel = { totalHorizontalDrag = 0f },
                        )
                    },
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
                                    navigation.openDevice(DeviceSection.CONNECTION)
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
                                    navigation.openDevice(DeviceSection.ACCESS_KEYS)
                                },
                            )
                        }
                        entry<AppRoute.Operations> {
                            OperationsRouteContent(
                                selectedOperation = null,
                                session = session,
                                deviceAuthenticated = deviceAuthenticated,
                                unitModel = unitModel,
                                controller = bleController,
                                operationStore = operationStore,
                                snackbar = snackbar,
                                navigation = navigation,
                                query = operationsQuery,
                                filters = operationsFilters,
                                onQueryChanged = onOperationsQueryChanged,
                                onFiltersChanged = onOperationsFiltersChanged,
                                listState = operationsListState,
                            )
                        }
                        entry<AppRoute.Device> { route ->
                            DeviceCenterScreen(
                                controller = bleController,
                                deviceKeys = deviceKeys,
                                section = route.section,
                                onSectionSelected = navigation::updateDeviceSection,
                                onProvisioningChanged = onProvisioningChanged,
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
                                    unitModel = unitModel,
                                    controller = bleController,
                                    operationStore = operationStore,
                                    snackbar = snackbar,
                                    navigation = navigation,
                                    query = operationsQuery,
                                    filters = operationsFilters,
                                    onQueryChanged = onOperationsQueryChanged,
                                    onFiltersChanged = onOperationsFiltersChanged,
                                    listState = operationsListState,
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
    unitModel: KarrUnitModel?,
    controller: BleController,
    operationStore: LocalOperationStore,
    snackbar: SnackbarHostState,
    navigation: KarrNavigationState,
    query: String,
    filters: OperationFilters,
    onQueryChanged: (String) -> Unit,
    onFiltersChanged: (OperationFilters) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    AdaptiveOperationsScreen(
        session = session,
        selectedOperation = selectedOperation,
        deviceAuthenticated = deviceAuthenticated,
        unitModel = unitModel,
        query = query,
        filters = filters,
        listState = listState,
        onQueryChanged = onQueryChanged,
        onFiltersChanged = onFiltersChanged,
        onOpenOperation = navigation::openOperation,
        detailContent = { operation ->
            DeviceOperationScreen(
                session = session,
                operation = operation,
                deviceAuthenticated = deviceAuthenticated,
                unitModel = unitModel,
                controller = controller,
                operationStore = operationStore,
                snackbar = snackbar,
            )
        },
    )
}

@Composable
private fun DashboardScreen(
    session: Session,
    bleState: BleSessionState,
    deviceAuthenticated: Boolean,
    onOpenDevice: () -> Unit,
    onOpenAccessKeys: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    ResponsiveContent(Modifier.fillMaxSize()) { contentModifier ->
        LazyColumn(
            modifier = contentModifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 16.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "What this demonstrates",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "How devices are found and reached",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "KARR advertisements are not limited to DR/QT names. " +
                                "Recovered Dealer units can also carry serial and model " +
                                "status in manufacturer data, so KARR Key evaluates every " +
                                "nearby BLE advertisement using names, raw manufacturer " +
                                "frames, and service UUIDs.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Why control is so broad",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Because those units sit on the vehicle's CAN bus and older KARR apps shipped with built-in authentication secrets, a client that passes legacy authentication can reach most vehicle-control functions exposed by the unit.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { uriHandler.openUri(UCSD_SECURITY_RESEARCH_URL) },
                        ) {
                            Text("Read the security research")
                        }
                        OutlinedButton(
                            onClick = { uriHandler.openUri(KARR_FIRMWARE_UPDATE_URL) },
                        ) {
                            Text("Firmware update instructions")
                        }
                    }
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Device",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val status = bleState.homeStatus()
                        Text(
                            status,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = status
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = if (session.roles.isEmpty()) {
                                onOpenAccessKeys
                            } else {
                                onOpenDevice
                            },
                        ) {
                            Text(
                                if (session.roles.isEmpty()) {
                                    "Set up access"
                                } else if (deviceAuthenticated) {
                                    "View device"
                                } else {
                                    "Connect device"
                                },
                            )
                        }
                    }
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.pirates_plunder_mark),
                            contentDescription = "The Pirates' Plunder skull and crossed tusks",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .sizeIn(maxWidth = 180.dp, maxHeight = 180.dp)
                                .aspectRatio(966f / 876f),
                        )
                        Text(
                            "Developed by NeedNotApply",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "for The Pirates' Plunder",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        OutlinedButton(
                            onClick = {
                                uriHandler.openUri("https://www.discord.gg/thepirates")
                            },
                        ) {
                            Text("Join us online")
                        }
                    }
                }
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

private fun BleSessionState.homeStatus(): String = when (this) {
    BleSessionState.Idle -> "Ready to scan for a compatible KARR unit."
    BleSessionState.Scanning -> "Scanning for compatible KARR units."
    is BleSessionState.Reconnecting ->
        "Reconnecting to $serial (attempt $attempt of $maxAttempts): $step."
    is BleSessionState.ReconnectFailed ->
        "Automatic reconnect to $serial stopped: $message"
    is BleSessionState.Connecting -> "Connecting to $address."
    is BleSessionState.DiscoveringUart -> "Discovering the KARR UART service."
    is BleSessionState.UartSubscribed -> "Bluetooth connected; waiting for unit authentication."
    is BleSessionState.AuthenticationRequired -> "The unit requested application authentication."
    is BleSessionState.Authenticating -> "Authenticating the connected unit."
    is BleSessionState.Ready ->
        unitModel?.let { model ->
            "Device authenticated and ready: ${model.displayName}."
        } ?: "Device authenticated and ready; model not identified."
    is BleSessionState.Error -> "Device error: $message"
}

internal fun BleSessionState.shouldStartHomeScan(): Boolean =
    this is BleSessionState.Idle ||
        this is BleSessionState.Error ||
        this is BleSessionState.ReconnectFailed

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

private val TOP_LEVEL_SWIPE_THRESHOLD = 72.dp
private const val PAGE_SLIDE_DURATION_MILLIS = 300
