package com.karr.key.auto

import com.karr.key.ble.BleCommandExchange
import com.karr.key.ble.BleCommandExchangeResult
import com.karr.key.ble.BleController
import com.karr.key.ble.BleModeChangeExchange
import com.karr.key.ble.BleModeChangeResult
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.ModeChangeStage
import com.karr.key.core.Session
import com.karr.key.data.LocalDeviceReadStore
import com.karr.key.data.LocalOperationState
import com.karr.key.data.LocalOperationStore
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtDecodedResponse
import com.karr.key.protocol.QtErrorCode
import com.karr.key.protocol.QtPacket
import com.karr.key.protocol.QtProtocolCodec
import com.karr.key.protocol.QtResponseDecoder
import com.karr.key.protocol.RecoveredDeviceAction
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredPreflightDecision
import com.karr.key.protocol.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface CarOperationOutcome {
    data class ReadCompleted(val decoded: QtDecodedResponse?) : CarOperationOutcome
    data class CommandAcknowledged(val message: String) : CarOperationOutcome
    data class ModeConfirmed(val targetMode: KarrDeviceMode) : CarOperationOutcome
    data class Blocked(val reason: String) : CarOperationOutcome
    data class Failed(val reason: String) : CarOperationOutcome
}

internal fun CarOperationOutcome.summary(): String = when (this) {
    is CarOperationOutcome.CommandAcknowledged -> message
    is CarOperationOutcome.ModeConfirmed -> "Unit reports ${targetMode.displayName} mode."
    is CarOperationOutcome.ReadCompleted -> decoded?.heading ?: "No response data was decoded."
    is CarOperationOutcome.Blocked -> reason
    is CarOperationOutcome.Failed -> reason
}

/**
 * Sends a car-visible operation and logs it, built directly on the same platform-free
 * primitives [com.karr.key.ui.DeviceOperationExecutor] uses on the phone
 * ([BleCommandExchange], [BleModeChangeExchange], preflight evaluation) rather than that file
 * itself, since `android:auto` cannot depend on `android:app`.
 *
 * Deliberately not replicated here: auto-driving the unit through intermediate modes before a
 * blocked command, timeout-to-security-state reconciliation retries, and immobilizer-recovery
 * status gating. Each is a multi-step, multi-second automated sequence that's a reasonable phone
 * convenience but not something that should silently kick off from a car head unit tap — a
 * mode-blocked operation here just reports the block reason instead of auto-resolving it.
 */
internal class CarCommandExecutor(
    private val controller: BleController,
    private val operationStore: LocalOperationStore,
    private val deviceReadStore: LocalDeviceReadStore,
) {
    private val exchange = BleCommandExchange(controller)
    private val modeChangeExchange = BleModeChangeExchange(controller)

    fun close() {
        operationStore.close()
        deviceReadStore.close()
    }

    suspend fun execute(
        operation: RecoveredDeviceOperation,
        session: Session,
        deviceAuthenticated: Boolean,
        unitModel: KarrUnitModel?,
        unitMode: KarrDeviceMode?,
    ): CarOperationOutcome {
        val blockingReason = CarOperationPolicy.blockingReason(
            operation = operation,
            session = session,
            deviceAuthenticated = deviceAuthenticated,
            unitModel = unitModel,
            unitMode = unitMode,
        )
        if (blockingReason != null) {
            return CarOperationOutcome.Blocked(blockingReason)
        }
        // CarOperationPolicy.blockingReason already returned for a missing binding above.
        val binding = checkNotNull(CarOperationPolicy.customerBinding(operation))

        val preflightDecision = runPreflight(binding.action)
        val executableAction = when (preflightDecision) {
            is RecoveredPreflightDecision.Allowed -> binding.action.copy(
                command = preflightDecision.commandOverride ?: binding.action.command,
            )
            is RecoveredPreflightDecision.Denied ->
                return CarOperationOutcome.Blocked(preflightDecision.reason)
        }

        val connectedScope = when (val state = controller.state.value) {
            is BleSessionState.Ready -> state.name ?: state.address
            else -> "no-ready-device"
        }
        val storedOperation = withContext(Dispatchers.IO) {
            runCatching {
                operationStore.enqueue(
                    role = binding.feature.requiredRole,
                    type = "AUTO_${executableAction.command.name}",
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
            return executeModeChange(executableAction, targetMode, storedOperation?.id)
        }
        return executeStandardCommand(executableAction, unitModel, storedOperation?.id)
    }

    private suspend fun runPreflight(action: RecoveredDeviceAction): RecoveredPreflightDecision {
        val preflight = action.preflight ?: return RecoveredPreflightDecision.Allowed()
        val responses = mutableListOf<QtPacket>()
        preflight.requestCommands.forEach { requestCommand ->
            when (
                val result = exchange.sendAndAwait(
                    packet = QtProtocolCodec.generateOriginal(requestCommand, byteArrayOf(0)),
                    expectedCommand = requestCommand,
                    acceptGenericAck = false,
                )
            ) {
                is BleCommandExchangeResult.Acknowledged -> responses += result.response
                is BleCommandExchangeResult.Rejected ->
                    return RecoveredPreflightDecision.Denied(
                        ackErrorMessage(result.response, requestCommand, "Preflight rejected"),
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
        return preflight.evaluate(responses = responses, attemptedCommand = action.command)
    }

    private suspend fun executeModeChange(
        action: RecoveredDeviceAction,
        targetMode: KarrDeviceMode,
        operationId: String?,
    ): CarOperationOutcome {
        return when (
            val result = modeChangeExchange.changeAndConfirm(
                changePacket = action.packet(),
                targetMode = targetMode,
            )
        ) {
            is BleModeChangeResult.Confirmed -> {
                updateOperation(operationId, LocalOperationState.SUCCEEDED)
                CarOperationOutcome.ModeConfirmed(targetMode)
            }
            is BleModeChangeResult.ObservedDifferent -> {
                val error = "CHANGE_MODE was acknowledged, but the unit reports " +
                    "${result.observedMode.displayName} mode rather than " +
                    "${targetMode.displayName}."
                updateOperation(operationId, LocalOperationState.FAILED_PERMANENT, error)
                CarOperationOutcome.Failed(error)
            }
            is BleModeChangeResult.Rejected -> {
                val attemptedCommand = when (result.stage) {
                    ModeChangeStage.CHANGE_MODE -> QtCommand.CHANGE_MODE
                    ModeChangeStage.READ_MODE -> QtCommand.READ_MODE
                }
                val error = ackErrorMessage(
                    result.response,
                    attemptedCommand,
                    "The unit rejected ${result.stage.name}",
                )
                updateOperation(operationId, LocalOperationState.FAILED_PERMANENT, error)
                CarOperationOutcome.Failed(error)
            }
            is BleModeChangeResult.TimedOut -> {
                val error = "${result.stage.name} did not receive a unit response within 5 seconds."
                updateOperation(operationId, LocalOperationState.FAILED_PERMANENT, error)
                CarOperationOutcome.Failed(error)
            }
            is BleModeChangeResult.TransportFailure -> {
                updateOperation(operationId, LocalOperationState.FAILED_PERMANENT, result.message)
                CarOperationOutcome.Failed(result.message)
            }
            is BleModeChangeResult.MalformedRead -> {
                val error = "CHANGE_MODE was acknowledged, but READ_MODE returned no mode byte."
                updateOperation(operationId, LocalOperationState.FAILED_PERMANENT, error)
                CarOperationOutcome.Failed(error)
            }
        }
    }

    private suspend fun executeStandardCommand(
        action: RecoveredDeviceAction,
        unitModel: KarrUnitModel?,
        operationId: String?,
    ): CarOperationOutcome {
        return when (
            val result = exchange.sendAndAwait(
                packet = action.packet(),
                expectedCommand = action.command,
            )
        ) {
            is BleCommandExchangeResult.Acknowledged -> {
                val decoded = QtResponseDecoder.decode(result.response, unitModel)
                recordReadIfApplicable(action.command, action.mutating, decoded, unitModel)
                updateOperation(operationId, LocalOperationState.SUCCEEDED)
                if (action.mutating) {
                    CarOperationOutcome.CommandAcknowledged(
                        "${action.label} was acknowledged by the unit. Physical output is " +
                            "not confirmed.",
                    )
                } else {
                    CarOperationOutcome.ReadCompleted(decoded)
                }
            }
            is BleCommandExchangeResult.Rejected -> {
                val error = ackErrorMessage(result.response, action.command)
                updateOperation(operationId, LocalOperationState.FAILED_PERMANENT, error)
                CarOperationOutcome.Failed(error)
            }
            BleCommandExchangeResult.TimedOut -> {
                val error = "GATT accepted ${action.command.name}, but no matching unit " +
                    "response was observed within 5 seconds."
                updateOperation(
                    operationId,
                    if (action.singleShot) {
                        LocalOperationState.FAILED_PERMANENT
                    } else {
                        LocalOperationState.FAILED_RETRYABLE
                    },
                    error,
                )
                CarOperationOutcome.Failed(error)
            }
            is BleCommandExchangeResult.TransportFailure -> {
                updateOperation(
                    operationId,
                    if (action.singleShot) {
                        LocalOperationState.FAILED_PERMANENT
                    } else {
                        LocalOperationState.FAILED_RETRYABLE
                    },
                    result.message,
                )
                CarOperationOutcome.Failed(result.message)
            }
        }
    }

    private suspend fun recordReadIfApplicable(
        command: QtCommand,
        mutating: Boolean,
        decoded: QtDecodedResponse?,
        fallbackUnitModel: KarrUnitModel?,
    ) {
        if (mutating || decoded == null) return
        val ready = controller.state.value as? BleSessionState.Ready ?: return
        withContext(Dispatchers.IO) {
            deviceReadStore.record(
                deviceAddress = ready.address,
                command = command.name,
                heading = decoded.heading,
                lines = decoded.lines,
                unitModel = (decoded.unitModel ?: fallbackUnitModel)?.displayName,
            )
            decoded.vehicleVin?.let(deviceReadStore::autoDecodeVin)
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

    private fun ackErrorMessage(
        response: QtPacket,
        attemptedCommand: QtCommand,
        prefix: String = "The unit rejected $attemptedCommand",
    ): String {
        val rawCode = response.payload.singleOrNull()?.toInt()?.and(0xff)
        val error = QtErrorCode.fromAckError(response)
        return when {
            error != null ->
                "$prefix: ${error.displayName} " +
                    "(code ${error.code.toString(16).padStart(2, '0').uppercase()}). " +
                    error.explanation
            rawCode != null ->
                "$prefix with ACK_ERROR code ${rawCode.toString(16).padStart(2, '0').uppercase()}."
            else ->
                "$prefix with an empty or malformed ACK_ERROR payload."
        }
    }
}
