package com.karr.key.ui

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.karr.key.ble.BleCommandExchange
import com.karr.key.ble.BleCommandExchangeResult
import com.karr.key.ble.BleController
import com.karr.key.ble.BleSessionState
import com.karr.key.core.AuthorizationDecision
import com.karr.key.core.FeatureDefinition
import com.karr.key.core.RolePolicy
import com.karr.key.core.Session
import com.karr.key.core.displayName
import com.karr.key.data.LocalOperationState
import com.karr.key.data.LocalOperationStore
import com.karr.key.protocol.PacketParseResult
import com.karr.key.protocol.RecoveredActionPreflight
import com.karr.key.protocol.RecoveredDeviceAction
import com.karr.key.protocol.RecoveredPreflightDecision
import com.karr.key.protocol.RecoveredWorkflowCatalog
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtProtocolCodec
import com.karr.key.protocol.evaluate
import kotlinx.coroutines.Dispatchers
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
    val exchange = remember(controller) { BleCommandExchange(controller) }
    val scope = rememberCoroutineScope()
    var pendingAction by remember(feature.id) {
        mutableStateOf<RecoveredDeviceAction?>(null)
    }
    var commandStatus by remember(feature.id) { mutableStateOf<String?>(null) }
    var lastResponse by remember(feature.id) { mutableStateOf<String?>(null) }
    var sending by remember(feature.id) { mutableStateOf(false) }
    val opensDeviceCenter = feature.id in DEVICE_CENTER_FEATURES

    LaunchedEffect(controller, feature.id) {
        controller.notifications.collect { bytes ->
            val parsed = QtProtocolCodec.parse(bytes)
            val framePreview = bytes
                .take(MAX_RESPONSE_PREVIEW_BYTES)
                .toByteArray()
                .toHex()
            lastResponse = when (parsed) {
                is PacketParseResult.Failure ->
                    buildString {
                        append("Unparsed notification: ${bytes.size} bytes")
                        append("\nRX frame: ")
                        append(framePreview)
                        if (bytes.size > MAX_RESPONSE_PREVIEW_BYTES) {
                            append("...")
                        }
                    }
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
                        append("\nRX frame: ")
                        append(framePreview)
                        if (bytes.size > MAX_RESPONSE_PREVIEW_BYTES) {
                            append("...")
                        }
                    }
                }
            }
        }
    }

    suspend fun updateOperation(
        operationId: String?,
        state: LocalOperationState,
        lastError: String? = null,
    ) {
        operationId ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                operationStore.updateState(
                    id = operationId,
                    state = state,
                    attempts = 1,
                    lastError = lastError,
                )
            }
        }
    }

    suspend fun runPreflight(action: RecoveredDeviceAction): String? {
        val preflight = action.preflight ?: return null
        commandStatus = "Checking ignition state before ${action.command.name}..."
        return when (
            val result = exchange.sendAndAwait(
                packet = QtProtocolCodec.generateOriginal(QtCommand.READ_STATE),
                expectedCommand = QtCommand.READ_STATE,
                acceptGenericAck = false,
                onGattAccepted = {
                    commandStatus =
                        "READ_STATE was accepted by Android GATT; waiting for unit state."
                },
            )
        ) {
            is BleCommandExchangeResult.Acknowledged -> {
                when (val decision = preflight.evaluate(result.response)) {
                    RecoveredPreflightDecision.Allowed -> {
                        commandStatus = "Ignition-off preflight passed."
                        null
                    }
                    is RecoveredPreflightDecision.Denied -> decision.reason
                }
            }
            is BleCommandExchangeResult.Rejected ->
                "Command blocked because the unit rejected the ignition preflight."
            BleCommandExchangeResult.TimedOut ->
                "Command blocked because ignition state could not be confirmed."
            is BleCommandExchangeResult.TransportFailure ->
                "Command blocked because ignition preflight failed: ${result.message}"
        }
    }

    fun execute(action: RecoveredDeviceAction) {
        sending = true
        scope.launch {
            try {
                val preflightFailure = runPreflight(action)
                if (preflightFailure != null) {
                    commandStatus = preflightFailure
                    snackbar.showSnackbar(preflightFailure)
                    return@launch
                }

                commandStatus = "Sending ${action.command.name}..."
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
                when (
                    val result = exchange.sendAndAwait(
                        packet = action.packet(),
                        expectedCommand = action.command,
                        onGattAccepted = {
                            updateOperation(
                                operationId = operation?.id,
                                state = LocalOperationState.DELIVERED_TO_GATT,
                            )
                            commandStatus =
                                "${action.command.name} was accepted by Android GATT; " +
                                    "waiting for the unit notification."
                        },
                    )
                ) {
                    is BleCommandExchangeResult.Acknowledged -> {
                        updateOperation(
                            operationId = operation?.id,
                            state = LocalOperationState.SUCCEEDED,
                        )
                        commandStatus =
                            "The unit returned a protocol acknowledgement for " +
                                "${action.command.name}. Physical output is not confirmed."
                        snackbar.showSnackbar("${action.label} acknowledged.")
                    }
                    is BleCommandExchangeResult.Rejected -> {
                        val error = "The unit rejected the command with ACK_ERROR."
                        updateOperation(
                            operationId = operation?.id,
                            state = LocalOperationState.FAILED_PERMANENT,
                            lastError = error,
                        )
                        commandStatus = error
                        snackbar.showSnackbar(error)
                    }
                    BleCommandExchangeResult.TimedOut -> {
                        val error =
                            "GATT accepted ${action.command.name}, but no matching unit " +
                                "response was observed within 5 seconds." +
                                if (action.singleShot) {
                                    " The single-shot command was not retried."
                                } else {
                                    ""
                                }
                        updateOperation(
                            operationId = operation?.id,
                            state = if (action.singleShot) {
                                LocalOperationState.FAILED_PERMANENT
                            } else {
                                LocalOperationState.FAILED_RETRYABLE
                            },
                            lastError = error,
                        )
                        commandStatus = error
                        snackbar.showSnackbar(error)
                    }
                    is BleCommandExchangeResult.TransportFailure -> {
                        val error = result.message +
                            if (action.singleShot) {
                                " The single-shot command was not retried."
                            } else {
                                ""
                            }
                        updateOperation(
                            operationId = operation?.id,
                            state = if (action.singleShot) {
                                LocalOperationState.FAILED_PERMANENT
                            } else {
                                LocalOperationState.FAILED_RETRYABLE
                            },
                            lastError = error,
                        )
                        commandStatus = error
                        snackbar.showSnackbar(error)
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
                val preflightText =
                    if (action.preflight == RecoveredActionPreflight.IGNITION_OFF) {
                        "The app will read unit state and refuse to send unless " +
                            "ignition is confirmed off. This single-shot test is not " +
                            "automatically retried.\n\n"
                    } else {
                        ""
                    }
                Text(
                    "${action.description}\n\n" +
                        preflightText +
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
                    Text(if (action.experimental) "Run once" else "Send command")
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
                            if (action.experimental) {
                                Text(
                                    "EXPERIMENTAL CANDIDATE",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Text(
                                    "TX frame: ${action.packet().toHex()}",
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                                                !sending,
                                    ) {
                                        Text(
                                            if (action.experimental) {
                                                "Review test"
                                            } else {
                                                "Review and send"
                                            },
                                        )
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { execute(action) },
                                        enabled =
                                            decision == AuthorizationDecision.Allowed &&
                                                deviceAuthenticated &&
                                                !sending,
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
                                "${action.label}: ${action.command.name} " +
                                    "[${action.packet().toHex()}] (${action.source})",
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

private fun ByteArray.toHex(): String =
    joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xff) }
