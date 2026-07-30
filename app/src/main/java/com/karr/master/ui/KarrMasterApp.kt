package com.karr.master.ui

import androidx.compose.foundation.Image
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
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.karr.master.R
import com.karr.master.ble.BleController
import com.karr.master.ble.BleSessionState
import com.karr.master.core.FeatureCatalog
import com.karr.master.core.FeatureDefinition
import com.karr.master.core.ResearchAccess
import com.karr.master.core.Session
import com.karr.master.core.TechnicianJob
import com.karr.master.core.UserRole
import com.karr.master.core.VehicleSummary
import com.karr.master.data.LocalOperationStore
import com.karr.master.data.MasterRepository
import com.karr.master.protocol.DeviceKeyPurpose
import com.karr.master.protocol.DeviceKeyProvisioning

@Composable
fun KarrMasterApp(
    repository: MasterRepository,
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
    val vehicles = remember(repository) { repository.vehicles() }
    val jobs = remember(repository) { repository.jobs() }
    val bleState by bleController.state.collectAsState()
    val deviceAuthenticated = bleState is BleSessionState.Ready
    val snackbar = remember { SnackbarHostState() }
    val navigation = rememberSaveable(saver = KarrNavigationState.Saver) {
        KarrNavigationState()
    }
    var toolsQuery by rememberSaveable { mutableStateOf("") }
    var toolsCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val customerListState = rememberLazyListState()
    val dealerListState = rememberLazyListState()
    val installerListState = rememberLazyListState()

    LaunchedEffect(session.roles) {
        navigation.normalizeRoles(session.roles)
        val route = navigation.stackFor(TopLevelDestination.TOOLS).lastOrNull()
        if (route is AppRoute.Tools && route.role == null && session.roles.isNotEmpty()) {
            navigation.updateToolsRole(
                UserRole.entries.firstOrNull(session.roles::contains),
            )
        }
    }

    MasterShell(
        session = session,
        deviceAuthenticated = deviceAuthenticated,
        bleState = bleState,
        navigation = navigation,
        vehicles = vehicles,
        jobs = jobs,
        bleController = bleController,
        deviceKeys = deviceKeys,
        operationStore = operationStore,
        onProvisioningChanged = { provisioningRevision++ },
        requestBlePermissions = requestBlePermissions,
        snackbar = snackbar,
        toolsQuery = toolsQuery,
        toolsCategory = toolsCategory,
        onToolsQueryChanged = { toolsQuery = it },
        onToolsCategoryChanged = { toolsCategory = it },
        customerListState = customerListState,
        dealerListState = dealerListState,
        installerListState = installerListState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MasterShell(
    session: Session,
    deviceAuthenticated: Boolean,
    bleState: BleSessionState,
    navigation: KarrNavigationState,
    vehicles: List<VehicleSummary>,
    jobs: List<TechnicianJob>,
    bleController: BleController,
    deviceKeys: DeviceKeyProvisioning,
    operationStore: LocalOperationStore,
    onProvisioningChanged: () -> Unit,
    requestBlePermissions: (((Boolean) -> Unit) -> Unit),
    snackbar: SnackbarHostState,
    toolsQuery: String,
    toolsCategory: String?,
    onToolsQueryChanged: (String) -> Unit,
    onToolsCategoryChanged: (String?) -> Unit,
    customerListState: androidx.compose.foundation.lazy.LazyListState,
    dealerListState: androidx.compose.foundation.lazy.LazyListState,
    installerListState: androidx.compose.foundation.lazy.LazyListState,
) {
    val currentFeature = (navigation.currentRoute as? AppRoute.FeatureDetail)
        ?.featureId
        ?.let { FeatureCatalog.all.findById(it) }
    val topBarTitle = when (navigation.currentRoute) {
        AppRoute.Home -> "KARR Master"
        is AppRoute.Tools -> "Tools"
        is AppRoute.Device -> "Device"
        is AppRoute.FeatureDetail -> currentFeature?.title ?: "Tool"
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
                TopAppBar(
                    title = {
                        Text(
                            text = topBarTitle,
                            maxLines = 1,
                        )
                    },
                    navigationIcon = {
                        if (navigation.currentRoute is AppRoute.FeatureDetail) {
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
                    .consumeWindowInsets(innerPadding),
            ) {
                NavDisplay(
                    backStack = navigation.currentBackStack,
                    onBack = { navigation.navigateUp() },
                    entryProvider = entryProvider {
                        entry<AppRoute.Home> {
                            DashboardScreen(
                                session = session,
                                bleState = bleState,
                                deviceAuthenticated = deviceAuthenticated,
                                vehicles = vehicles,
                                jobs = jobs,
                                onOpenRole = navigation::openTools,
                                onOpenDevice = {
                                    navigation.openDevice(DeviceSection.CONNECTION)
                                },
                                onOpenAccessKeys = {
                                    navigation.openDevice(DeviceSection.ACCESS_KEYS)
                                },
                            )
                        }
                        entry<AppRoute.Tools> { route ->
                            ToolsRouteContent(
                                route = route,
                                selectedFeature = null,
                                session = session,
                                vehicles = vehicles,
                                jobs = jobs,
                                deviceAuthenticated = deviceAuthenticated,
                                controller = bleController,
                                operationStore = operationStore,
                                snackbar = snackbar,
                                navigation = navigation,
                                query = toolsQuery,
                                category = toolsCategory,
                                onQueryChanged = onToolsQueryChanged,
                                onCategoryChanged = onToolsCategoryChanged,
                                customerListState = customerListState,
                                dealerListState = dealerListState,
                                installerListState = installerListState,
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
                        entry<AppRoute.FeatureDetail> { route ->
                            val feature = FeatureCatalog.all.findById(route.featureId)
                            if (feature == null) {
                                MissingFeatureState(onBack = { navigation.navigateUp() })
                            } else {
                                ToolsRouteContent(
                                    route = AppRoute.Tools(route.role),
                                    selectedFeature = feature,
                                    session = session,
                                    vehicles = vehicles,
                                    jobs = jobs,
                                    deviceAuthenticated = deviceAuthenticated,
                                    controller = bleController,
                                    operationStore = operationStore,
                                    snackbar = snackbar,
                                    navigation = navigation,
                                    query = toolsQuery,
                                    category = toolsCategory,
                                    onQueryChanged = onToolsQueryChanged,
                                    onCategoryChanged = onToolsCategoryChanged,
                                    customerListState = customerListState,
                                    dealerListState = dealerListState,
                                    installerListState = installerListState,
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
private fun ToolsRouteContent(
    route: AppRoute.Tools,
    selectedFeature: FeatureDefinition?,
    session: Session,
    vehicles: List<VehicleSummary>,
    jobs: List<TechnicianJob>,
    deviceAuthenticated: Boolean,
    controller: BleController,
    operationStore: LocalOperationStore,
    snackbar: SnackbarHostState,
    navigation: KarrNavigationState,
    query: String,
    category: String?,
    onQueryChanged: (String) -> Unit,
    onCategoryChanged: (String?) -> Unit,
    customerListState: androidx.compose.foundation.lazy.LazyListState,
    dealerListState: androidx.compose.foundation.lazy.LazyListState,
    installerListState: androidx.compose.foundation.lazy.LazyListState,
) {
    val availableRoles = UserRole.entries.filter(session.roles::contains)
    val selectedRole = route.role?.takeIf(availableRoles::contains)
        ?: availableRoles.firstOrNull()
    val listState = when (selectedRole) {
        UserRole.CUSTOMER -> customerListState
        UserRole.DEALER -> dealerListState
        UserRole.INSTALLER -> installerListState
        null -> customerListState
    }

    AdaptiveToolsScreen(
        session = session,
        selectedRole = selectedRole,
        selectedFeature = selectedFeature,
        vehicles = vehicles,
        jobs = jobs,
        deviceAuthenticated = deviceAuthenticated,
        query = query,
        selectedCategory = category,
        listState = listState,
        onRoleSelected = { role ->
            navigation.updateToolsRole(role)
            onCategoryChanged(null)
        },
        onQueryChanged = onQueryChanged,
        onCategorySelected = onCategoryChanged,
        onOpenFeature = navigation::openFeature,
        onOpenAccessKeys = {
            navigation.openDevice(DeviceSection.ACCESS_KEYS)
        },
        detailContent = { feature ->
            FeatureWorkflowScreen(
                session = session,
                feature = feature,
                deviceAuthenticated = deviceAuthenticated,
                controller = controller,
                operationStore = operationStore,
                snackbar = snackbar,
                onOpenDevice = {
                    navigation.openDevice(DeviceSection.CONNECTION)
                },
            )
        },
    )
}

@Composable
private fun DashboardScreen(
    session: Session,
    bleState: BleSessionState,
    deviceAuthenticated: Boolean,
    vehicles: List<VehicleSummary>,
    jobs: List<TechnicianJob>,
    onOpenRole: (UserRole) -> Unit,
    onOpenDevice: () -> Unit,
    onOpenAccessKeys: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val roleCounts = UserRole.entries.associateWith { role ->
        FeatureCatalog.forRole(role).size
    }

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
                SectionHeader(
                    title = "Vehicle access toolkit",
                    supportingText = "Vehicle access, diagnostics, and service workflows.",
                )
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
                SectionHeader(
                    title = "Workspaces",
                    supportingText = "Choose one role. Locked roles link to local key setup.",
                )
            }
            UserRole.entries.forEach { role ->
                item(key = "home-role:$role") {
                    val unlocked = role in session.roles
                    OutlinedCard(
                        onClick = {
                            if (unlocked) {
                                onOpenRole(role)
                            } else {
                                onOpenAccessKeys()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                role.homeTitle(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${roleCounts.getValue(role)} tools",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            StatusPill(
                                if (unlocked) {
                                    FeatureAvailability.Available
                                } else {
                                    FeatureAvailability.Locked(
                                        "Provision a local ${role.homeTitle().lowercase()} key.",
                                    )
                                },
                            )
                        }
                    }
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("On-device records") },
                        supportingContent = {
                            Text(
                                "${vehicles.size} vehicle records • ${jobs.size} work records. " +
                                    "Encrypted operation history also stays on this device.",
                            )
                        },
                    )
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
private fun MissingFeatureState(onBack: () -> Unit) {
    ResponsiveContent(Modifier.fillMaxSize()) { contentModifier ->
        Column(
            modifier = contentModifier.padding(top = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(
                title = "Tool unavailable",
                supportingText = "This feature is no longer in the local catalog.",
            )
            Button(onClick = onBack) {
                Text("Back to tools")
            }
        }
    }
}

private fun List<FeatureDefinition>.findById(id: String): FeatureDefinition? =
    firstOrNull { it.id == id }

private fun UserRole.homeTitle(): String = when (this) {
    UserRole.CUSTOMER -> "Customer"
    UserRole.DEALER -> "Dealer"
    UserRole.INSTALLER -> "Installer"
}

private fun BleSessionState.homeStatus(): String = when (this) {
    BleSessionState.Idle -> "Ready to scan for a compatible KARR unit."
    BleSessionState.Scanning -> "Scanning for compatible KARR units."
    is BleSessionState.Connecting -> "Connecting to $address."
    is BleSessionState.DiscoveringUart -> "Discovering the KARR UART service."
    is BleSessionState.UartSubscribed -> "Bluetooth connected; waiting for unit authentication."
    is BleSessionState.AuthenticationRequired -> "The unit requested application authentication."
    is BleSessionState.Authenticating -> "Authenticating the connected unit."
    is BleSessionState.Ready -> "Device authenticated and ready."
    is BleSessionState.Error -> "Device error: $message"
}
