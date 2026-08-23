package com.karr.key.desktop.ui

import com.karr.key.core.AuthorizationDecision
import com.karr.key.core.RolePolicy
import com.karr.key.core.Session
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.RecoveredCompatibilityConstraint
import com.karr.key.protocol.RecoveredDeviceAction
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredModelCompatibility
import com.karr.key.protocol.RecoveredWorkflowCatalog
import com.karr.key.protocol.compatibilityWith

internal data class ModeSwitchOffer(
    val operation: RecoveredDeviceOperation,
    val nextMode: KarrDeviceMode,
    val destinationDescription: String,
    val totalSteps: Int,
)

internal fun modeSwitchOffer(
    session: Session,
    blockedAction: RecoveredDeviceAction,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode?,
): ModeSwitchOffer? {
    if (!deviceAuthenticated || unitMode == null) return null
    val requirement = blockedAction.modeRequirement ?: return null
    val compatibility = blockedAction.compatibilityWith(unitModel, unitMode)
    if (compatibility !is RecoveredModelCompatibility.Unsupported ||
        compatibility.constraint != RecoveredCompatibilityConstraint.UNIT_MODE
    ) {
        return null
    }

    data class SearchNode(
        val mode: KarrDeviceMode,
        val firstOperation: RecoveredDeviceOperation?,
        val firstTarget: KarrDeviceMode?,
        val depth: Int,
    )

    val pending = ArrayDeque<SearchNode>()
    val visited = mutableSetOf(unitMode)
    pending.add(
        SearchNode(
            mode = unitMode,
            firstOperation = null,
            firstTarget = null,
            depth = 0,
        ),
    )
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (current.mode in requirement.supportedModes && current.firstOperation != null) {
            return ModeSwitchOffer(
                operation = current.firstOperation,
                nextMode = requireNotNull(current.firstTarget),
                destinationDescription = requirement.supportedDescription,
                totalSteps = current.depth,
            )
        }

        RecoveredWorkflowCatalog.operations
            .asSequence()
            .filter { operation -> operation.command == QtCommand.CHANGE_MODE }
            .map { operation ->
                operation to operation.preferredBinding(session, unitModel, current.mode)
            }
            .filter { (_, binding) ->
                RolePolicy().authorize(
                    session = session,
                    feature = binding.feature,
                    deviceAuthenticated = true,
                ) == AuthorizationDecision.Allowed
            }
            .filter { (_, binding) ->
                binding.action.compatibilityWith(unitModel, current.mode) ==
                    RecoveredModelCompatibility.Supported
            }
            .mapNotNull { (operation, binding) ->
                binding.action.targetMode?.let { target -> operation to target }
            }
            .sortedBy { (_, target) -> target.code }
            .forEach { (operation, target) ->
                if (visited.add(target)) {
                    pending.add(
                        SearchNode(
                            mode = target,
                            firstOperation = current.firstOperation ?: operation,
                            firstTarget = current.firstTarget ?: target,
                            depth = current.depth + 1,
                        ),
                    )
                }
            }
    }
    return null
}
