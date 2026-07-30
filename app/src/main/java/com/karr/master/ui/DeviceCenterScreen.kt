package com.karr.master.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.karr.master.ble.BleController
import com.karr.master.ble.BleSessionState
import com.karr.master.ble.DiscoveredDevice
import com.karr.master.ble.KarrCandidateTier
import com.karr.master.ble.KarrGatt
import com.karr.master.protocol.DeviceKeyProvisioning
import com.karr.master.protocol.DeviceKeyPurpose
import com.karr.master.protocol.PacketParseResult
import com.karr.master.protocol.QtCommand
import com.karr.master.protocol.QtProtocolCodec
import com.karr.master.protocol.ResearchKeyEncoding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun DeviceCenterScreen(
    controller: BleController,
    deviceKeys: DeviceKeyProvisioning,
    section: DeviceSection,
    onSectionSelected: (DeviceSection) -> Unit,
    onProvisioningChanged: () -> Unit,
    requestPermissions: (((Boolean) -> Unit) -> Unit),
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val devices by controller.devices.collectAsState()
    val confirmedDevices = confirmedDevicesForConnection(devices)
    val otherDevices = otherDevicesForConnection(devices)
    val confirmedCount = confirmedDevices.size
    val nearbyCount = confirmedCount + otherDevices.size
    val scope = rememberCoroutineScope()
    var lastNotification by remember { mutableStateOf<String?>(null) }
    var selfTest by remember { mutableStateOf<String?>(null) }
    var showOtherDevices by remember { mutableStateOf(false) }

    // Credential text intentionally uses remember, never rememberSaveable.
    var customerKeyInput by remember { mutableStateOf("") }
    var dealerKeyInput by remember { mutableStateOf("") }
    var customerSaveAttempted by remember { mutableStateOf(false) }
    var dealerSaveAttempted by remember { mutableStateOf(false) }
    var customerKeyProvisioned by remember(deviceKeys) {
        mutableStateOf(deviceKeys.isProvisioned(DeviceKeyPurpose.CUSTOMER))
    }
    var dealerKeyProvisioned by remember(deviceKeys) {
        mutableStateOf(deviceKeys.isProvisioned(DeviceKeyPurpose.DEALER))
    }
    var pendingClear by remember { mutableStateOf<DeviceKeyPurpose?>(null) }

    fun saveKey(
        purpose: DeviceKeyPurpose,
        input: String,
        clearInput: () -> Unit,
        markProvisioned: () -> Unit,
    ) {
        val parsed = ResearchKeyEncoding.parseLegacyHex(input)
        val key = parsed.getOrElse { failure ->
            scope.launch {
                snackbar.showSnackbar(
                    failure.message ?: "The research key is invalid.",
                )
            }
            return
        }
        val saved = try {
            deviceKeys.provision(purpose, key)
        } finally {
            key.fill(0)
        }
        saved.onSuccess {
            clearInput()
            markProvisioned()
            onProvisioningChanged()
            scope.launch {
                snackbar.showSnackbar("Key encrypted locally.")
            }
        }.onFailure { failure ->
            scope.launch {
                snackbar.showSnackbar(
                    failure.message ?: "The key could not be stored.",
                )
            }
        }
    }

    LaunchedEffect(controller) {
        controller.notifications.collectLatest { bytes ->
            lastNotification = when (val parsed = QtProtocolCodec.parse(bytes)) {
                is PacketParseResult.Success -> {
                    val command = parsed.packet.command?.name
                        ?: "COMMAND_${parsed.packet.commandCode}"
                    "${bytes.toHex()} → $command (${parsed.packet.payload.size} data bytes)"
                }
                is PacketParseResult.Failure -> "${bytes.toHex()} → ${parsed.reason}"
            }
        }
    }

    pendingClear?.let { purpose ->
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text("Clear stored key?") },
            text = {
                Text(
                    "This removes the encrypted ${purpose.displayName()} key from this device. " +
                        "The corresponding tools will lock again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deviceKeys.remove(purpose)
                        when (purpose) {
                            DeviceKeyPurpose.CUSTOMER -> {
                                customerKeyInput = ""
                                customerKeyProvisioned = false
                                customerSaveAttempted = false
                            }
                            DeviceKeyPurpose.DEALER -> {
                                dealerKeyInput = ""
                                dealerKeyProvisioned = false
                                dealerSaveAttempted = false
                            }
                            DeviceKeyPurpose.PER_UNIT_ENCRYPTION -> Unit
                        }
                        onProvisioningChanged()
                        pendingClear = null
                        scope.launch { snackbar.showSnackbar("Stored key cleared.") }
                    },
                ) {
                    Text("Clear key")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    val startScan = {
        requestPermissions { granted ->
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
    }
    val runSelfTest = {
        val packet = QtProtocolCodec.generateOriginal(QtCommand.READ_STATE)
        selfTest = when (val parsed = QtProtocolCodec.parse(packet)) {
            is PacketParseResult.Success ->
                "${packet.toHex()} → ${parsed.packet.command?.name}"
            is PacketParseResult.Failure -> parsed.reason
        }
    }
    val sendReadState: () -> Unit = {
        scope.launch {
            controller.send(
                QtProtocolCodec.generateOriginal(QtCommand.READ_STATE),
            ).onSuccess {
                snackbar.showSnackbar("READ_STATE sent.")
            }.onFailure { failure ->
                snackbar.showSnackbar(failure.message ?: "Command send failed.")
            }
        }
        Unit
    }

    @Composable
    fun SelectedSection(sectionModifier: Modifier) {
        when (section) {
            DeviceSection.CONNECTION -> ConnectionSection(
                state = state,
                nearbyCount = nearbyCount,
                confirmedDevices = confirmedDevices,
                otherDevices = otherDevices,
                confirmedCount = confirmedCount,
                showOtherDevices = showOtherDevices,
                onShowOtherDevicesChanged = { showOtherDevices = it },
                onScan = startScan,
                onStopScan = controller::stopScan,
                onDisconnect = controller::disconnect,
                onRetryReconnect = {
                    controller.retryReconnect().onFailure { failure ->
                        scope.launch {
                            snackbar.showSnackbar(
                                failure.message ?: "Automatic reconnect could not be retried.",
                            )
                        }
                    }
                },
                onConnect = { device ->
                    controller.connect(device.address).onFailure { failure ->
                        scope.launch {
                            snackbar.showSnackbar(
                                failure.message ?: "Connection failed.",
                            )
                        }
                    }
                },
                modifier = sectionModifier,
            )
            DeviceSection.ACCESS_KEYS -> AccessKeysSection(
                customerInput = customerKeyInput,
                dealerInput = dealerKeyInput,
                customerProvisioned = customerKeyProvisioned,
                dealerProvisioned = dealerKeyProvisioned,
                customerSaveAttempted = customerSaveAttempted,
                dealerSaveAttempted = dealerSaveAttempted,
                onCustomerInputChanged = {
                    customerKeyInput = it
                    customerSaveAttempted = false
                },
                onDealerInputChanged = {
                    dealerKeyInput = it
                    dealerSaveAttempted = false
                },
                onSaveCustomer = {
                    customerSaveAttempted = true
                    saveKey(
                        purpose = DeviceKeyPurpose.CUSTOMER,
                        input = customerKeyInput,
                        clearInput = {
                            customerKeyInput = ""
                            customerSaveAttempted = false
                        },
                        markProvisioned = { customerKeyProvisioned = true },
                    )
                },
                onSaveDealer = {
                    dealerSaveAttempted = true
                    saveKey(
                        purpose = DeviceKeyPurpose.DEALER,
                        input = dealerKeyInput,
                        clearInput = {
                            dealerKeyInput = ""
                            dealerSaveAttempted = false
                        },
                        markProvisioned = { dealerKeyProvisioned = true },
                    )
                },
                onClearCustomer = { pendingClear = DeviceKeyPurpose.CUSTOMER },
                onClearDealer = { pendingClear = DeviceKeyPurpose.DEALER },
                modifier = sectionModifier,
            )
            DeviceSection.DIAGNOSTICS -> DiagnosticsSection(
                state = state,
                selfTest = selfTest,
                lastNotification = lastNotification,
                onRunSelfTest = runSelfTest,
                onSendReadState = sendReadState,
                modifier = sectionModifier,
            )
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(280.dp)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionHeader(
                        title = "Device",
                        supportingText = "Bluetooth, local access, and protocol inspection.",
                    )
                    DeviceSection.entries.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item.label) },
                            selected = item == section,
                            onClick = { onSectionSelected(item) },
                        )
                    }
                }
                VerticalDivider()
                SelectedSection(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = DeviceSection.entries.indexOf(section),
                ) {
                    DeviceSection.entries.forEach { item ->
                        Tab(
                            selected = item == section,
                            onClick = { onSectionSelected(item) },
                            text = { Text(item.label) },
                        )
                    }
                }
                SelectedSection(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ConnectionSection(
    state: BleSessionState,
    nearbyCount: Int,
    confirmedDevices: List<DiscoveredDevice>,
    otherDevices: List<DiscoveredDevice>,
    confirmedCount: Int,
    showOtherDevices: Boolean,
    onShowOtherDevicesChanged: (Boolean) -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onRetryReconnect: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 20.dp,
            end = 16.dp,
            bottom = 32.dp,
        ),
    ) {
        item {
            SectionHeader(
                title = "Connection",
                supportingText =
                    "Unexpected drops reconnect only to the last authenticated unit.",
            )
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = state.label(
                            confirmedCount,
                            nearbyCount,
                        ),
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = state.label(
                                confirmedCount,
                                nearbyCount,
                            )
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onScan,
                            enabled = state.canScan(),
                        ) {
                            Text("Scan")
                        }
                        OutlinedButton(
                            onClick = onStopScan,
                            enabled = state is BleSessionState.Scanning,
                        ) {
                            Text("Stop")
                        }
                        if (state is BleSessionState.ReconnectFailed) {
                            OutlinedButton(onClick = onRetryReconnect) {
                                Text("Retry reconnect")
                            }
                        }
                        OutlinedButton(
                            onClick = onDisconnect,
                            enabled = state.canDisconnect(),
                        ) {
                            Text(
                                if (
                                    state is BleSessionState.Reconnecting ||
                                    state is BleSessionState.ReconnectFailed
                                ) {
                                    "Cancel reconnect"
                                } else {
                                    "Disconnect"
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionHeader(
                title = scanCountLabel(
                    confirmedCount,
                    nearbyCount,
                ),
                supportingText = "Confirmed formats match recovered KARR identifiers. " +
                    "Strongest recently seen signals appear first.",
            )
        }
        if (confirmedDevices.isEmpty()) {
            item {
                Text(
                    if (state is BleSessionState.Scanning) {
                        "Scanning for confirmed KARR-format signals."
                    } else {
                        "No confirmed KARR-format signals found."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(confirmedDevices, key = { "confirmed:${it.address}" }) { device ->
                DiscoveredDeviceCard(
                    device = device,
                    onConnect = if (device.connectionEligible) {
                        { onConnect(device) }
                    } else {
                        null
                    },
                )
            }
        }
        if (otherDevices.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = {
                        onShowOtherDevicesChanged(!showOtherDevices)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (showOtherDevices) {
                            "Hide other nearby (${otherDevices.size})"
                        } else {
                            "Show other nearby (${otherDevices.size})"
                        },
                    )
                }
            }
        }
        if (showOtherDevices) {
            item {
                SectionHeader(
                    title = "Other nearby advertisements",
                    supportingText = "Visible for diagnosis only. Connect remains disabled " +
                        "without a recovered KARR identifier.",
                )
            }
            items(otherDevices, key = { "other:${it.address}" }) { device ->
                DiscoveredDeviceCard(device = device, onConnect = null)
            }
        }
    }
}

@Composable
private fun AccessKeysSection(
    customerInput: String,
    dealerInput: String,
    customerProvisioned: Boolean,
    dealerProvisioned: Boolean,
    customerSaveAttempted: Boolean,
    dealerSaveAttempted: Boolean,
    onCustomerInputChanged: (String) -> Unit,
    onDealerInputChanged: (String) -> Unit,
    onSaveCustomer: () -> Unit,
    onSaveDealer: () -> Unit,
    onClearCustomer: () -> Unit,
    onClearDealer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val customerValid = customerInput.isBlank() ||
        ResearchKeyEncoding.parseLegacyHex(customerInput).isSuccess
    val dealerValid = dealerInput.isBlank() ||
        ResearchKeyEncoding.parseLegacyHex(dealerInput).isSuccess

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 20.dp,
            end = 16.dp,
            bottom = 32.dp,
        ),
    ) {
        item {
            SectionHeader(
                title = "Access keys",
                supportingText = "Authorized research material is encrypted with Android " +
                    "Keystore and never bundled with the APK.",
            )
        }
        item {
            KeyProvisioningCard(
                title = "Customer",
                supportingText = "Unlocks the Customer tool catalog.",
                fieldLabel = "Customer key (16-byte hex)",
                input = customerInput,
                provisioned = customerProvisioned,
                isError = customerSaveAttempted && !customerValid,
                onInputChanged = onCustomerInputChanged,
                onSave = onSaveCustomer,
                onClear = onClearCustomer,
            )
        }
        item {
            KeyProvisioningCard(
                title = "Dealer / Installer",
                supportingText = "Unlocks the Dealer and Installer tool catalogs.",
                fieldLabel = "Dealer key (16-byte hex)",
                input = dealerInput,
                provisioned = dealerProvisioned,
                isError = dealerSaveAttempted && !dealerValid,
                onInputChanged = onDealerInputChanged,
                onSave = onSaveDealer,
                onClear = onClearDealer,
            )
        }
        item {
            Text(
                "Catalog access does not authorize live commands. Safety-sensitive " +
                    "operations still require this unit to return AUTH_SUCCESS.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun KeyProvisioningCard(
    title: String,
    supportingText: String,
    fieldLabel: String,
    input: String,
    provisioned: Boolean,
    isError: Boolean,
    onInputChanged: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusPill(
                if (provisioned) {
                    FeatureAvailability.Available
                } else {
                    FeatureAvailability.Locked("Not provisioned")
                },
            )
            OutlinedTextField(
                value = input,
                onValueChange = onInputChanged,
                label = { Text(fieldLabel) },
                supportingText = {
                    Text(
                        if (isError) {
                            "Enter exactly 16 bytes as hexadecimal."
                        } else {
                            "Spaces and separators are accepted."
                        },
                    )
                },
                isError = isError,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSave,
                    enabled = input.isNotBlank(),
                ) {
                    Text("Save key")
                }
                OutlinedButton(
                    onClick = onClear,
                    enabled = provisioned,
                ) {
                    Text("Clear stored key")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(
    state: BleSessionState,
    selfTest: String?,
    lastNotification: String?,
    onRunSelfTest: () -> Unit,
    onSendReadState: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 20.dp,
            end = 16.dp,
            bottom = 32.dp,
        ),
    ) {
        item {
            SectionHeader(
                title = "Diagnostics",
                supportingText = "Local protocol inspection and authenticated device tests.",
            )
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Protocol checks",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "The self-test builds and parses READ_STATE locally without " +
                            "contacting a device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = onRunSelfTest) {
                            Text("Run self-test")
                        }
                        Button(
                            onClick = onSendReadState,
                            enabled = state is BleSessionState.Ready,
                        ) {
                            Text("Send READ_STATE")
                        }
                    }
                    if (state is BleSessionState.AuthenticationRequired ||
                        state is BleSessionState.Authenticating
                    ) {
                        Text(
                            "The unit requested authentication. KARR Master reads its " +
                                "mode, uses matching local key material, and waits for " +
                                "AUTH_SUCCESS.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    selfTest?.let {
                        Text(
                            "Self-test result",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        CodeSurface(it)
                    }
                }
            }
        }
        item {
            TechnicalDetails(title = "Bluetooth UUIDs") {
                Text("UART service", style = MaterialTheme.typography.labelLarge)
                CodeSurface(KarrGatt.UART_SERVICE.toString())
                Text("UART characteristic", style = MaterialTheme.typography.labelLarge)
                CodeSurface(KarrGatt.UART_CHARACTERISTIC.toString())
                Text(
                    "Client configuration descriptor",
                    style = MaterialTheme.typography.labelLarge,
                )
                CodeSurface(KarrGatt.CLIENT_CONFIGURATION.toString())
            }
        }
        item {
            SectionHeader(
                title = "Last notification",
                supportingText = "Selectable decoded packet output from the connected unit.",
            )
            CodeSurface(lastNotification ?: "No notifications received.")
        }
    }
}

@Composable
private fun DiscoveredDeviceCard(
    device: DiscoveredDevice,
    onConnect: (() -> Unit)?,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                device.name ?: "Unnamed BLE device",
                style = MaterialTheme.typography.titleMedium,
            )
            CodeSurface("${device.address}  RSSI ${device.rssi}")
            val isConfirmed =
                device.classification.tier == KarrCandidateTier.CONFIRMED_FORMAT
            Text(
                if (isConfirmed) {
                    "Confirmed KARR format: ${device.classification.evidence}"
                } else {
                    device.classification.evidence
                },
                color = if (isConfirmed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (device.classification.isCandidate && !device.connectable) {
                Text(
                    "Broadcast-only advertisement; connection unavailable.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (onConnect != null) {
                OutlinedButton(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

private fun BleSessionState.canDisconnect(): Boolean =
    this is BleSessionState.Ready ||
        this is BleSessionState.Reconnecting ||
        this is BleSessionState.ReconnectFailed ||
        this is BleSessionState.Connecting ||
        this is BleSessionState.DiscoveringUart ||
        this is BleSessionState.UartSubscribed ||
        this is BleSessionState.AuthenticationRequired ||
        this is BleSessionState.Authenticating

private fun BleSessionState.canScan(): Boolean =
    this is BleSessionState.Idle ||
        this is BleSessionState.Error ||
        this is BleSessionState.ReconnectFailed

private fun BleSessionState.label(
    confirmedCount: Int,
    nearbyCount: Int,
): String = when (this) {
    BleSessionState.Idle -> "Idle"
    BleSessionState.Scanning ->
        "Scanning — ${scanCountLabel(confirmedCount, nearbyCount)}"
    is BleSessionState.Reconnecting ->
        "Reconnecting to $serial (attempt $attempt of $maxAttempts): $step"
    is BleSessionState.ReconnectFailed ->
        "Reconnect to $serial stopped after $attempts attempts: $message"
    is BleSessionState.Connecting -> "Connecting to $address"
    is BleSessionState.DiscoveringUart -> "Discovering KARR UART on $address"
    is BleSessionState.UartSubscribed ->
        "Bluetooth connected: ${name ?: address}; waiting for KARR authentication"
    is BleSessionState.AuthenticationRequired ->
        "KARR authentication required: ${name ?: address} " +
            "($challengeBytes-byte challenge). Bluetooth link is healthy."
    is BleSessionState.Authenticating ->
        "Authenticating ${name ?: address}: $step"
    is BleSessionState.Ready -> "Authenticated and ready: ${name ?: address}"
    is BleSessionState.Error -> "Error: $message"
}

internal fun scanCountLabel(
    confirmedCount: Int,
    nearbyCount: Int,
): String = "$confirmedCount confirmed KARR / $nearbyCount nearby"

internal fun confirmedDevicesForConnection(
    devices: List<DiscoveredDevice>,
): List<DiscoveredDevice> = devices
    .asSequence()
    .filter { it.classification.tier == KarrCandidateTier.CONFIRMED_FORMAT }
    .sortedWith(
        compareByDescending<DiscoveredDevice> { it.rssi }
            .thenBy { it.name ?: it.address },
    )
    .toList()

internal fun otherDevicesForConnection(
    devices: List<DiscoveredDevice>,
): List<DiscoveredDevice> = devices.filter {
    it.classification.tier == KarrCandidateTier.OTHER
}

private fun DeviceKeyPurpose.displayName(): String = when (this) {
    DeviceKeyPurpose.CUSTOMER -> "Customer"
    DeviceKeyPurpose.DEALER -> "Dealer / Installer"
    DeviceKeyPurpose.PER_UNIT_ENCRYPTION -> "per-unit encryption"
}

private fun ByteArray.toHex(): String =
    joinToString(separator = " ") { byte -> "%02X".format(byte.toInt() and 0xff) }
