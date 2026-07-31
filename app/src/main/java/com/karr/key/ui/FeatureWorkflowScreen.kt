package com.karr.key.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.karr.key.ble.BleCommandExchange
import com.karr.key.ble.BleCommandExchangeResult
import com.karr.key.ble.BleController
import com.karr.key.ble.BleSessionState
import com.karr.key.core.AuthorizationDecision
import com.karr.key.core.RolePolicy
import com.karr.key.core.Session
import com.karr.key.core.displayName
import com.karr.key.data.LocalOperationState
import com.karr.key.data.LocalOperationStore
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.RecoveredActionPreflight
import com.karr.key.protocol.RecoveredDeviceAction
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredModelCompatibility
import com.karr.key.protocol.RecoveredOperationBinding
import com.karr.key.protocol.RecoveredPreflightDecision
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
fun DeviceOperationScreen(
    session: Session,
    operation: RecoveredDeviceOperation,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
    controller: BleController,
    operationStore: LocalOperationStore,
    snackbar: SnackbarHostState,
) {
    val binding = remember(
        operation.id,
        session.roles,
        session.capabilities,
        unitModel,
    ) {
        operation.preferredBinding(session, unitModel)
    }
    val feature = binding.feature
    val action = binding.action
    val decision = RolePolicy().authorize(session, feature, deviceAuthenticated)
    val exchange = remember(controller) { BleCommandExchange(controller) }
    val scope = rememberCoroutineScope()
    var commandStatus by remember(operation.id) { mutableStateOf<String?>(null) }
    var decodedResponse by remember(operation.id) {
        mutableStateOf<QtDecodedResponse?>(null)
    }
    var lastResponse by remember(operation.id) { mutableStateOf<String?>(null) }
    var sending by remember(operation.id) { mutableStateOf(false) }
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
        decodedResponse = null
        lastResponse = null
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
                        decodedResponse = QtResponseDecoder.decode(
                            packet = result.response,
                            unitModel = unitModel,
                        )
                        lastResponse = result.response.rawResponseText(result.rawFrame)
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
                                append("The unit returned ${executableAction.command.name} data. ")
                                append(
                                    if (decodedResponse != null) {
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
                        decodedResponse = null
                        lastResponse = result.response.rawResponseText(result.rawFrame)
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
                    operation.category.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    operation.label,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    operation.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                val modelCompatibility = action.compatibilityWith(unitModel)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (action.mutating) {
                                "Run command"
                            } else {
                                "Read from unit"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (deviceAuthenticated) {
                            Text(
                                "${unitModel?.displayName ?: "Unknown model"} connected",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "Connect and authenticate a unit before running this operation.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                        if (decision is AuthorizationDecision.Denied) {
                            Text(
                                decision.reason,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (action.experimental) {
                            Text(
                                "EXPERIMENTAL CANDIDATE",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
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
            item {
                TechnicalDetails {
                    Text(
                        "Available roles",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(operation.roles.joinToString { role -> role.displayName() })
                    Text(
                        "Recovered sources",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    operation.bindings
                        .map { operationBinding -> operationBinding.action.source }
                        .distinct()
                        .forEach { source -> Text(source) }
                    operation.bindings
                        .mapNotNull(RecoveredOperationBinding::limitation)
                        .distinct()
                        .takeIf { limitations -> limitations.isNotEmpty() }
                        ?.let { limitations ->
                            Text(
                                "Protocol boundary",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            limitations.forEach { limitation ->
                                Text(
                                    limitation,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    Text(
                        "Recovered command mapping",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    CodeSurface(
                        "${operation.command.name} [${action.packet().toHex()}]",
                    )
                    lastResponse?.let { response ->
                        Text(
                            "Raw protocol response",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        CodeSurface(response)
                    }
                }
            }
        }
    }
}

internal fun RecoveredDeviceOperation.preferredBinding(
    session: Session,
    unitModel: KarrUnitModel?,
): RecoveredOperationBinding = bindings.maxBy { binding ->
    val feature = binding.feature
    var score = 0
    if (feature.requiredRole in session.roles) {
        score += 100
    }
    if (session.capabilities.containsAll(feature.requiredCapabilities)) {
        score += 50
    }
    score += when (binding.action.compatibilityWith(unitModel)) {
        RecoveredModelCompatibility.Supported -> 20
        is RecoveredModelCompatibility.Unknown -> 10
        is RecoveredModelCompatibility.Unsupported -> 0
    }
    if (binding.action.expectedSecurityState != null) {
        score += 1
    }
    score
}

private const val MAX_RESPONSE_PREVIEW_BYTES = 48

private fun QtPacket.rawResponseText(rawFrame: ByteArray): String {
    val commandName = command?.name
        ?: "0x${commandCode.toString(16).uppercase()}"
    val payloadPreview = payload
        .take(MAX_RESPONSE_PREVIEW_BYTES)
        .toByteArray()
        .toHex()
    val framePreview = rawFrame
        .take(MAX_RESPONSE_PREVIEW_BYTES)
        .toByteArray()
        .toHex()
    return buildString {
        append(commandName)
        append(" - ")
        append(payload.size)
        append(" payload bytes")
        if (payloadPreview.isNotEmpty()) {
            append(": ")
            append(payloadPreview)
            if (payload.size > MAX_RESPONSE_PREVIEW_BYTES) {
                append("...")
            }
        }
        append("\nRX frame: ")
        append(framePreview)
        if (rawFrame.size > MAX_RESPONSE_PREVIEW_BYTES) {
            append("...")
        }
    }
}

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
