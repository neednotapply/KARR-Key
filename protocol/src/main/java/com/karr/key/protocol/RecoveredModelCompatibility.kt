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

sealed interface RecoveredModelCompatibility {
    data object Supported : RecoveredModelCompatibility
    data class Unsupported(val reason: String) : RecoveredModelCompatibility
    data class Unknown(val reason: String) : RecoveredModelCompatibility
}

fun RecoveredDeviceAction.compatibilityWith(
    unitModel: KarrUnitModel?,
): RecoveredModelCompatibility {
    val requirement = modelRequirement ?: return RecoveredModelCompatibility.Supported
    if (unitModel == null) {
        return RecoveredModelCompatibility.Unknown(
            "Connect and identify the unit model before using $label.",
        )
    }
    if (!unitModel.isRecognized) {
        return RecoveredModelCompatibility.Unknown(
            "${unitModel.displayName} has no recovered compatibility mapping for $label.",
        )
    }
    return if (requirement.supports(unitModel)) {
        RecoveredModelCompatibility.Supported
    } else {
        RecoveredModelCompatibility.Unsupported(
            "$label is supported only on ${requirement.supportedDescription}; " +
                "the connected model is ${unitModel.displayName}.",
        )
    }
}
