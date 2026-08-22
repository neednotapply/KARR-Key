package com.karr.key.protocol

data class RecoveredModelRequirement(
    val supportedModels: Set<KarrUnitModel> = emptySet(),
    val supportedFamilies: Set<KarrUnitFamily> = emptySet(),
    val supportedDescription: String,
) {
    init {
        require(supportedModels.isNotEmpty() || supportedFamilies.isNotEmpty()) {
            "A model requirement must identify at least one supported model or family."
        }
        require(supportedDescription.isNotBlank())
    }

    fun supports(model: KarrUnitModel): Boolean =
        model in supportedModels || model.family in supportedFamilies
}

data class RecoveredModeRequirement(
    val supportedModes: Set<KarrDeviceMode>,
    val supportedDescription: String,
) {
    init {
        require(supportedModes.isNotEmpty())
        require(supportedDescription.isNotBlank())
    }

    fun supports(mode: KarrDeviceMode): Boolean = mode in supportedModes
}

sealed interface RecoveredModelCompatibility {
    data object Supported : RecoveredModelCompatibility
    data class Unsupported(
        val reason: String,
        val constraint: RecoveredCompatibilityConstraint,
    ) : RecoveredModelCompatibility
    data class Unknown(
        val reason: String,
        val constraint: RecoveredCompatibilityConstraint,
    ) : RecoveredModelCompatibility
}

enum class RecoveredCompatibilityConstraint {
    HARDWARE,
    UNIT_MODE,
}

fun RecoveredDeviceAction.compatibilityWith(
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode? = null,
): RecoveredModelCompatibility {
    modelRequirement?.let { requirement ->
        if (unitModel == null) {
            return RecoveredModelCompatibility.Unknown(
                "Connect and identify the unit model before using $label.",
                RecoveredCompatibilityConstraint.HARDWARE,
            )
        }
        if (!unitModel.isRecognized) {
            return RecoveredModelCompatibility.Unknown(
                "${unitModel.displayName} has no recovered compatibility mapping for $label.",
                RecoveredCompatibilityConstraint.HARDWARE,
            )
        }
        if (!requirement.supports(unitModel)) {
            return RecoveredModelCompatibility.Unsupported(
                "$label is supported only on ${requirement.supportedDescription}; " +
                    "the connected model is ${unitModel.displayName}.",
                RecoveredCompatibilityConstraint.HARDWARE,
            )
        }
    }

    modeRequirement?.let { requirement ->
        if (unitMode == null) {
            return RecoveredModelCompatibility.Unknown(
                "Connect and identify the unit operating mode before using $label.",
                RecoveredCompatibilityConstraint.UNIT_MODE,
            )
        }
        if (!requirement.supports(unitMode)) {
            return RecoveredModelCompatibility.Unsupported(
                "$label is available only in ${requirement.supportedDescription}; " +
                    "the connected unit is in ${unitMode.displayName} mode.",
                RecoveredCompatibilityConstraint.UNIT_MODE,
            )
        }
    }

    return RecoveredModelCompatibility.Supported
}
