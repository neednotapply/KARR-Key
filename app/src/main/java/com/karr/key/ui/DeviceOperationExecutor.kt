package com.karr.key.ui

import androidx.compose.material3.SnackbarHostState
import com.karr.key.ble.BleCommandExchange
import com.karr.key.ble.BleCommandExchangeResult
import com.karr.key.core.KarrTrace
import com.karr.key.ble.BleController
import com.karr.key.ble.BleModeChangeExchange
import com.karr.key.ble.BleModeChangeResult
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.ModeChangeStage
import com.karr.key.core.AuthorizationDecision
import com.karr.key.core.RolePolicy
import com.karr.key.core.Session
import com.karr.key.core.displayName
import com.karr.key.data.LocalDeviceReadStore
import com.karr.key.data.LocalOperationState
import com.karr.key.data.LocalOperationStore
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtDecodedResponse
import com.karr.key.protocol.QtPacket
import com.karr.key.protocol.QtProtocolCodec
import com.karr.key.protocol.QtResponseDecoder
import com.karr.key.protocol.RecoveredDeviceAction
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredModelCompatibility
import com.karr.key.protocol.RecoveredPreflightDecision
import com.karr.key.protocol.compatibilityWith
import com.karr.key.protocol.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class OperationExecutionCallbacks(
    val onStatus: (String) -> Unit = {},
    val onDecodedResponse: (QtDecodedResponse?) -> Unit = {},
    val onRawResponse: (String?) -> Unit = {},
    val onSendingChanged: (Boolean) -> Unit = {},
    val onSucceeded: () -> Unit = {},
)

data class OperationExecutionResult(
    val operationId: String,
    val status: String?,
    val decodedResponse: QtDecodedResponse?,
    val rawResponse: String?,
)

/**
 * Persists a successful READ's decoded result against the currently-connected unit so it can later
 * be surfaced on the Map. No-op for mutating commands, undecodable responses, or when the unit is
 * no longer connected. Shared by both read-execution paths (the operations-list quick run and the
 * operation detail screen).
 */
internal suspend fun LocalDeviceReadStore.recordReadIfApplicable(
    controller: BleController,
    command: QtCommand,
    mutating: Boolean,
    decoded: QtDecodedResponse?,
    fallbackUnitModel: KarrUnitModel?,
) {
    if (mutating || decoded == null) return
    val ready = controller.state.value as? BleSessionState.Ready ?: return
    withContext(Dispatchers.IO) {
        record(
            deviceAddress = ready.address,
            command = command.name,
            heading = decoded.heading,
            lines = decoded.lines,
            unitModel = (decoded.unitModel ?: fallbackUnitModel)?.displayName,
        )
    }
}

internal data class OperationExecutionAccess(
    val action: RecoveredDeviceAction,
    val requiredRole: com.karr.key.core.UserRole,
    val deviceAuthenticated: Boolean,
    val decision: AuthorizationDecision,
    val compatibility: RecoveredModelCompatibility,
    val recoveryStatus: ImmobilizerRecoveryStatus?,
    val blockingReason: String?,
) {
    val canExecute: Boolean
        get() = blockingReason == null
}

internal fun RecoveredDeviceOperation.executionAccess(
    session: Session,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode?,
): OperationExecutionAccess {
    val binding = preferredBinding(session, unitModel, unitMode)
    val decision = RolePolicy().authorize(session, binding.feature, deviceAuthenticated)
    val compatibility = binding.action.compatibilityWith(unitModel, unitMode)
    val recoveryStatus = immobilizerRecoveryStatus(
        session = session,
        operation = this,
        deviceAuthenticated = deviceAuthenticated,
        unitModel = unitModel,
        unitMode = unitMode,
    )
    val blockingReason = when {
        decision is AuthorizationDecision.Denied -> decision.reason
        !deviceAuthenticated -> "Connect and authenticate a unit."
        compatibility is RecoveredModelCompatibility.Unsupported -> compatibility.reason
        compatibility is RecoveredModelCompatibility.Unknown -> compatibility.reason
        recoveryStatus?.available == false ->
            recoveryStatus.reason ?: "The recovery command is not available."
        else -> null
    }
    return OperationExecutionAccess(
        action = binding.action,
        requiredRole = binding.feature.requiredRole,
        deviceAuthenticated = deviceAuthenticated,
        decision = decision,
        compatibility = compatibility,
        recoveryStatus = recoveryStatus,
        blockingReason = blockingReason,
    )
}

internal class DeviceOperationExecutor(
    private val controller: BleController,
    private val operationStore: LocalOperationStore,
    private val deviceReadStore: LocalDeviceReadStore,
    private val snackbar: SnackbarHostState,
) {
    private val exchange = BleCommandExchange(controller)
    private val modeChangeExchange = BleModeChangeExchange(controller)

    suspend fun execute(
        operation: RecoveredDeviceOperation,
        session: Session,
        deviceAuthenticated: Boolean,
        unitModel: KarrUnitModel?,
        unitMode: KarrDeviceMode?,
        callbacks: OperationExecutionCallbacks = OperationExecutionCallbacks(),
    ) {
        var access = operation.executionAccess(
            session = session,
            deviceAuthenticated = deviceAuthenticated,
            unitModel = unitModel,
            unitMode = unitMode,
        )
        KarrTrace.log(KarrTrace.POLICY) {
            "execute ${access.action.command.name} " +
                "model=${unitModel?.displayName ?: "?"} mode=${unitMode?.name ?: "?"} " +
                "authenticated=$deviceAuthenticated"
        }
        callbacks.onSendingChanged(true)
        try {
            // If the unit's mode is the only thing blocking this command, drive it to a supported
            // mode and re-evaluate — the same behavior the auto-connect sweep uses. A role/hardware
            // block is not a mode block, so no hop is made and the gate below still stops the send.
            if (access.blockingReason != null && deviceAuthenticated) {
                val drive = modeChangeExchange.driveToRequiredMode(
                    session = session,
                    action = access.action,
                    unitModel = unitModel,
                    startMode = unitMode,
                    onHop = { _, to ->
                        callbacks.onStatus("Switching to ${to.displayName} mode first...")
                    },
                )
                if (drive.changed) {
                    KarrTrace.log(KarrTrace.POLICY) {
                        "auto mode -> ${drive.finalMode?.name ?: "?"} for ${access.action.command.name}"
                    }
                    access = operation.executionAccess(
                        session = session,
                        deviceAuthenticated = deviceAuthenticated,
                        unitModel = unitModel,
                        unitMode = drive.finalMode,
                    )
                }
            }
            access.blockingReason?.let { reason ->
                KarrTrace.log(KarrTrace.POLICY) { "blocked ${access.action.command.name}: $reason" }
                callbacks.onStatus(reason)
                snackbar.showSnackbar(reason)
                return
            }
            val action = access.action
            callbacks.onDecodedResponse(null)
            callbacks.onRawResponse(null)
            val preflightDecision = runPreflight(action, callbacks.onStatus)
            val executableAction = when (preflightDecision) {
                is RecoveredPreflightDecision.Allowed -> action.copy(
                    command = preflightDecision.commandOverride ?: action.command,
                )
                is RecoveredPreflightDecision.Denied -> {
                    callbacks.onStatus(preflightDecision.reason)
                    snackbar.showSnackbar(preflightDecision.reason)
                    return
                }
            }
            val routingExplanation = preflightDecision.explanation
            callbacks.onStatus(
                buildString {
                    routingExplanation?.let {
                        append(it)
                        append("\n")
                    }
                    append("Sending ${executableAction.command.name}...")
                },
            )
            val connectedScope = when (val state = controller.state.value) {
                is BleSessionState.Ready -> state.name ?: state.address
                else -> "no-ready-device"
            }
            val storedOperation = withContext(Dispatchers.IO) {
                runCatching {
                    operationStore.enqueue(
                        role = access.requiredRole,
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
                executeModeChange(
                    action = executableAction,
                    targetMode = targetMode,
                    unitModel = unitModel,
                    operationId = storedOperation?.id,
                    callbacks = callbacks,
                )
                return
            }
            executeStandardCommand(
                originalAction = action,
                executableAction = executableAction,
                unitModel = unitModel,
                operationId = storedOperation?.id,
                routingExplanation = routingExplanation,
                callbacks = callbacks,
            )
        } finally {
            callbacks.onSendingChanged(false)
        }
    }

    private suspend fun runPreflight(
        action: RecoveredDeviceAction,
        onStatus: (String) -> Unit,
    ): RecoveredPreflightDecision {
        val preflight = action.preflight ?: return RecoveredPreflightDecision.Allowed()
        onStatus("Checking unit compatibility before ${action.command.name}...")
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
                        onStatus(
                            "$requestCommand was accepted by Android GATT; " +
                                "waiting for the unit response.",
                        )
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

    private suspend fun executeModeChange(
        action: RecoveredDeviceAction,
        targetMode: KarrDeviceMode,
        unitModel: KarrUnitModel?,
        operationId: String?,
        callbacks: OperationExecutionCallbacks,
    ) {
        when (
            val result = modeChangeExchange.changeAndConfirm(
                changePacket = action.packet(),
                targetMode = targetMode,
                onChangeGattAccepted = {
                    updateOperation(operationId, LocalOperationState.DELIVERED_TO_GATT)
                    callbacks.onStatus(
                        "CHANGE_MODE was accepted by Android GATT; waiting for " +
                            "the unit acknowledgement.",
                    )
                },
                onReadGattAccepted = {
                    callbacks.onStatus(
                        "CHANGE_MODE was acknowledged; reading the unit mode back.",
                    )
                },
            )
        ) {
            is BleModeChangeResult.Confirmed -> {
                callbacks.onDecodedResponse(
                    QtResponseDecoder.decode(result.readResponse, unitModel),
                )
                callbacks.onRawResponse(
                    result.readResponse.rawResponseText(result.rawFrame),
                )
                updateOperation(operationId, LocalOperationState.SUCCEEDED)
                callbacks.onStatus(
                    "Unit reports ${targetMode.displayName} mode. The mode change " +
                        "is confirmed by a fresh READ_MODE response.",
                )
                snackbar.showSnackbar("Mode confirmed: ${targetMode.displayName}.")
                callbacks.onSucceeded()
            }
            is BleModeChangeResult.ObservedDifferent -> {
                callbacks.onDecodedResponse(
                    QtResponseDecoder.decode(result.readResponse, unitModel),
                )
                callbacks.onRawResponse(
                    result.readResponse.rawResponseText(result.rawFrame),
                )
                val error =
                    "CHANGE_MODE was acknowledged, but a fresh READ_MODE reports " +
                        "${result.observedMode.displayName} mode rather than " +
                        "${targetMode.displayName}. The command was not repeated."
                updateOperation(
                    operationId,
                    LocalOperationState.FAILED_PERMANENT,
                    error,
                )
                callbacks.onStatus(error)
                snackbar.showSnackbar(error)
            }
            is BleModeChangeResult.Rejected -> {
                callbacks.onDecodedResponse(null)
                callbacks.onRawResponse(
                    result.response.rawResponseText(result.rawFrame),
                )
                val attemptedCommand = when (result.stage) {
                    ModeChangeStage.CHANGE_MODE -> QtCommand.CHANGE_MODE
                    ModeChangeStage.READ_MODE -> QtCommand.READ_MODE
                }
                val error = result.response.ackErrorMessage(
                    attemptedCommand = attemptedCommand,
                    prefix = "The unit rejected ${result.stage.displayName}",
                ) + " The mode-change command was not repeated."
                updateOperation(
                    operationId,
                    LocalOperationState.FAILED_PERMANENT,
                    error,
                )
                callbacks.onStatus(error)
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
                    operationId,
                    LocalOperationState.FAILED_PERMANENT,
                    error,
                )
                callbacks.onStatus(error)
                snackbar.showSnackbar(error)
            }
            is BleModeChangeResult.TransportFailure -> {
                val error =
                    "${result.stage.displayName} failed: ${result.message} " +
                        "The mode-change command was not repeated."
                updateOperation(
                    operationId,
                    LocalOperationState.FAILED_PERMANENT,
                    error,
                )
                callbacks.onStatus(error)
                snackbar.showSnackbar(error)
            }
            is BleModeChangeResult.MalformedRead -> {
                callbacks.onDecodedResponse(null)
                callbacks.onRawResponse(
                    result.response.rawResponseText(result.rawFrame),
                )
                val error =
                    "CHANGE_MODE was acknowledged, but READ_MODE returned no mode " +
                        "byte. The requested mode is not confirmed, and the command " +
                        "was not repeated."
                updateOperation(
                    operationId,
                    LocalOperationState.FAILED_PERMANENT,
                    error,
                )
                callbacks.onStatus(error)
                snackbar.showSnackbar(error)
            }
        }
    }

    private suspend fun executeStandardCommand(
        originalAction: RecoveredDeviceAction,
        executableAction: RecoveredDeviceAction,
        unitModel: KarrUnitModel?,
        operationId: String?,
        routingExplanation: String?,
        callbacks: OperationExecutionCallbacks,
    ) {
        when (
            val result = exchange.sendAndAwait(
                packet = executableAction.packet(),
                expectedCommand = executableAction.command,
                onGattAccepted = {
                    updateOperation(operationId, LocalOperationState.DELIVERED_TO_GATT)
                    callbacks.onStatus(
                        buildString {
                            routingExplanation?.let {
                                append(it)
                                append("\n")
                            }
                            append(
                                "${executableAction.command.name} was accepted by " +
                                    "Android GATT; waiting for the unit notification.",
                            )
                        },
                    )
                },
            )
        ) {
            is BleCommandExchangeResult.Acknowledged -> {
                val decoded = QtResponseDecoder.decode(result.response, unitModel)
                KarrTrace.log(KarrTrace.PROTO) {
                    if (decoded != null) {
                        "decoded ${executableAction.command.name}: ${decoded.heading} | " +
                            decoded.lines.joinToString(" ; ")
                    } else {
                        "decoded ${executableAction.command.name}: no decoder, raw only"
                    }
                }
                callbacks.onDecodedResponse(decoded)
                deviceReadStore.recordReadIfApplicable(
                    controller = controller,
                    command = executableAction.command,
                    mutating = executableAction.mutating,
                    decoded = decoded,
                    fallbackUnitModel = unitModel,
                )
                callbacks.onRawResponse(
                    result.response.rawResponseText(result.rawFrame),
                )
                updateOperation(operationId, LocalOperationState.SUCCEEDED)
                callbacks.onStatus(
                    buildString {
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
                                if (decoded != null) {
                                    "The decoded response is shown below."
                                } else {
                                    "The raw response is available below."
                                },
                            )
                        }
                    },
                )
                snackbar.showSnackbar("${originalAction.label} acknowledged.")
                callbacks.onSucceeded()
            }
            is BleCommandExchangeResult.Rejected -> {
                callbacks.onDecodedResponse(null)
                callbacks.onRawResponse(
                    result.response.rawResponseText(result.rawFrame),
                )
                val error = result.response.ackErrorMessage(executableAction.command)
                updateOperation(
                    operationId,
                    LocalOperationState.FAILED_PERMANENT,
                    error,
                )
                callbacks.onStatus(error)
                snackbar.showSnackbar(error)
            }
            BleCommandExchangeResult.TimedOut -> {
                reconcileTimedOutCommand(
                    action = executableAction,
                    operationId = operationId,
                    routingExplanation = routingExplanation,
                    callbacks = callbacks,
                )
            }
            is BleCommandExchangeResult.TransportFailure -> {
                val error = result.message +
                    if (executableAction.singleShot) {
                        " The single-shot command was not retried."
                    } else {
                        ""
                    }
                updateOperation(
                    operationId,
                    if (executableAction.singleShot) {
                        LocalOperationState.FAILED_PERMANENT
                    } else {
                        LocalOperationState.FAILED_RETRYABLE
                    },
                    error,
                )
                callbacks.onStatus(error)
                snackbar.showSnackbar(error)
            }
        }
    }

    private suspend fun reconcileTimedOutCommand(
        action: RecoveredDeviceAction,
        operationId: String?,
        routingExplanation: String?,
        callbacks: OperationExecutionCallbacks,
    ) {
        val baseError =
            "GATT accepted ${action.command.name}, but no matching unit response was " +
                "observed within 5 seconds." +
                if (action.singleShot) {
                    " The single-shot command was not retried."
                } else {
                    ""
                }
        val expectedSecurityState = action.expectedSecurityState
        val reconciliation = expectedSecurityState?.let { expected ->
            exchange.reconcileSecurityState(
                expected = expected,
                onGattAccepted = {
                    callbacks.onStatus(
                        "${action.command.name} did not return a matching response. " +
                            "Checking the unit's current security state.",
                    )
                },
            )
        }
        when (reconciliation) {
            is SecurityStateReconciliation.Confirmed -> {
                updateOperation(operationId, LocalOperationState.SUCCEEDED)
                callbacks.onStatus(
                    buildString {
                        routingExplanation?.let {
                            append(it)
                            append("\n")
                        }
                        append(
                            "${action.command.name} did not return a separate response, but a " +
                                "fresh unit state check reports Security: " +
                                "${reconciliation.state.displayName}. This matches the requested " +
                                "state, so the command was not repeated. Physical door position " +
                                "is not confirmed.",
                        )
                    },
                )
                snackbar.showSnackbar(
                    "Unit reports ${reconciliation.state.displayName.lowercase()}; no retry needed.",
                )
                callbacks.onSucceeded()
            }
            is SecurityStateReconciliation.Different -> {
                val error =
                    "$baseError A fresh unit state check reports Security: " +
                        "${reconciliation.state.displayName}, so the requested " +
                        "${expectedSecurityState.displayName} state was not confirmed."
                updateOperation(
                    operationId,
                    failureState(action),
                    error,
                )
                callbacks.onStatus(error)
                snackbar.showSnackbar(error)
            }
            SecurityStateReconciliation.Unavailable -> {
                val error =
                    "$baseError A follow-up unit state check could not confirm " +
                        "Security: ${expectedSecurityState.displayName}."
                updateOperation(
                    operationId,
                    failureState(action),
                    error,
                )
                callbacks.onStatus(error)
                snackbar.showSnackbar(error)
            }
            null -> {
                updateOperation(
                    operationId,
                    failureState(action),
                    baseError,
                )
                callbacks.onStatus(baseError)
                snackbar.showSnackbar(baseError)
            }
        }
    }

    private suspend fun updateOperation(
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

    private fun failureState(action: RecoveredDeviceAction): LocalOperationState =
        if (action.singleShot) {
            LocalOperationState.FAILED_PERMANENT
        } else {
            LocalOperationState.FAILED_RETRYABLE
        }
}
