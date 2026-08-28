package com.karr.key.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.karr.key.ble.BleCommandExchange
import com.karr.key.ble.BleCommandExchangeResult
import com.karr.key.ble.BleController
import com.karr.key.ble.BleModeChangeExchange
import com.karr.key.ble.BleModeChangeResult
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.ModeChangeStage
import com.karr.key.core.AuthorizationDecision
import com.karr.key.core.KarrTrace
import com.karr.key.core.RolePolicy
import com.karr.key.core.Session
import com.karr.key.core.displayName
import com.karr.key.data.CommandCompatStore
import com.karr.key.data.LocalDeviceReadStore
import com.karr.key.data.LocalOperationState
import com.karr.key.data.LocalOperationStore
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.RecoveredActionPreflight
import com.karr.key.protocol.RecoveredCompatibilityConstraint
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
import com.karr.key.protocol.dangerScore
import com.karr.key.protocol.evaluate
import com.karr.key.protocol.qtSpecificSettingOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DeviceOperationScreen(
    session: Session,
    operation: RecoveredDeviceOperation,
    deviceAuthenticated: Boolean,
    bleState: BleSessionState,
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode?,
    controller: BleController,
    operationStore: LocalOperationStore,
    deviceReadStore: LocalDeviceReadStore,
    snackbar: SnackbarHostState,
    onOpenOperation: (RecoveredDeviceOperation) -> Unit,
    initialExecutionResult: OperationExecutionResult? = null,
) {
    val binding = remember(
        operation.id,
        session.roles,
        session.capabilities,
        unitModel,
        unitMode,
    ) {
        operation.preferredBinding(session, unitModel, unitMode)
    }
    val feature = binding.feature
    val action = binding.action
    var selectedSetting by remember(operation.id) {
        mutableIntStateOf(action.payload.firstOrNull() ?: 0)
    }
    var settingMenuExpanded by remember(operation.id) { mutableStateOf(false) }
    val selectedAction = if (action.command == QtCommand.READ_SPECIFIC_SETTING) {
        action.copy(payload = listOf(selectedSetting))
    } else {
        action
    }
    val decision = RolePolicy().authorize(session, feature, deviceAuthenticated)
    val modeSwitchOffer = modeSwitchOffer(
        session = session,
        blockedAction = action,
        deviceAuthenticated = deviceAuthenticated,
        unitModel = unitModel,
        unitMode = unitMode,
    )
    // A mode-only block is resolved automatically on send (the shared mode driver switches the unit
    // to a supported mode first), so such an operation stays runnable rather than showing a disabled
    // Send behind a "wrong mode" message.
    val modeAutoResolvable = modeSwitchOffer != null
    val recoveryStatus = remember(
        operation.id,
        session.roles,
        session.capabilities,
        deviceAuthenticated,
        unitModel,
        unitMode,
    ) {
        immobilizerRecoveryStatus(
            session = session,
            operation = operation,
            deviceAuthenticated = deviceAuthenticated,
            unitModel = unitModel,
            unitMode = unitMode,
        )
    }
    val exchange = remember(controller) { BleCommandExchange(controller) }
    val modeChangeExchange = remember(controller) { BleModeChangeExchange(controller) }
    val context = LocalContext.current
    val compatibilityStore = remember(context) { CommandCompatStore(context) }
    val scope = rememberCoroutineScope()
    var commandStatus by remember(operation.id) { mutableStateOf<String?>(null) }
    var decodedResponse by remember(operation.id) {
        mutableStateOf<QtDecodedResponse?>(null)
    }
    var lastResponse by remember(operation.id) { mutableStateOf<String?>(null) }
    var sending by remember(operation.id) { mutableStateOf(false) }

    fun recordSpecificSettingResult(action: RecoveredDeviceAction, outcome: String) {
        if (action.command != QtCommand.READ_SPECIFIC_SETTING || unitModel == null) return
        val tx = ByteArray(action.payload.size) { index -> action.payload[index].toByte() }
        compatibilityStore.record(unitModel.code, action.command.name, tx, outcome)
        KarrTrace.log(KarrTrace.COMPAT) {
            val txHex = if (tx.isEmpty()) "-" else KarrTrace.hex(tx)
            "${unitModel.displayName} / ${unitMode?.displayName ?: "?"} | " +
                "${action.command.name} | $outcome | tx=$txHex"
        }
    }
    LaunchedEffect(operation.id, initialExecutionResult) {
        initialExecutionResult
            ?.takeIf { result -> result.operationId == operation.id }
            ?.let { result ->
                commandStatus = result.status
                decodedResponse = result.decodedResponse
                lastResponse = result.rawResponse
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
        sending = true
        decodedResponse = null
        lastResponse = null
        scope.launch {
            try {
                // If the unit's mode is the only block, drive it to a supported mode first — the
                // same shared driver every send path uses — then re-check before sending.
                var effectiveMode = unitMode
                if (action.compatibilityWith(unitModel, effectiveMode).blockingReason() != null) {
                    val drive = modeChangeExchange.driveToRequiredMode(
                        session = session,
                        action = action,
                        unitModel = unitModel,
                        startMode = effectiveMode,
                        onHop = { _, to ->
                            commandStatus = "Switching to ${to.displayName} mode first..."
                        },
                    )
                    if (drive.changed) effectiveMode = drive.finalMode
                }
                action.compatibilityWith(unitModel, effectiveMode).blockingReason()?.let { reason ->
                    commandStatus = reason
                    snackbar.showSnackbar(reason)
                    return@launch
                }
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
                executableAction.targetMode?.let { targetMode ->
                    when (
                        val result = modeChangeExchange.changeAndConfirm(
                            changePacket = executableAction.packet(),
                            targetMode = targetMode,
                            onChangeGattAccepted = {
                                updateOperation(
                                    operationId = operation?.id,
                                    state = LocalOperationState.DELIVERED_TO_GATT,
                                )
                                commandStatus =
                                    "CHANGE_MODE was accepted by Android GATT; waiting for " +
                                        "the unit acknowledgement."
                            },
                            onReadGattAccepted = {
                                commandStatus =
                                    "CHANGE_MODE was acknowledged; reading the unit mode back."
                            },
                        )
                    ) {
                        is BleModeChangeResult.Confirmed -> {
                            decodedResponse = QtResponseDecoder.decode(
                                packet = result.readResponse,
                                unitModel = unitModel,
                            )
                            lastResponse = result.readResponse.rawResponseText(result.rawFrame)
                            updateOperation(
                                operationId = operation?.id,
                                state = LocalOperationState.SUCCEEDED,
                            )
                            commandStatus =
                                "Unit reports ${targetMode.displayName} mode. The mode change " +
                                    "is confirmed by a fresh READ_MODE response."
                            snackbar.showSnackbar(
                                "Mode confirmed: ${targetMode.displayName}.",
                            )
                        }
                        is BleModeChangeResult.ObservedDifferent -> {
                            decodedResponse = QtResponseDecoder.decode(
                                packet = result.readResponse,
                                unitModel = unitModel,
                            )
                            lastResponse = result.readResponse.rawResponseText(result.rawFrame)
                            val error =
                                "CHANGE_MODE was acknowledged, but a fresh READ_MODE reports " +
                                    "${result.observedMode.displayName} mode rather than " +
                                    "${targetMode.displayName}. The command was not repeated."
                            updateOperation(
                                operationId = operation?.id,
                                state = LocalOperationState.FAILED_PERMANENT,
                                lastError = error,
                            )
                            commandStatus = error
                            snackbar.showSnackbar(error)
                        }
                        is BleModeChangeResult.Rejected -> {
                            decodedResponse = null
                            lastResponse = result.response.rawResponseText(result.rawFrame)
                            val attemptedCommand = when (result.stage) {
                                ModeChangeStage.CHANGE_MODE -> QtCommand.CHANGE_MODE
                                ModeChangeStage.READ_MODE -> QtCommand.READ_MODE
                            }
                            val error = result.response.ackErrorMessage(
                                attemptedCommand = attemptedCommand,
                                prefix = "The unit rejected ${result.stage.displayName}",
                            ) + " The mode-change command was not repeated."
                            updateOperation(
                                operationId = operation?.id,
                                state = LocalOperationState.FAILED_PERMANENT,
                                lastError = error,
                            )
                            commandStatus = error
                            snackbar.showSnackbar(error)
                        }
                        is BleModeChangeResult.TimedOut -> {
                            val error = when (result.stage) {
                                ModeChangeStage.CHANGE_MODE ->
                                    "GATT accepted CHANGE_MODE, but no unit acknowledgement was " +
                                        "observed within 5 seconds. The command was not repeated."
                                ModeChangeStage.READ_MODE ->
                                    "CHANGE_MODE was acknowledged, but the follow-up READ_MODE " +
                                        "did not return within 5 seconds. The requested mode is " +
                                        "not confirmed, and CHANGE_MODE was not repeated."
                            }
                            updateOperation(
                                operationId = operation?.id,
                                state = LocalOperationState.FAILED_PERMANENT,
                                lastError = error,
                            )
                            commandStatus = error
                            snackbar.showSnackbar(error)
                        }
                        is BleModeChangeResult.TransportFailure -> {
                            val error =
                                "${result.stage.displayName} failed: ${result.message} " +
                                    "The mode-change command was not repeated."
                            updateOperation(
                                operationId = operation?.id,
                                state = LocalOperationState.FAILED_PERMANENT,
                                lastError = error,
                            )
                            commandStatus = error
                            snackbar.showSnackbar(error)
                        }
                        is BleModeChangeResult.MalformedRead -> {
                            decodedResponse = null
                            lastResponse = result.response.rawResponseText(result.rawFrame)
                            val error =
                                "CHANGE_MODE was acknowledged, but READ_MODE returned no mode " +
                                    "byte. The requested mode is not confirmed, and the command " +
                                    "was not repeated."
                            updateOperation(
                                operationId = operation?.id,
                                state = LocalOperationState.FAILED_PERMANENT,
                                lastError = error,
                            )
                            commandStatus = error
                            snackbar.showSnackbar(error)
                        }
                    }
                    return@launch
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
                        val responseName = result.response.command?.name
                            ?: "0x${result.response.commandCode.toString(16).uppercase()}"
                        val responsePayload = if (result.response.payload.isEmpty()) {
                            "-"
                        } else {
                            KarrTrace.hex(result.response.payload)
                        }
                        recordSpecificSettingResult(
                            executableAction,
                            "${CommandCompatStore.OUTCOME_OK} rx=$responseName:$responsePayload",
                        )
                        decodedResponse = QtResponseDecoder.decode(
                            packet = result.response,
                            unitModel = unitModel,
                        )
                        lastResponse = result.response.rawResponseText(result.rawFrame)
                        deviceReadStore.recordReadIfApplicable(
                            controller = controller,
                            command = executableAction.command,
                            mutating = executableAction.mutating,
                            decoded = decodedResponse,
                            fallbackUnitModel = unitModel,
                        )
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
                        val rejectCode = result.response.payload.firstOrNull()
                            ?.toInt()
                            ?.and(0xff)
                            ?.let { code -> "0x${code.toString(16).uppercase().padStart(2, '0')}" }
                            ?: "??"
                        recordSpecificSettingResult(
                            executableAction,
                            CommandCompatStore.OUTCOME_REJECT_PREFIX + rejectCode,
                        )
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
                        recordSpecificSettingResult(
                            executableAction,
                            CommandCompatStore.OUTCOME_TIMEOUT,
                        )
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
                        recordSpecificSettingResult(
                            executableAction,
                            CommandCompatStore.OUTCOME_LINK,
                        )
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
                val compatibility = selectedAction.compatibilityWith(unitModel, unitMode)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (action.command == QtCommand.READ_SPECIFIC_SETTING) {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    "Setting to read",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Choose the payload selector sent to the unit.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Box(Modifier.fillMaxWidth()) {
                                    val selectedOption = qtSpecificSettingOptions.first {
                                        it.selector == selectedSetting
                                    }
                                    OutlinedButton(
                                        onClick = { settingMenuExpanded = true },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("specific-setting-selector"),
                                    ) {
                                        Text(
                                            "0x${selectedOption.selector.toString(16).uppercase().padStart(2, '0')} · " +
                                                selectedOption.label,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text("▾")
                                    }
                                    DropdownMenu(
                                        expanded = settingMenuExpanded,
                                        onDismissRequest = { settingMenuExpanded = false },
                                    ) {
                                        qtSpecificSettingOptions.forEach { option ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        "0x${option.selector.toString(16).uppercase().padStart(2, '0')} · " +
                                                            option.label,
                                                    )
                                                },
                                                onClick = {
                                                    selectedSetting = option.selector
                                                    settingMenuExpanded = false
                                                },
                                                modifier = Modifier.testTag(
                                                    "specific-setting:${option.selector}",
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // A mode-only block no longer disables Send — the drive resolves it on tap.
                    val modeReady =
                        compatibility == RecoveredModelCompatibility.Supported || modeAutoResolvable
                    if (action.mutating) {
                        Button(
                            onClick = { execute(selectedAction) },
                            enabled =
                                decision == AuthorizationDecision.Allowed &&
                                    deviceAuthenticated &&
                                    modeReady &&
                                    recoveryStatus?.available != false &&
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
                        Button(
                            onClick = { execute(selectedAction) },
                            enabled =
                                decision == AuthorizationDecision.Allowed &&
                                    deviceAuthenticated &&
                                    modeReady &&
                                    !sending,
                        ) {
                            Text("Run read")
                        }
                    }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "Requirements",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            DangerTag(operation.dangerScore)
                        action.targetMode?.let { targetMode ->
                            Text(
                                "MODE EFFECT",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                targetMode.effectDescription,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (deviceAuthenticated) {
                            Text(
                                listOfNotNull(
                                    unitModel?.displayName,
                                    unitMode?.let { mode -> "${mode.displayName} mode" },
                                ).ifEmpty {
                                    listOf("Compatibility details unavailable")
                                }.joinToString(" • ") + " connected",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                if (bleState.hasActiveDeviceConnection()) {
                                    "AUTHENTICATION REQUIRED"
                                } else {
                                    "DEVICE NOT CONNECTED"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                if (bleState.hasActiveDeviceConnection()) {
                                    "Finish authenticating the connected unit."
                                } else {
                                    "Connect and authenticate a unit."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when (
                            val displayedCompatibility =
                            if (deviceAuthenticated) {
                                compatibility
                            } else {
                                RecoveredModelCompatibility.Supported
                            }
                        ) {
                            RecoveredModelCompatibility.Supported -> Unit
                            is RecoveredModelCompatibility.Unsupported -> {
                                val modeRestriction =
                                    displayedCompatibility.constraint ==
                                        RecoveredCompatibilityConstraint.UNIT_MODE
                                Text(
                                    if (modeRestriction) {
                                        "UNIT MODE CHANGE REQUIRED"
                                    } else {
                                        "HARDWARE NOT SUPPORTED"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (modeRestriction) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                                Text(
                                    displayedCompatibility.reason,
                                    color = if (modeRestriction) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                            is RecoveredModelCompatibility.Unknown -> {
                                Text(
                                    if (
                                        displayedCompatibility.constraint ==
                                        RecoveredCompatibilityConstraint.UNIT_MODE
                                    ) {
                                        "UNIT MODE UNKNOWN"
                                    } else {
                                        "HARDWARE COMPATIBILITY UNKNOWN"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Text(
                                    displayedCompatibility.reason,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (
                            deviceAuthenticated &&
                            compatibility is RecoveredModelCompatibility.Unsupported &&
                            compatibility.constraint ==
                            RecoveredCompatibilityConstraint.UNIT_MODE &&
                            decision == AuthorizationDecision.Allowed
                        ) {
                            modeSwitchOffer?.let { offer ->
                                Text(
                                    if (offer.totalSteps == 1) {
                                        "A recovered mode switch can make this operation available."
                                    } else {
                                        "A recovered ${offer.totalSteps}-step path can reach " +
                                            "${offer.destinationDescription}. Start by switching " +
                                            "to ${offer.nextMode.displayName} mode."
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    onClick = { onOpenOperation(offer.operation) },
                                ) {
                                    Text("Open ${offer.operation.label}")
                                }
                            } ?: Text(
                                "No authorized recovered mode switch is available from the " +
                                    "unit's current mode.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                        recoveryStatus?.let { recovery ->
                            Text(
                                "RESTORE DRIVABLE STATE",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                "This is a separate Unlock and disarm command, not part of the " +
                                    "immobilizer-output packet. Open it to return the vehicle to " +
                                    "a non-immobilized state, then " +
                                    "physically verify the vehicle is drivable before this test, " +
                                    "and keep it available afterward. A protocol acknowledgement " +
                                    "is not physical proof.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            recovery.operation?.let { recoveryOperation ->
                                OutlinedButton(
                                    onClick = { onOpenOperation(recoveryOperation) },
                                ) {
                                    Text("Open recovery: ${recoveryOperation.label}")
                                }
                            }
                            if (!recovery.available) {
                                Text(
                                    "IMMOBILIZATION TEST BLOCKED",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                recovery.reason?.let { reason ->
                                    Text(
                                        reason,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
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
                            decoded.vehicleVin?.let { vin ->
                                VinIdentityContent(vin = vin)
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

private fun BleSessionState.hasActiveDeviceConnection(): Boolean = when (this) {
    is BleSessionState.DiscoveringUart,
    is BleSessionState.UartSubscribed,
    is BleSessionState.AuthenticationRequired,
    is BleSessionState.Authenticating,
    is BleSessionState.Ready -> true
    else -> false
}

internal fun RecoveredDeviceOperation.preferredBinding(
    session: Session,
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode? = null,
): RecoveredOperationBinding = bindings.maxBy { binding ->
    val feature = binding.feature
    var score = 0
    if (feature.requiredRole in session.roles) {
        score += 100
    }
    if (session.capabilities.containsAll(feature.requiredCapabilities)) {
        score += 50
    }
    score += when (binding.action.compatibilityWith(unitModel, unitMode)) {
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

internal fun QtPacket.rawResponseText(rawFrame: ByteArray): String {
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

internal fun QtPacket.ackErrorMessage(
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

internal val ModeChangeStage.displayName: String
    get() = when (this) {
        ModeChangeStage.CHANGE_MODE -> "CHANGE_MODE"
        ModeChangeStage.READ_MODE -> "READ_MODE confirmation"
    }
