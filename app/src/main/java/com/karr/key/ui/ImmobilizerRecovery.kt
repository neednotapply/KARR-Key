package com.karr.key.ui

import com.karr.key.core.AuthorizationDecision
import com.karr.key.core.RolePolicy
import com.karr.key.core.Session
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredModelCompatibility
import com.karr.key.protocol.RecoveredWorkflowCatalog
import com.karr.key.protocol.compatibilityWith

internal data class ImmobilizerRecoveryStatus(
    val operation: RecoveredDeviceOperation?,
    val available: Boolean,
    val reason: String?,
)

internal fun RecoveredDeviceOperation.isImmobilizerOutputCandidate(): Boolean =
    command == QtCommand.VEHICLE_CONTROL && payload == listOf(2)

internal fun immobilizerRecoveryStatus(
    session: Session,
    operation: RecoveredDeviceOperation,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode?,
): ImmobilizerRecoveryStatus? {
    if (!operation.isImmobilizerOutputCandidate()) return null

    val recovery = RecoveredWorkflowCatalog.operationById(IMMOBILIZER_RECOVERY_OPERATION_ID)
        ?: return ImmobilizerRecoveryStatus(
            operation = null,
            available = false,
            reason = "The recovered Restore drivable state operation is missing.",
        )
    if (!deviceAuthenticated) {
        return ImmobilizerRecoveryStatus(
            operation = recovery,
            available = false,
            reason = "Connect and authenticate the unit to verify the recovery operation.",
        )
    }

    val binding = recovery.preferredBinding(session, unitModel, unitMode)
    when (val decision = RolePolicy().authorize(session, binding.feature, deviceAuthenticated)) {
        AuthorizationDecision.Allowed -> Unit
        is AuthorizationDecision.Denied ->
            return ImmobilizerRecoveryStatus(
                operation = recovery,
                available = false,
                reason = decision.reason,
            )
    }

    return when (val compatibility = binding.action.compatibilityWith(unitModel, unitMode)) {
        RecoveredModelCompatibility.Supported -> ImmobilizerRecoveryStatus(
            operation = recovery,
            available = true,
            reason = null,
        )
        is RecoveredModelCompatibility.Unsupported -> ImmobilizerRecoveryStatus(
            operation = recovery,
            available = false,
            reason = compatibility.reason,
        )
        is RecoveredModelCompatibility.Unknown -> ImmobilizerRecoveryStatus(
            operation = recovery,
            available = false,
            reason = compatibility.reason,
        )
    }
}

private const val IMMOBILIZER_RECOVERY_OPERATION_ID = "unlock-doors-00"
