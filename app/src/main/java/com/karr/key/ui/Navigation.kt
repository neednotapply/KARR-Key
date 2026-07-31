package com.karr.key.ui

import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.Saver
import androidx.navigation3.runtime.NavKey
import com.karr.key.R
import com.karr.key.core.FeatureDefinition
import com.karr.key.core.UserRole

internal enum class TopLevelDestination(
    val label: String,
    @param:DrawableRes val iconRes: Int,
) {
    HOME("Home", R.drawable.ic_nav_home),
    TOOLS("Tools", R.drawable.ic_nav_installer),
    DEVICE("Device", R.drawable.ic_nav_bluetooth),
}

internal enum class DeviceSection(val label: String) {
    CONNECTION("Connection"),
    ACCESS_KEYS("Access keys"),
    DIAGNOSTICS("Diagnostics"),
}

internal sealed interface AppRoute : NavKey {
    companion object {}

    data object Home : AppRoute

    data class Tools(val role: UserRole? = null) : AppRoute

    data class Device(val section: DeviceSection = DeviceSection.CONNECTION) : AppRoute

    data class FeatureDetail(
        val featureId: String,
        val role: UserRole,
    ) : AppRoute
}

internal class KarrNavigationState private constructor(
    selected: TopLevelDestination,
    restoredStacks: Map<TopLevelDestination, List<AppRoute>>,
) {
    private val stacks = mutableStateMapOf<TopLevelDestination, SnapshotStateList<AppRoute>>()

    var selectedTopLevel by mutableStateOf(selected)
        private set

    init {
        TopLevelDestination.entries.forEach { destination ->
            val restored = restoredStacks[destination].orEmpty()
            stacks[destination] = (
                if (restored.isEmpty()) {
                    listOf(rootRoute(destination))
                } else {
                    restored
                }
                ).toMutableStateList()
        }
    }

    constructor() : this(
        selected = TopLevelDestination.HOME,
        restoredStacks = emptyMap(),
    )

    val currentBackStack: List<AppRoute>
        get() = stacks.getValue(selectedTopLevel)

    val currentRoute: AppRoute
        get() = currentBackStack.last()

    fun selectTopLevel(destination: TopLevelDestination) {
        if (selectedTopLevel == destination) {
            popToRoot(destination)
        } else {
            selectedTopLevel = destination
        }
    }

    fun moveTopLevelBy(offset: Int): Boolean {
        require(offset == -1 || offset == 1) {
            "Top-level navigation only supports adjacent moves."
        }
        if (currentBackStack.size > 1) return false
        val destination = TopLevelDestination.entries
            .getOrNull(selectedTopLevel.ordinal + offset)
            ?: return false
        selectedTopLevel = destination
        return true
    }

    fun openTools(role: UserRole?) {
        val stack = stacks.getValue(TopLevelDestination.TOOLS)
        stack.clear()
        stack.add(AppRoute.Tools(role))
        selectedTopLevel = TopLevelDestination.TOOLS
    }

    fun updateToolsRole(role: UserRole?) {
        val stack = stacks.getValue(TopLevelDestination.TOOLS)
        val rootIndex = stack.indexOfFirst { it is AppRoute.Tools }
        if (rootIndex >= 0) {
            stack[rootIndex] = AppRoute.Tools(role)
        } else {
            stack.add(0, AppRoute.Tools(role))
        }
    }

    fun openFeature(feature: FeatureDefinition) {
        val stack = stacks.getValue(TopLevelDestination.TOOLS)
        val role = feature.requiredRole
        if (stack.firstOrNull() !is AppRoute.Tools) {
            stack.clear()
            stack.add(AppRoute.Tools(role))
        } else {
            stack[0] = AppRoute.Tools(role)
        }
        while (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        }
        stack.add(AppRoute.FeatureDetail(feature.id, role))
        selectedTopLevel = TopLevelDestination.TOOLS
    }

    fun openDevice(section: DeviceSection = DeviceSection.CONNECTION) {
        val stack = stacks.getValue(TopLevelDestination.DEVICE)
        stack.clear()
        stack.add(AppRoute.Device(section))
        selectedTopLevel = TopLevelDestination.DEVICE
    }

    fun updateDeviceSection(section: DeviceSection) {
        val stack = stacks.getValue(TopLevelDestination.DEVICE)
        if (stack.isEmpty()) {
            stack.add(AppRoute.Device(section))
        } else {
            stack[0] = AppRoute.Device(section)
        }
    }

    fun navigateUp(): Boolean {
        val stack = stacks.getValue(selectedTopLevel)
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun normalizeRoles(availableRoles: Set<UserRole>) {
        val stack = stacks.getValue(TopLevelDestination.TOOLS)
        val selectedRole = when (val route = stack.lastOrNull()) {
            is AppRoute.Tools -> route.role
            is AppRoute.FeatureDetail -> route.role
            else -> null
        }
        if (selectedRole != null && selectedRole !in availableRoles) {
            stack.clear()
            stack.add(
                AppRoute.Tools(
                    UserRole.entries.firstOrNull(availableRoles::contains),
                ),
            )
        }
    }

    internal fun stackFor(destination: TopLevelDestination): List<AppRoute> =
        stacks.getValue(destination).toList()

    private fun popToRoot(destination: TopLevelDestination) {
        val stack = stacks.getValue(destination)
        while (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        }
    }

    companion object {
        val Saver: Saver<KarrNavigationState, Bundle> = Saver(
            save = { state ->
                Bundle().apply {
                    putString(KEY_SELECTED, state.selectedTopLevel.name)
                    TopLevelDestination.entries.forEach { destination ->
                        putStringArrayList(
                            destination.name,
                            ArrayList(state.stackFor(destination).map { it.encode() }),
                        )
                    }
                }
            },
            restore = { bundle ->
                val selected = bundle.getString(KEY_SELECTED)
                    ?.let(TopLevelDestination::valueOf)
                    ?: TopLevelDestination.HOME
                val restored = TopLevelDestination.entries.associateWith { destination ->
                    bundle.getStringArrayList(destination.name)
                        .orEmpty()
                        .mapNotNull(AppRoute::decode)
                }
                KarrNavigationState(selected, restored)
            },
        )

        private const val KEY_SELECTED = "selected_top_level"
    }
}

private fun rootRoute(destination: TopLevelDestination): AppRoute = when (destination) {
    TopLevelDestination.HOME -> AppRoute.Home
    TopLevelDestination.TOOLS -> AppRoute.Tools()
    TopLevelDestination.DEVICE -> AppRoute.Device()
}

private fun AppRoute.encode(): String = when (this) {
    AppRoute.Home -> "home"
    is AppRoute.Tools -> "tools|${role?.name.orEmpty()}"
    is AppRoute.Device -> "device|${section.name}"
    is AppRoute.FeatureDetail -> "feature|${role.name}|$featureId"
}

private fun AppRoute.Companion.decode(value: String): AppRoute? {
    val parts = value.split('|', limit = 3)
    return when (parts.firstOrNull()) {
        "home" -> AppRoute.Home
        "tools" -> AppRoute.Tools(
            role = parts.getOrNull(1)
                ?.takeIf(String::isNotBlank)
                ?.let(UserRole::valueOf),
        )
        "device" -> AppRoute.Device(
            section = parts.getOrNull(1)
                ?.let(DeviceSection::valueOf)
                ?: DeviceSection.CONNECTION,
        )
        "feature" -> {
            val role = parts.getOrNull(1)?.let(UserRole::valueOf) ?: return null
            val featureId = parts.getOrNull(2) ?: return null
            AppRoute.FeatureDetail(featureId, role)
        }
        else -> null
    }
}
