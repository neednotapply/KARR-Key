package com.karr.master.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.karr.master.ble.BleController
import com.karr.master.ble.BleSessionState
import com.karr.master.core.AuthorizationDecision
import com.karr.master.core.FeatureDefinition
import com.karr.master.core.RolePolicy
import com.karr.master.core.Session
import com.karr.master.core.displayName
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
    var awaitingOperationId by remember(feature.id) { mutableStateOf<String?>(null) }
    var awaitingCommandCode by remember(feature.id) { mutableStateOf<Int?>(null) }
    var commandGeneration by remember(feature.id) { mutableIntStateOf(0) }
    val opensDeviceCenter = feature.id in DEVICE_CENTER_FEATURES

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

    ResponsiveContent(
        modifier = Modifier.fillMaxSize(),
        contentMaxWidth = 920.dp,
    ) { contentModifier ->
        LazyColumn(
            modifier = contentModifier,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 20.dp,
                bottom = 32.dp,
            ),
        ) {
            item {
                Text(
                    feature.category.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    feature.title,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    feature.summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Research access",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        StatusPill(
                            featureAvailability(
                                session = session,
                                feature = feature,
                                deviceAuthenticated = deviceAuthenticated,
                            ),
                        )
                        Text(
                            when (decision) {
                                AuthorizationDecision.Allowed -> {
                                    if (feature.safetySensitive) {
                                        "Local role material and the current authenticated " +
                                            "device session authorize this workflow."
                                    } else {
                                        "Local role material authorizes this workflow."
                                    }
                                }
                                is AuthorizationDecision.Denied -> decision.reason
                            },
                            color = if (decision == AuthorizationDecision.Allowed) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
            if (opensDeviceCenter) {
                item {
                    Button(onClick = onOpenDevice) {
                        Text("Open device connection")
                    }
                }
            }
            if (actions.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Device operations",
                        supportingText = if (deviceAuthenticated) {
                            "Commands use the authenticated Bluetooth session."
                        } else {
                            "Connect and complete unit authentication before sending commands."
                        },
                    )
                }
                items(actions, key = RecoveredDeviceAction::id) { action ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                action.label,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                action.description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
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
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Local workflow",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "This workflow uses local records or operator input and " +
                                    "does not send a direct unit packet.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                TechnicalDetails {
                    Text("Role", style = MaterialTheme.typography.labelLarge)
                    Text(feature.requiredRole.displayName())
                    Text(
                        "Recovered source",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(feature.sourceApp)
                    limitation?.let { text ->
                        Text(
                            "Protocol boundary",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (actions.isNotEmpty()) {
                        Text(
                            "Recovered command mapping",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        actions.forEach { action ->
                            CodeSurface(
                                "${action.label}: ${action.command.name} (${action.source})",
                            )
                        }
                    }
                }
            }
            commandStatus?.let { status ->
                item {
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = status
                            },
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Command status",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(status)
                        }
                    }
                }
            }
            lastResponse?.let { response ->
                item {
                    SectionHeader(title = "Latest unit notification")
                    CodeSurface(response)
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

private const val MAX_RESPONSE_PREVIEW_BYTES = 48
private const val RESPONSE_TIMEOUT_MS = 5_000L
