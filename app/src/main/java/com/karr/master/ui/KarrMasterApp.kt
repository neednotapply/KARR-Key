package com.karr.master.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.karr.master.ble.BleController
import com.karr.master.core.FeatureCatalog
import com.karr.master.core.FeatureDefinition
import com.karr.master.core.Session
import com.karr.master.core.TechnicianJob
import com.karr.master.core.UserRole
import com.karr.master.core.VehicleSummary
import com.karr.master.core.displayName
import com.karr.master.data.MasterRepository
import com.karr.master.feature.customer.CustomerFeatureScreen
import com.karr.master.feature.dealer.DealerFeatureScreen
import com.karr.master.feature.installer.InstallerFeatureScreen
import kotlinx.coroutines.launch

private enum class MainDestination(
    val label: String,
    val shortLabel: String,
    val role: UserRole? = null,
) {
    HOME("Home", "H"),
    CUSTOMER("Customer", "C", UserRole.CUSTOMER),
    DEALER("Dealer", "D", UserRole.DEALER),
    INSTALLER("Installer", "I", UserRole.INSTALLER),
    DEVICE("Device", "BT"),
}

@Composable
fun KarrMasterApp(
    repository: MasterRepository,
    bleController: BleController,
    requestBlePermissions: (((Boolean) -> Unit) -> Unit),
) {
    var session by remember { mutableStateOf<Session?>(null) }
    var vehicles by remember { mutableStateOf<List<VehicleSummary>>(emptyList()) }
    var jobs by remember { mutableStateOf<List<TechnicianJob>>(emptyList()) }
    var destination by remember { mutableStateOf(MainDestination.HOME) }
    var selectedFeature by remember { mutableStateOf<FeatureDefinition?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session) {
        val active = session ?: return@LaunchedEffect
        vehicles = repository.vehicles(active).getOrElse { emptyList() }
        jobs = repository.jobs(active).getOrElse { emptyList() }
    }

    if (session == null) {
        LoginScreen(
            onProductionLogin = { username, password ->
                scope.launch {
                    repository.login(username, password)
                        .onSuccess { session = it }
                        .onFailure { snackbar.showSnackbar(it.message ?: "Login failed.") }
                }
            },
            onDemoLogin = { roles ->
                scope.launch {
                    session = repository.demoSession(roles)
                    destination = MainDestination.HOME
                }
            },
            snackbar = snackbar,
        )
        return
    }

    val activeSession = requireNotNull(session)
    MasterShell(
        session = activeSession,
        destination = destination,
        selectedFeature = selectedFeature,
        vehicles = vehicles,
        jobs = jobs,
        bleController = bleController,
        requestBlePermissions = requestBlePermissions,
        snackbar = snackbar,
        onDestination = {
            selectedFeature = null
            destination = it
        },
        onOpenFeature = { selectedFeature = it },
        onBackFromFeature = { selectedFeature = null },
        onLogout = {
            scope.launch {
                bleController.disconnect()
                repository.logout()
                session = null
                vehicles = emptyList()
                jobs = emptyList()
                selectedFeature = null
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MasterShell(
    session: Session,
    destination: MainDestination,
    selectedFeature: FeatureDefinition?,
    vehicles: List<VehicleSummary>,
    jobs: List<TechnicianJob>,
    bleController: BleController,
    requestBlePermissions: (((Boolean) -> Unit) -> Unit),
    snackbar: SnackbarHostState,
    onDestination: (MainDestination) -> Unit,
    onOpenFeature: (FeatureDefinition) -> Unit,
    onBackFromFeature: () -> Unit,
    onLogout: () -> Unit,
) {
    val visibleDestinations = MainDestination.entries.filter { tab ->
        tab.role == null || tab.role in session.roles
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("KARR MASTER", fontWeight = FontWeight.Black)
                        Text(
                            if (session.demo) "Local demo • no live commands" else session.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (session.demo) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onLogout) {
                        Text("Log out")
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
                        icon = { Text(tab.shortLabel) },
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
                .padding(vertical = 14.dp),
        ) {
            when {
                selectedFeature != null -> FeatureDetailScreen(
                    feature = selectedFeature,
                    onBack = onBackFromFeature,
                )
                destination == MainDestination.HOME -> DashboardScreen(
                    session = session,
                    vehicles = vehicles,
                    jobs = jobs,
                    onDestination = onDestination,
                )
                destination == MainDestination.CUSTOMER -> CustomerFeatureScreen(
                    session = session,
                    vehicles = vehicles,
                    onOpenFeature = onOpenFeature,
                    modifier = Modifier.fillMaxSize(),
                )
                destination == MainDestination.DEALER -> DealerFeatureScreen(
                    session = session,
                    vehicles = vehicles,
                    onOpenFeature = onOpenFeature,
                    modifier = Modifier.fillMaxSize(),
                )
                destination == MainDestination.INSTALLER -> InstallerFeatureScreen(
                    session = session,
                    jobs = jobs,
                    onOpenFeature = onOpenFeature,
                    modifier = Modifier.fillMaxSize(),
                )
                destination == MainDestination.DEVICE -> DeviceCenterScreen(
                    session = session,
                    controller = bleController,
                    requestPermissions = requestBlePermissions,
                    snackbar = snackbar,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LoginScreen(
    onProductionLogin: (String, CharArray) -> Unit,
    onDemoLogin: (Set<UserRole>) -> Unit,
    snackbar: SnackbarHostState,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var roles by remember { mutableStateOf(UserRole.entries.toSet()) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Text(
                    "KARR",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "MASTER",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Light,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "One secure client for Customer, Dealer, and Installer workflows.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Production sign-in", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                onProductionLogin(username, password.toCharArray())
                                password = ""
                            },
                            enabled = username.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sign in")
                        }
                        Text(
                            "Requires an authorized backend adapter; no APK credentials are reused.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Local demo roles", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UserRole.entries.forEach { role ->
                        FilterChip(
                            selected = role in roles,
                            onClick = {
                                roles = if (role in roles) roles - role else roles + role
                            },
                            label = { Text(role.displayName()) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { onDemoLogin(roles) },
                    enabled = roles.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open safe demo")
                }
                Text(
                    "Demo mode exercises navigation, data, BLE discovery, and codecs. " +
                        "Safety-sensitive commands remain locked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    session: Session,
    vehicles: List<VehicleSummary>,
    jobs: List<TechnicianJob>,
    onDestination: (MainDestination) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Good to see you, ${session.displayName}",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "${vehicles.size} vehicles • ${jobs.size} technician jobs",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (session.demo) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "SAFE DEMO",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            "Live server authorization is absent. Device mutations, sales, " +
                                "fitments, CAN, firmware, and key operations are locked.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        items(session.roles.toList(), key = { it.name }) { role ->
            val features = when (role) {
                UserRole.CUSTOMER -> FeatureCatalog.customer
                UserRole.DEALER -> FeatureCatalog.dealer
                UserRole.INSTALLER -> FeatureCatalog.installer
            }
            val destination = when (role) {
                UserRole.CUSTOMER -> MainDestination.CUSTOMER
                UserRole.DEALER -> MainDestination.DEALER
                UserRole.INSTALLER -> MainDestination.INSTALLER
            }
            ElevatedCard(
                onClick = { onDestination(destination) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(role.displayName(), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${features.size} consolidated feature groups",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        features.joinToString(" • ") { it.title },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            ElevatedCard(
                onClick = { onDestination(MainDestination.DEVICE) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Device center", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Explicit BLE scan/connect and shared UART protocol diagnostics.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureDetailScreen(
    feature: FeatureDefinition,
    onBack: () -> Unit,
) {
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
                    Text("Migration adapter", style = MaterialTheme.typography.titleMedium)
                    Text("Source: ${feature.sourceApp}")
                    Text("Role: ${feature.requiredRole.displayName()}")
                    Text(
                        "Capabilities: ${feature.requiredCapabilities.joinToString { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (feature.safetySensitive) {
                            "This workflow requires production server authorization, " +
                                "scope, audit, and explicit confirmation."
                        } else {
                            "The feature surface is wired; production data requires the typed API adapter."
                        },
                        color = if (feature.safetySensitive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
