package com.karr.key.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.karr.key.ble.BleController
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.KarrGatt
import com.karr.key.data.LocalDeviceProfileStore
import com.karr.key.data.LocalDeviceReadStore
import com.karr.key.data.LocalMapHistoryStore
import com.karr.key.data.MapHistoryImportSummary
import com.karr.key.protocol.DeviceKeyProvisioning
import com.karr.key.protocol.DeviceKeyPurpose
import com.karr.key.protocol.PacketParseResult
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtProtocolCodec
import com.karr.key.protocol.ResearchKeyEncoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(
    controller: BleController,
    deviceKeys: DeviceKeyProvisioning,
    mapHistoryStore: LocalMapHistoryStore,
    deviceReadStore: LocalDeviceReadStore,
    deviceProfileStore: LocalDeviceProfileStore,
    section: SettingsSection,
    onSectionSelected: (SettingsSection) -> Unit,
    onProvisioningChanged: () -> Unit,
    requestMapHistoryExport: (((Result<Int>) -> Unit) -> Unit),
    requestMapHistoryImport: (((Result<MapHistoryImportSummary>) -> Unit) -> Unit),
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()

    var historyCount by remember { mutableStateOf<Int?>(null) }

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

    var lastNotification by remember { mutableStateOf<String?>(null) }
    var selfTest by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(mapHistoryStore) {
        historyCount = withContext(Dispatchers.IO) { mapHistoryStore.count() }
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
                        "The corresponding operations will lock again.",
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
            SettingsSection.ACCESS_KEYS -> AccessKeysSection(
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
            SettingsSection.MAP -> MapSettingsSection(
                historyCount = historyCount,
                onClear = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            mapHistoryStore.clear()
                            deviceReadStore.clear()
                            deviceProfileStore.clear()
                        }
                        historyCount = 0
                        snackbar.showSnackbar("Map history cleared.")
                    }
                },
                onExport = {
                    requestMapHistoryExport { result ->
                        scope.launch {
                            result.onSuccess { count ->
                                snackbar.showSnackbar(
                                    if (count == 1) {
                                        "Exported 1 map observation."
                                    } else {
                                        "Exported $count map observations."
                                    },
                                )
                            }.onFailure { failure ->
                                snackbar.showSnackbar(failure.message ?: "Export failed.")
                            }
                        }
                    }
                },
                onImport = {
                    requestMapHistoryImport { result ->
                        scope.launch {
                            result.onSuccess { summary ->
                                historyCount = withContext(Dispatchers.IO) {
                                    mapHistoryStore.count()
                                }
                                snackbar.showSnackbar(summary.toSnackbarMessage())
                            }.onFailure { failure ->
                                snackbar.showSnackbar(failure.message ?: "Import failed.")
                            }
                        }
                    }
                },
                modifier = sectionModifier,
            )
            SettingsSection.DIAGNOSTICS -> DiagnosticsSection(
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
                    SettingsSection.entries.forEach { item ->
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
                    selectedTabIndex = SettingsSection.entries.indexOf(section),
                ) {
                    SettingsSection.entries.forEach { item ->
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
private fun MapSettingsSection(
    historyCount: Int?,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 20.dp,
            end = 16.dp,
            bottom = 32.dp,
        ),
    ) {
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Map history",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = historyCount.mapHistorySummary(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onClear,
                        enabled = (historyCount ?: 0) > 0,
                    ) {
                        Text("Clear map history")
                    }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Import / export",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Export uses a WiGLE-compatible CSV format. Importing a WiGLE " +
                            "file only keeps devices that match KARR's discovery logic.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onExport,
                            enabled = (historyCount ?: 0) > 0,
                        ) {
                            Text("Export")
                        }
                        OutlinedButton(onClick = onImport) {
                            Text("Import")
                        }
                    }
                }
            }
        }
    }
}

private fun Int?.mapHistorySummary(): String = when (this) {
    null -> "Checking stored map history."
    0 -> "No map history stored."
    1 -> "1 stored map observation."
    else -> "$this stored map observations."
}

private fun MapHistoryImportSummary.toSnackbarMessage(): String {
    if (total == 0) return "No KARR-matching devices found in that file."
    val parts = buildList {
        if (inserted > 0) add(if (inserted == 1) "1 new" else "$inserted new")
        if (updated > 0) add(if (updated == 1) "1 updated" else "$updated updated")
    }
    return "Imported: ${parts.joinToString(", ")}."
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
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 20.dp,
            end = 16.dp,
            bottom = 32.dp,
        ),
    ) {
        item {
            KeyProvisioningCard(
                title = "Customer",
                supportingText = "Unlocks Customer device operations.",
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
                supportingText = "Unlocks Dealer and Installer device operations.",
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
            if (!provisioned) {
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
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!provisioned) {
                    Button(
                        onClick = onSave,
                        enabled = input.isNotBlank(),
                    ) {
                        Text("Save key")
                    }
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
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 20.dp,
            end = 16.dp,
            bottom = 32.dp,
        ),
    ) {
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
                            "The unit requested authentication. KARR Key reads its " +
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
            val ready = state as? BleSessionState.Ready
            TechnicalDetails(title = "Bluetooth link") {
                Text("Negotiated PHY", style = MaterialTheme.typography.labelLarge)
                CodeSurface(
                    ready?.linkDiagnostics?.let { diagnostics ->
                        "TX ${diagnostics.transmitPhy.displayName}  •  " +
                            "RX ${diagnostics.receivePhy.displayName}"
                    } ?: "Not reported",
                )
                Text(
                    "This reports the observed Bluetooth link. KARR Key does not request " +
                        "LE Coded PHY or increase radio transmit power.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
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

private fun DeviceKeyPurpose.displayName(): String = when (this) {
    DeviceKeyPurpose.CUSTOMER -> "Customer"
    DeviceKeyPurpose.DEALER -> "Dealer / Installer"
    DeviceKeyPurpose.PER_UNIT_ENCRYPTION -> "per-unit encryption"
}

private fun ByteArray.toHex(): String =
    joinToString(separator = " ") { byte -> "%02X".format(byte.toInt() and 0xff) }
