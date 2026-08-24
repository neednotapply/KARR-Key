package com.karr.key.auto

import com.karr.key.core.AuthorizationDecision
import com.karr.key.core.RolePolicy
import com.karr.key.core.Session
import com.karr.key.core.UserRole
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredModelCompatibility
import com.karr.key.protocol.RecoveredOperationBinding
import com.karr.key.protocol.RecoveredWorkflowCatalog
import com.karr.key.protocol.compatibilityWith

/**
 * Which operations the Android Auto surface offers, and whether a given one can run right now.
 * Pure logic, no `CarContext`/BLE — shared by [OperationCategoryScreen] (to grey out a blocked
 * row with its reason) and [CarCommandExecutor] (to gate the actual send) so the two can't drift.
 * Dealer/Installer operations are bench/installation workflows and are never offered here,
 * regardless of the current session's roles.
 */
internal object CarOperationPolicy {
    fun carVisibleOperations(session: Session): List<RecoveredDeviceOperation> {
        if (UserRole.CUSTOMER !in session.roles) return emptyList()
        return RecoveredWorkflowCatalog.operations.filter { operation ->
            UserRole.CUSTOMER in operation.roles
        }
    }

    fun requiresConfirmation(operation: RecoveredDeviceOperation): Boolean = operation.mutating

    /** The Customer-role binding for [operation], or null if it has none. */
    fun customerBinding(operation: RecoveredDeviceOperation): RecoveredOperationBinding? =
        operation.bindings.firstOrNull { binding -> binding.feature.requiredRole == UserRole.CUSTOMER }

    /**
     * Why [operation] can't run right now, or null if it's allowed to send. Checked before a
     * command's preflight, which can still block execution beyond this point.
     */
    fun blockingReason(
        operation: RecoveredDeviceOperation,
        session: Session,
        deviceAuthenticated: Boolean,
        unitModel: KarrUnitModel?,
        unitMode: KarrDeviceMode?,
    ): String? {
        val binding = customerBinding(operation)
            ?: return "This operation is not available to the Customer role."
        val decision = RolePolicy().authorize(session, binding.feature, deviceAuthenticated)
        if (decision is AuthorizationDecision.Denied) return decision.reason
        if (!deviceAuthenticated) return "Connect and authenticate a unit."
        return when (val compatibility = binding.action.compatibilityWith(unitModel, unitMode)) {
            RecoveredModelCompatibility.Supported -> null
            is RecoveredModelCompatibility.Unsupported -> compatibility.reason
            is RecoveredModelCompatibility.Unknown -> compatibility.reason
        }
    }
}
