package com.karr.key.desktop.ui

import com.karr.key.core.FeatureDefinition
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.RecoveredModelCompatibility
import com.karr.key.protocol.RecoveredWorkflowCatalog
import com.karr.key.protocol.compatibilityWith

internal sealed interface FeatureModelCompatibility {
    data object Supported : FeatureModelCompatibility
    data class Limited(val description: String) : FeatureModelCompatibility
    data class Unsupported(val description: String) : FeatureModelCompatibility
    data class Unknown(val description: String) : FeatureModelCompatibility
}

internal fun featureModelCompatibility(
    feature: FeatureDefinition,
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode? = null,
): FeatureModelCompatibility {
    val actions = RecoveredWorkflowCatalog.actionsFor(feature.id)
    if (actions.isEmpty()) {
        return FeatureModelCompatibility.Supported
    }
    val results = actions.map { action -> action.compatibilityWith(unitModel, unitMode) }
    if (results.all { result -> result == RecoveredModelCompatibility.Supported }) {
        return FeatureModelCompatibility.Supported
    }

    val modelName = unitModel?.displayName
    val hasSupported = results.any { result ->
        result == RecoveredModelCompatibility.Supported
    }
    val unsupported = results.filterIsInstance<RecoveredModelCompatibility.Unsupported>()
    val unknown = results.filterIsInstance<RecoveredModelCompatibility.Unknown>()

    return when {
        !hasSupported && unsupported.size == results.size ->
            FeatureModelCompatibility.Unsupported(
                "No commands in ${feature.title} are available for $modelName in its " +
                    "current mode.",
            )
        !hasSupported && unknown.size == results.size ->
            FeatureModelCompatibility.Unknown(
                "The connected unit's compatibility details must be identified before " +
                    "these commands can run.",
            )
        else ->
            FeatureModelCompatibility.Limited(
                if (modelName == null) {
                    "Some commands require confirmed unit compatibility details."
                } else {
                    "Some commands are not available for $modelName in its current mode."
                },
            )
    }
}
