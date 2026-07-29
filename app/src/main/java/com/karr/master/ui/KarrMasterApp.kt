package com.karr.master.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.karr.master.R
import com.karr.master.ble.BleController
import com.karr.master.ble.BleSessionState
import com.karr.master.core.AuthorizationDecision
import com.karr.master.core.FeatureCatalog
import com.karr.master.core.FeatureDefinition
import com.karr.master.core.RolePolicy
import com.karr.master.core.Session
import com.karr.master.core.TechnicianJob
import com.karr.master.core.UserRole
import com.karr.master.core.VehicleSummary
import com.karr.master.core.displayName
import com.karr.master.data.MasterRepository
import com.karr.master.feature.customer.CustomerFeatureScreen
import com.karr.master.feature.dealer.DealerFeatureScreen
import com.karr.master.feature.installer.InstallerFeatureScreen
import com.karr.master.protocol.DeviceKeyProvisioning
import kotlin.math.abs

private enum class MainDestination(
    val label: String,
    val iconRes: Int,
    val role: UserRole? = null,
) {
    HOME("Home", R.drawable.ic_nav_home),
    CUSTOMER("Customer", R.drawable.ic_nav_customer),
    DEALER("Dealer", R.drawable.ic_nav_dealer),
    INSTALLER("Installer", R.drawable.ic_nav_installer),
    DEVICE("Device", R.drawable.ic_nav_bluetooth),
}

@Composable
fun KarrMasterApp(
    repository: MasterRepository,
    bleController: BleController,
    deviceKeys: DeviceKeyProvisioning,
    requestBlePermissions: (((Boolean) -> Unit) -> Unit),
) {
    val session = remember(repository) { repository.localSession() }
    val vehicles = remember(repository) { repository.vehicles() }
    val jobs = remember(repository) { repository.jobs() }
    val bleState by bleController.state.collectAsState()
    val deviceAuthenticated = bleState is BleSessionState.Ready
    var destination by remember { mutableStateOf(MainDestination.DEVICE) }
    var selectedFeature by remember { mutableStateOf<FeatureDefinition?>(null) }
    val snackbar = remember { SnackbarHostState() }

    MasterShell(
        session = session,
        deviceAuthenticated = deviceAuthenticated,
        destination = destination,
        selectedFeature = selectedFeature,
        vehicles = vehicles,
        jobs = jobs,
        bleController = bleController,
        deviceKeys = deviceKeys,
        requestBlePermissions = requestBlePermissions,
        snackbar = snackbar,
        onDestination = {
            selectedFeature = null
            destination = it
        },
        onOpenFeature = { selectedFeature = it },
        onBackFromFeature = { selectedFeature = null },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MasterShell(
    session: Session,
    deviceAuthenticated: Boolean,
    destination: MainDestination,
    selectedFeature: FeatureDefinition?,
    vehicles: List<VehicleSummary>,
    jobs: List<TechnicianJob>,
    bleController: BleController,
    deviceKeys: DeviceKeyProvisioning,
    requestBlePermissions: (((Boolean) -> Unit) -> Unit),
    snackbar: SnackbarHostState,
    onDestination: (MainDestination) -> Unit,
    onOpenFeature: (FeatureDefinition) -> Unit,
    onBackFromFeature: () -> Unit,
) {
    val visibleDestinations = MainDestination.entries.filter { tab ->
        tab.role == null || tab.role in session.roles
    }
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val pageSwipeModifier = if (selectedFeature == null) {
        Modifier.pointerInput(destination, visibleDestinations) {
            var horizontalDrag = 0f
            detectHorizontalDragGestures(
                onDragStart = { horizontalDrag = 0f },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    horizontalDrag += dragAmount
                },
                onDragEnd = {
                    if (abs(horizontalDrag) >= swipeThreshold) {
                        val currentIndex = visibleDestinations.indexOf(destination)
                        val targetIndex = if (horizontalDrag < 0f) {
                            currentIndex + 1
                        } else {
                            currentIndex - 1
                        }
                        visibleDestinations.getOrNull(targetIndex)?.let(onDestination)
                    }
                    horizontalDrag = 0f
                },
                onDragCancel = { horizontalDrag = 0f },
            )
        }
    } else {
        Modifier
    }

    BackHandler(enabled = selectedFeature != null) {
        onBackFromFeature()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("KARR MASTER", fontWeight = FontWeight.Black)
                        Text(
                            if (deviceAuthenticated) {
                                "Device authenticated"
                            } else {
                                "Ready to connect"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                visibleDestinations.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedFeature == null && destination == tab,
                        onClick = { onDestination(tab) },
                        icon = {
                            Icon(
                                painter = painterResource(tab.iconRes),
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(vertical = 14.dp)
                .then(pageSwipeModifier),
        ) {
            when {
                selectedFeature != null -> FeatureDetailScreen(
                    session = session,
                    feature = selectedFeature,
                    deviceAuthenticated = deviceAuthenticated,
                    onBack = onBackFromFeature,
                )
                destination == MainDestination.HOME -> DashboardScreen()
                destination == MainDestination.CUSTOMER -> CustomerFeatureScreen(
                    session = session,
                    vehicles = vehicles,
                    deviceAuthenticated = deviceAuthenticated,
                    onOpenFeature = onOpenFeature,
                    modifier = Modifier.fillMaxSize(),
                )
                destination == MainDestination.DEALER -> DealerFeatureScreen(
                    session = session,
                    vehicles = vehicles,
                    deviceAuthenticated = deviceAuthenticated,
                    onOpenFeature = onOpenFeature,
                    modifier = Modifier.fillMaxSize(),
                )
                destination == MainDestination.INSTALLER -> InstallerFeatureScreen(
                    session = session,
                    jobs = jobs,
                    deviceAuthenticated = deviceAuthenticated,
                    onOpenFeature = onOpenFeature,
                    modifier = Modifier.fillMaxSize(),
                )
                destination == MainDestination.DEVICE -> DeviceCenterScreen(
                    controller = bleController,
                    deviceKeys = deviceKeys,
                    requestPermissions = requestBlePermissions,
                    snackbar = snackbar,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun DashboardScreen() {
    val uriHandler = LocalUriHandler.current
    val customerFeatureCount = FeatureCatalog.forRole(UserRole.CUSTOMER).size
    val dealerFeatureCount = FeatureCatalog.forRole(UserRole.DEALER).size
    val installerFeatureCount = FeatureCatalog.forRole(UserRole.INSTALLER).size

    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Vehicle access toolkit",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Vehicle access, diagnostics, and service workflows.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Image(
                painter = painterResource(R.drawable.pirates_plunder_mark),
                contentDescription = "The Pirates' Plunder skull and crossed tusks",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp)
                    .aspectRatio(966f / 876f),
            )
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("What KARR Master does", style = MaterialTheme.typography.titleLarge)
                    FeatureSummary(
                        title = "Direct connection",
                        description = "Discover, authenticate with, and service compatible KARR units over Bluetooth.",
                    )
                    FeatureSummary(
                        title = "Three focused workspaces",
                        description = "$customerFeatureCount customer, $dealerFeatureCount dealer, and $installerFeatureCount installer tools.",
                    )
                    FeatureSummary(
                        title = "On-device records",
                        description = "Vehicle and work records stay on this device.",
                    )
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Developed by NeedNotApply",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "for The Pirates' Plunder",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = {
                            uriHandler.openUri("https://www.discord.gg/thepirates")
                        },
                    ) {
                        Text("Join us online!")
                    }
                    Text(
                        "discord.gg/thepirates",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureSummary(
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeatureDetailScreen(
    session: Session,
    feature: FeatureDefinition,
    deviceAuthenticated: Boolean,
    onBack: () -> Unit,
) {
    val decision = RolePolicy().authorize(session, feature, deviceAuthenticated)
    LazyColumn(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
        }
        item {
            Text(
                feature.category.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(feature.title, style = MaterialTheme.typography.headlineMedium)
            Text(
                feature.summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Recovered compatibility source", style = MaterialTheme.typography.titleMedium)
                    Text("Source: ${feature.sourceApp}")
                    Text("Role: ${feature.requiredRole.displayName()}")
                    Text(
                        "Capabilities: ${feature.requiredCapabilities.joinToString { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    when (decision) {
                        AuthorizationDecision.Allowed -> Text(
                            if (feature.safetySensitive) {
                                "The current Bluetooth session is authenticated for local work."
                            } else {
                                "This surface uses local records or recovered protocol behavior."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is AuthorizationDecision.Denied -> Text(
                            decision.reason,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
