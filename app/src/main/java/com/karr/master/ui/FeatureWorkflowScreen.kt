package com.karr.master.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.karr.master.ble.BleController
import com.karr.master.ble.BleSessionState
import com.karr.master.core.AuthorizationDecision
import com.karr.master.core.FeatureDefinition
import com.karr.master.core.RolePolicy
import com.karr.master.core.Session
import com.karr.master.core.displayName
import com.karr.master.data.LocalOperation
import com.karr.master.data.LocalOperationState
import com.karr.master.data.LocalOperationStore
import com.karr.master.protocol.PacketParseResult
import com.karr.master.protocol.RecoveredDeviceAction
import com.karr.master.protocol.RecoveredWorkflowCatalog
import com.karr.master.protocol.QtCommand
import com.karr.master.protocol.QtProtocolCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FeatureWorkflowScreen(
    session: Session,
    feature: FeatureDefinition,
    deviceAuthenticated: Boolean,
    controller: BleController,
    operationStore: LocalOperationStore,
    snackbar: SnackbarHostState,
    onOpenDevice: () -> Unit,
    onBack: () -> Unit,
) {
    val decision = RolePolicy().authorize(session, feature, deviceAuthenticated)
    val actions = remember(feature.id) {
        RecoveredWorkflowCatalog.actionsFor(feature.id)
    }
    val limitation = remember(feature.id) {
        RecoveredWorkflowCatalog.limitationFor(feature.id)
    }
    val scope = rememberCoroutineScope()
    var pendingAction by remember(feature.id) {
        mutableStateOf<RecoveredDeviceAction?>(null)
    }
    var commandStatus by remember(feature.id) { mutableStateOf<String?>(null) }
    var lastResponse by remember(feature.id) { mutableStateOf<String?>(null) }
    var sending by remember(feature.id) { mutableStateOf(false) }
    var recentOperations by remember(feature.id) {
        mutableStateOf<List<LocalOperation>>(emptyList())
    }
    var awaitingOperationId by remember(feature.id) { mutableStateOf<String?>(null) }
    var awaitingCommandCode by remember(feature.id) { mutableStateOf<Int?>(null) }
    var commandGeneration by remember(feature.id) { mutableIntStateOf(0) }
    val opensDeviceCenter = feature.id in DEVICE_CENTER_FEATURES
    val showsLocalHistory = feature.id in LOCAL_HISTORY_FEATURES

    LaunchedEffect(controller, feature.id) {
        controller.notifications.collect { bytes ->
            val parsed = QtProtocolCodec.parse(bytes)
            lastResponse = when (parsed) {
                is PacketParseResult.Failure ->
                    "Unparsed notification: ${bytes.size} bytes"
                is PacketParseResult.Success -> {
                    val command = parsed.packet.command?.name
                        ?: "0x${parsed.packet.commandCode.toString(16).uppercase()}"
                    val payload = parsed.packet.payload
                    val preview = payload
                        .take(MAX_RESPONSE_PREVIEW_BYTES)
                        .joinToString(" ") { byte ->
                            "%02X".format(byte.toInt() and 0xff)
                        }
                    buildString {
                        append(command)
                        append(" - ")
                        append(payload.size)
                        append(" payload bytes")
                        if (preview.isNotEmpty()) {
                            append(": ")
                            append(preview)
                            if (payload.size > MAX_RESPONSE_PREVIEW_BYTES) {
                                append("...")
                            }
                        }
                    }
                }
            }
            val expectedCommandCode = awaitingCommandCode
            if (parsed is PacketParseResult.Success &&
                expectedCommandCode != null &&
                parsed.packet.commandCode in setOf(
                    expectedCommandCode,
                    QtCommand.ACK_OK.code,
                    QtCommand.ACK_ERROR.code,
                )
            ) {
                val failed = parsed.packet.command == QtCommand.ACK_ERROR
                val operationId = awaitingOperationId
                awaitingOperationId = null
                awaitingCommandCode = null
                commandStatus = if (failed) {
                    "The unit rejected the command with ACK_ERROR."
                } else {
                    "The unit returned the expected protocol notification."
                }
                operationId?.let {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            operationStore.updateState(
                                id = it,
                                state = if (failed) {
                                    LocalOperationState.FAILED_PERMANENT
                                } else {
                                    LocalOperationState.SUCCEEDED
                                },
                                attempts = 1,
                                lastError = if (failed) "The unit returned ACK_ERROR." else null,
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(feature.id, commandStatus) {
        if (showsLocalHistory) {
            recentOperations = withContext(Dispatchers.IO) {
                operationStore.recent(50)
            }
        }
    }

    fun execute(action: RecoveredDeviceAction) {
        sending = true
        commandStatus = "Sending ${action.command.name}..."
        scope.launch {
            try {
                val connectedScope = when (val state = controller.state.value) {
                    is BleSessionState.Ready -> state.name ?: state.address
                    else -> "no-ready-device"
                }
                val operation = withContext(Dispatchers.IO) {
                    runCatching {
                        operationStore.enqueue(
                            role = feature.requiredRole,
                            type = "BLE_${action.command.name}",
                            scope = connectedScope,
                            payload = "payload_bytes=${action.payload.size}",
                        ).also { created ->
                            operationStore.updateState(
                                id = created.id,
                                state = LocalOperationState.IN_FLIGHT,
                                attempts = 1,
                            )
                        }
                    }.getOrNull()
                }
                commandGeneration++
                val expectedGeneration = commandGeneration
                val expectedCode = action.command.code
                awaitingOperationId = operation?.id
                awaitingCommandCode = expectedCode
                controller.send(action.packet())
                    .onSuccess {
                        val responseStillPending =
                            commandGeneration == expectedGeneration &&
                                awaitingCommandCode == expectedCode
                        if (responseStillPending) {
                            operation?.let { recorded ->
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        operationStore.updateState(
                                            id = recorded.id,
                                            state = LocalOperationState.DELIVERED_TO_GATT,
                                            attempts = 1,
                                        )
                                    }
                                }
                            }
                            commandStatus =
                                "${action.command.name} was accepted by Android GATT; " +
                                    "waiting for the unit notification."
                        }
                        snackbar.showSnackbar("${action.label} sent.")
                        scope.launch {
                            delay(RESPONSE_TIMEOUT_MS)
                            if (commandGeneration == expectedGeneration &&
                                awaitingCommandCode == expectedCode
                            ) {
                                awaitingOperationId = null
                                awaitingCommandCode = null
                                commandStatus =
                                    "GATT accepted ${action.command.name}, but no matching " +
                                        "unit response was observed within 5 seconds."
                            }
                        }
                    }
                    .onFailure { failure ->
                        val responseStillPending =
                            commandGeneration == expectedGeneration &&
                                awaitingCommandCode == expectedCode
                        if (responseStillPending) {
                            awaitingOperationId = null
                            awaitingCommandCode = null
                            operation?.let { recorded ->
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        operationStore.updateState(
                                            id = recorded.id,
                                            state = LocalOperationState.FAILED_RETRYABLE,
                                            attempts = 1,
                                            lastError = failure.message,
                                        )
                                    }
                                }
                            }
                            commandStatus = failure.message ?: "The BLE command failed."
                            snackbar.showSnackbar(commandStatus!!)
                        }
                    }
            } finally {
                sending = false
            }
        }
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("Confirm ${action.label}") },
            text = {
                Text(
                    "${action.description}\n\n" +
                        "Use only on the authorized stationary research vehicle. " +
                        "The app reports GATT delivery separately from the unit response.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingAction = null
                        execute(action)
                    },
                ) {
                    Text("Send command")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text("Cancel")
                }
            },
        )
    }

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
                    Text("Research access", style = MaterialTheme.typography.titleMedium)
                    Text("Source: ${feature.sourceApp}")
                    Text("Role: ${feature.requiredRole.displayName()}")
                    when (decision) {
                        AuthorizationDecision.Allowed -> Text(
                            if (feature.safetySensitive) {
                                "The corresponding local key is provisioned and the current " +
                                    "Bluetooth session is authenticated."
                            } else {
                                "The corresponding local key is provisioned for this workflow."
                            },
                            color = MaterialTheme.colorScheme.primary,
                        )
                        is AuthorizationDecision.Denied -> Text(
                            decision.reason,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        if (opensDeviceCenter) {
            item {
                Button(onClick = onOpenDevice) {
                    Text("Open Device center")
                }
            }
        }
        if (actions.isNotEmpty()) {
            item {
                Text(
                    "Recovered device operations",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!deviceAuthenticated) {
                    Text(
                        "Connect and complete unit authentication before sending commands.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(actions, key = RecoveredDeviceAction::id) { action ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(action.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            action.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${action.command.name} (${action.source})",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (action.mutating) {
                                Button(
                                    onClick = { pendingAction = action },
                                    enabled =
                                        decision == AuthorizationDecision.Allowed &&
                                            deviceAuthenticated &&
                                            !sending &&
                                            awaitingCommandCode == null,
                                ) {
                                    Text("Review and send")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { execute(action) },
                                    enabled =
                                        decision == AuthorizationDecision.Allowed &&
                                            deviceAuthenticated &&
                                            !sending &&
                                            awaitingCommandCode == null,
                                ) {
                                    Text("Run read")
                                }
                            }
                        }
                    }
                }
            }
        } else if (!opensDeviceCenter) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Local workflow", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This workflow uses local records or operator input and does not " +
                                "send a direct unit packet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        limitation?.let { text ->
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Protocol boundary", style = MaterialTheme.typography.titleMedium)
                        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        commandStatus?.let { status ->
            item {
                Text("Command status", style = MaterialTheme.typography.titleMedium)
                Text(status)
            }
        }
        lastResponse?.let { response ->
            item {
                Text("Latest unit notification", style = MaterialTheme.typography.titleMedium)
                Text(
                    response,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (showsLocalHistory) {
            item {
                Text("Encrypted operation history", style = MaterialTheme.typography.titleMedium)
                if (recentOperations.isEmpty()) {
                    Text(
                        "No device-command attempts have been recorded.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(recentOperations, key = LocalOperation::id) { operation ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(operation.type, style = MaterialTheme.typography.titleSmall)
                        Text("${operation.role.name} - ${operation.state.name}")
                        Text(
                            "${operation.scope} - ${operation.createdUtc}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private val DEVICE_CENTER_FEATURES = setOf(
    "customer.device",
    "dealer.bluetooth",
    "installer.bluetooth",
)

private val LOCAL_HISTORY_FEATURES = setOf(
    "customer.local_history",
    "installer.offline",
)

private const val MAX_RESPONSE_PREVIEW_BYTES = 48
private const val RESPONSE_TIMEOUT_MS = 5_000L
