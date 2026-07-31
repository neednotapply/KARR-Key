package com.karr.key.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.PacketParseResult
import com.karr.key.protocol.RecoveredActionPreflight
import com.karr.key.protocol.RecoveredDeviceAction
import com.karr.key.protocol.RecoveredModelCompatibility
import com.karr.key.protocol.RecoveredPreflightDecision
import com.karr.key.protocol.RecoveredWorkflowCatalog
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtDecodedResponse
import com.karr.key.protocol.QtErrorCode
import com.karr.key.protocol.QtPacket
import com.karr.key.protocol.QtProtocolCodec
import com.karr.key.protocol.QtResponseDecoder
import com.karr.key.protocol.compatibilityWith
import com.karr.key.protocol.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FeatureWorkflowScreen(
    session: Session,
    feature: FeatureDefinition,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
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
    var commandStatus by remember(feature.id) { mutableStateOf<String?>(null) }
    var decodedResponse by remember(feature.id) {
        mutableStateOf<QtDecodedResponse?>(null)
    }
    var lastResponse by remember(feature.id) { mutableStateOf<String?>(null) }
    var sending by remember(feature.id) { mutableStateOf(false) }
    val opensDeviceCenter = feature.id in DEVICE_CENTER_FEATURES

    LaunchedEffect(controller, feature.id) {
        controller.notifications.collect { bytes ->
            val parsed = QtProtocolCodec.parse(bytes)
            decodedResponse = null
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
                    decodedResponse = QtResponseDecoder.decode(parsed.packet)
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

    suspend fun runPreflight(
        action: RecoveredDeviceAction,
    ): RecoveredPreflightDecision {
        val preflight = action.preflight ?: return RecoveredPreflightDecision.Allowed()
        commandStatus = "Checking unit compatibility before ${action.command.name}..."
        val responses = mutableListOf<QtPacket>()
        preflight.requestCommands.forEach { requestCommand ->
            when (
                val result = exchange.sendAndAwait(
                    packet = QtProtocolCodec.generateOriginal(
                        requestCommand,
                        byteArrayOf(0),
                    ),
                    expectedCommand = requestCommand,
                    acceptGenericAck = false,
                    onGattAccepted = {
                        commandStatus =
                            "$requestCommand was accepted by Android GATT; " +
                                "waiting for the unit response."
                    },
                )
            ) {
                is BleCommandExchangeResult.Acknowledged -> responses += result.response
                is BleCommandExchangeResult.Rejected ->
                    return RecoveredPreflightDecision.Denied(
                        result.response.ackErrorMessage(
                            requestCommand,
                            "Preflight rejected",
                        ),
                    )
                BleCommandExchangeResult.TimedOut ->
                    return RecoveredPreflightDecision.Denied(
                        "Command blocked because $requestCommand compatibility data " +
                            "could not be confirmed.",
                    )
                is BleCommandExchangeResult.TransportFailure ->
                    return RecoveredPreflightDecision.Denied(
                        "Command blocked because its preflight failed: ${result.message}",
                    )
            }
        }
        return preflight.evaluate(
            responses = responses,
            attemptedCommand = action.command,
        )
    }

    fun execute(action: RecoveredDeviceAction) {
        action.compatibilityWith(unitModel).blockingReason()?.let { reason ->
            commandStatus = reason
            scope.launch {
                snackbar.showSnackbar(reason)
            }
            return
        }
        sending = true
        scope.launch {
            try {
                val preflightDecision = runPreflight(action)
                val executableAction = when (preflightDecision) {
                    is RecoveredPreflightDecision.Allowed -> action.copy(
                        command = preflightDecision.commandOverride ?: action.command,
                    )
                    is RecoveredPreflightDecision.Denied -> {
                        commandStatus = preflightDecision.reason
                        snackbar.showSnackbar(preflightDecision.reason)
                        return@launch
                    }
                }
                val routingExplanation = preflightDecision.explanation

                commandStatus = buildString {
                    routingExplanation?.let {
                        append(it)
                        append("\n")
                    }
                    append("Sending ${executableAction.command.name}...")
                }
                val connectedScope = when (val state = controller.state.value) {
                    is BleSessionState.Ready -> state.name ?: state.address
                    else -> "no-ready-device"
                }
                val operation = withContext(Dispatchers.IO) {
                    runCatching {
                        operationStore.enqueue(
                            role = feature.requiredRole,
                            type = "BLE_${executableAction.command.name}",
                            scope = connectedScope,
                            payload = "payload_bytes=${executableAction.payload.size}",
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
                        packet = executableAction.packet(),
                        expectedCommand = executableAction.command,
                        onGattAccepted = {
                            updateOperation(
                                operationId = operation?.id,
                                state = LocalOperationState.DELIVERED_TO_GATT,
                            )
                            commandStatus = buildString {
                                routingExplanation?.let {
                                    append(it)
                                    append("\n")
                                }
                                append(
                                    "${executableAction.command.name} was accepted by " +
                                        "Android GATT; waiting for the unit notification.",
                                )
                            }
                        },
                    )
                ) {
                    is BleCommandExchangeResult.Acknowledged -> {
                        updateOperation(
                            operationId = operation?.id,
                            state = LocalOperationState.SUCCEEDED,
                        )
                        commandStatus = buildString {
                            routingExplanation?.let {
                                append(it)
                                append("\n")
                            }
                            if (executableAction.mutating) {
                                append(
                                    "The unit returned a protocol acknowledgement for " +
                                        "${executableAction.command.name}. Physical output is " +
                                        "not confirmed.",
                                )
                            } else {
                                val responseDecoded =
                                    QtResponseDecoder.decode(result.response) != null
                                append("The unit returned ${executableAction.command.name} data. ")
                                append(
                                    if (responseDecoded) {
                                        "The decoded response is shown below."
                                    } else {
                                        "The raw response is available below."
                                    },
                                )
                            }
                        }
                        snackbar.showSnackbar("${action.label} acknowledged.")
                    }
                    is BleCommandExchangeResult.Rejected -> {
                        val error = result.response.ackErrorMessage(executableAction.command)
                        updateOperation(
                            operationId = operation?.id,
                            state = LocalOperationState.FAILED_PERMANENT,
                            lastError = error,
                        )
                        commandStatus = error
                        snackbar.showSnackbar(error)
                    }
                    BleCommandExchangeResult.TimedOut -> {
                        val baseError =
                            "GATT accepted ${executableAction.command.name}, but no matching unit " +
                                "response was observed within 5 seconds." +
                                if (executableAction.singleShot) {
                                    " The single-shot command was not retried."
                                } else {
                                    ""
                                }
                        val expectedSecurityState = executableAction.expectedSecurityState
                        val reconciliation = expectedSecurityState?.let { expected ->
                            exchange.reconcileSecurityState(
                                expected = expected,
                                onGattAccepted = {
                                    commandStatus =
                                        "${executableAction.command.name} did not return a " +
                                            "matching response. Checking the unit's current " +
                                            "security state before treating it as failed."
                                },
                            )
                        }
                        when (reconciliation) {
                            is SecurityStateReconciliation.Confirmed -> {
                                updateOperation(
                                    operationId = operation?.id,
                                    state = LocalOperationState.SUCCEEDED,
                                )
                                commandStatus = buildString {
                                    routingExplanation?.let {
                                        append(it)
                                        append("\n")
                                    }
                                    append(
                                        "${executableAction.command.name} did not return a " +
                                            "separate response, but a fresh unit state check " +
                                            "reports Security: " +
                                            "${reconciliation.state.displayName}. This matches " +
                                            "the requested state, so the command was not " +
                                            "repeated. Physical door position is not confirmed.",
                                    )
                                }
                                snackbar.showSnackbar(
                                    "Unit reports ${reconciliation.state.displayName.lowercase()}; " +
                                        "no retry needed.",
                                )
                            }
                            is SecurityStateReconciliation.Different -> {
                                val error =
                                    "$baseError A fresh unit state check reports Security: " +
                                        "${reconciliation.state.displayName}, so the requested " +
                                        "${expectedSecurityState.displayName} state was not confirmed."
                                updateOperation(
                                    operationId = operation?.id,
                                    state = if (executableAction.singleShot) {
                                        LocalOperationState.FAILED_PERMANENT
                                    } else {
                                        LocalOperationState.FAILED_RETRYABLE
                                    },
                                    lastError = error,
                                )
                                commandStatus = error
                                snackbar.showSnackbar(error)
                            }
                            SecurityStateReconciliation.Unavailable -> {
                                val error =
                                    "$baseError A follow-up unit state check could not confirm " +
                                        "Security: ${expectedSecurityState.displayName}."
                                updateOperation(
                                    operationId = operation?.id,
                                    state = if (executableAction.singleShot) {
                                        LocalOperationState.FAILED_PERMANENT
                                    } else {
                                        LocalOperationState.FAILED_RETRYABLE
                                    },
                                    lastError = error,
                                )
                                commandStatus = error
                                snackbar.showSnackbar(error)
                            }
                            null -> {
                                updateOperation(
                                    operationId = operation?.id,
                                    state = if (executableAction.singleShot) {
                                        LocalOperationState.FAILED_PERMANENT
                                    } else {
                                        LocalOperationState.FAILED_RETRYABLE
                                    },
                                    lastError = baseError,
                                )
                                commandStatus = baseError
                                snackbar.showSnackbar(baseError)
                            }
                        }
                    }
                    is BleCommandExchangeResult.TransportFailure -> {
                        val error = result.message +
                            if (executableAction.singleShot) {
                                " The single-shot command was not retried."
                            } else {
                                ""
                            }
                        updateOperation(
                            operationId = operation?.id,
                            state = if (executableAction.singleShot) {
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
                                unitModel = unitModel,
                            ),
                        )
                        Text(
                            when (decision) {
                                AuthorizationDecision.Allowed -> {
                                    when (
                                        val compatibility =
                                            featureModelCompatibility(feature, unitModel)
                                    ) {
                                        is FeatureModelCompatibility.Limited ->
                                            compatibility.description
                                        is FeatureModelCompatibility.Unsupported ->
                                            compatibility.description
                                        is FeatureModelCompatibility.Unknown ->
                                            compatibility.description
                                        FeatureModelCompatibility.Supported ->
                                            if (feature.safetySensitive) {
                                                "Local role material and the current " +
                                                    "authenticated device session authorize " +
                                                    "this workflow."
                                            } else {
                                                "Local role material authorizes this workflow."
                                            }
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
            if (deviceAuthenticated) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Connected model",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                unitModel?.displayName ?: "Model not identified",
                                style = MaterialTheme.typography.titleLarge,
                                color = if (unitModel?.isRecognized == true) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
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
                            "${unitModel?.displayName ?: "Unknown model"} connected. " +
                                "Unsupported commands are disabled."
                        } else {
                            "Connect and complete unit authentication before sending commands."
                        },
                    )
                }
                items(actions, key = RecoveredDeviceAction::id) { action ->
                    val modelCompatibility = action.compatibilityWith(unitModel)
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
                            when (modelCompatibility) {
                                RecoveredModelCompatibility.Supported -> Unit
                                is RecoveredModelCompatibility.Unsupported -> {
                                    Text(
                                        "NOT SUPPORTED ON " +
                                            (unitModel?.displayName?.uppercase()
                                                ?: "CONNECTED MODEL"),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        modelCompatibility.reason,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                is RecoveredModelCompatibility.Unknown -> {
                                    Text(
                                        "MODEL SUPPORT UNCONFIRMED",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                    Text(
                                        modelCompatibility.reason,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
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
                                        onClick = { execute(action) },
                                        enabled =
                                            decision == AuthorizationDecision.Allowed &&
                                                deviceAuthenticated &&
                                                modelCompatibility ==
                                                RecoveredModelCompatibility.Supported &&
                                                !sending,
                                    ) {
                                        Text(
                                            if (action.experimental) {
                                                "Run once"
                                            } else {
                                                "Send command"
                                            },
                                        )
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { execute(action) },
                                        enabled =
                                            decision == AuthorizationDecision.Allowed &&
                                                deviceAuthenticated &&
                                                modelCompatibility ==
                                                RecoveredModelCompatibility.Supported &&
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
            decodedResponse?.let { decoded ->
                item {
                    SectionHeader(title = "Latest unit response")
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                decoded.heading,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            decoded.lines.forEach { line ->
                                Text(line)
                            }
                        }
                    }
                }
            }
            lastResponse?.let { response ->
                item {
                    TechnicalDetails(title = "Raw protocol response") {
                        CodeSurface(response)
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

private const val MAX_RESPONSE_PREVIEW_BYTES = 48

private fun ByteArray.toHex(): String =
    joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xff) }

private fun QtPacket.ackErrorMessage(
    attemptedCommand: QtCommand,
    prefix: String = "The unit rejected $attemptedCommand",
): String {
    val rawCode = payload.singleOrNull()?.toInt()?.and(0xff)
    val error = QtErrorCode.fromAckError(this)
    return when {
        error != null ->
            "$prefix: ${error.displayName} (code ${error.code.toString(16).padStart(2, '0').uppercase()}). " +
                error.explanation
        rawCode != null ->
            "$prefix with ACK_ERROR code ${rawCode.toString(16).padStart(2, '0').uppercase()}."
        else ->
            "$prefix with an empty or malformed ACK_ERROR payload."
    }
}

private fun RecoveredModelCompatibility.blockingReason(): String? = when (this) {
    RecoveredModelCompatibility.Supported -> null
    is RecoveredModelCompatibility.Unsupported -> reason
    is RecoveredModelCompatibility.Unknown -> reason
}
