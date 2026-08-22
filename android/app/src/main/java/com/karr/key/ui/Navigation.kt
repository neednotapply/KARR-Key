package com.karr.key.ui

import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.navigation3.runtime.NavKey
import com.karr.key.BuildConfig
import com.karr.key.R
import com.karr.key.protocol.RecoveredDeviceOperation

internal enum class TopLevelDestination(
    val label: String,
    @param:DrawableRes val iconRes: Int,
) {
    HOME("Home", R.drawable.ic_nav_home),
    DEVICE("Device", R.drawable.ic_nav_bluetooth),
    OPERATIONS("Operations", R.drawable.ic_nav_installer),
    MAP("Map", R.drawable.ic_nav_map),
    SETTINGS("Settings", R.drawable.ic_nav_settings),
}

internal enum class SettingsSection(val label: String) {
    ACCESS_KEYS("Access keys"),
    MAP("Map"),
    DIAGNOSTICS("Diagnostics"),
}

internal sealed interface AppRoute : NavKey {
    companion object {}

    data object Home : AppRoute

    data object Operations : AppRoute

    data object Map : AppRoute

    data class Settings(val section: SettingsSection = SettingsSection.ACCESS_KEYS) : AppRoute

    data object Device : AppRoute

    data class OperationDetail(val operationId: String) : AppRoute

    data object CommandConsole : AppRoute
}

internal class KarrNavigationState private constructor(
    selected: TopLevelDestination,
    restoredStacks: Map<TopLevelDestination, List<AppRoute>>,
) {
    private val stacks = mutableStateMapOf<TopLevelDestination, SnapshotStateList<AppRoute>>()

    var selectedTopLevel by mutableStateOf(selected)
        private set

    var topLevelTransitionForward by mutableStateOf(true)
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
            switchTopLevel(destination)
        }
    }

    fun openOperations() {
        val stack = stacks.getValue(TopLevelDestination.OPERATIONS)
        stack.clear()
        stack.add(AppRoute.Operations)
        switchTopLevel(
            destination = TopLevelDestination.OPERATIONS,
            forwardWhenUnchanged = true,
        )
    }

    fun openOperationsAfterSuccessfulConnection(
        wasAuthenticated: Boolean,
        isAuthenticated: Boolean,
    ): Boolean {
        if (
            wasAuthenticated ||
            !isAuthenticated ||
            currentRoute !is AppRoute.Device
        ) {
            return false
        }
        openOperations()
        return true
    }

    fun openOperation(operation: RecoveredDeviceOperation) {
        val stack = stacks.getValue(TopLevelDestination.OPERATIONS)
        if (stack.firstOrNull() != AppRoute.Operations) {
            stack.clear()
            stack.add(AppRoute.Operations)
        }
        while (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        }
        stack.add(AppRoute.OperationDetail(operation.id))
        switchTopLevel(
            destination = TopLevelDestination.OPERATIONS,
            forwardWhenUnchanged = true,
        )
    }

    fun openCommandConsole() {
        if (!BuildConfig.DEBUG) return
        val stack = stacks.getValue(TopLevelDestination.OPERATIONS)
        if (stack.firstOrNull() != AppRoute.Operations) {
            stack.clear()
            stack.add(AppRoute.Operations)
        }
        while (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        }
        stack.add(AppRoute.CommandConsole)
        switchTopLevel(
            destination = TopLevelDestination.OPERATIONS,
            forwardWhenUnchanged = true,
        )
    }

    fun openDevice() {
        val stack = stacks.getValue(TopLevelDestination.DEVICE)
        stack.clear()
        stack.add(AppRoute.Device)
        switchTopLevel(
            destination = TopLevelDestination.DEVICE,
            forwardWhenUnchanged = true,
        )
    }

    fun openSettings(section: SettingsSection = SettingsSection.ACCESS_KEYS) {
        val stack = stacks.getValue(TopLevelDestination.SETTINGS)
        stack.clear()
        stack.add(AppRoute.Settings(section))
        switchTopLevel(
            destination = TopLevelDestination.SETTINGS,
            forwardWhenUnchanged = true,
        )
    }

    fun updateSettingsSection(section: SettingsSection) {
        val stack = stacks.getValue(TopLevelDestination.SETTINGS)
        val current = (stack.lastOrNull() as? AppRoute.Settings)?.section
        if (current != null && section != current) {
            topLevelTransitionForward = section.ordinal > current.ordinal
        }
        if (stack.isEmpty()) {
            stack.add(AppRoute.Settings(section))
        } else {
            stack[0] = AppRoute.Settings(section)
        }
    }

    /**
     * Advances one step in the swipe-navigation order: through Settings' own sub-sections while
     * Settings is active, otherwise to the next top-level destination. A no-op past the last
     * section of the last top-level destination. Callers are expected to withhold this while
     * [selectedTopLevel] is [TopLevelDestination.MAP], so swiping never leaves the Map screen.
     */
    fun swipeForward() {
        if (selectedTopLevel == TopLevelDestination.SETTINGS) {
            val current = (currentRoute as? AppRoute.Settings)?.section
                ?: SettingsSection.ACCESS_KEYS
            val nextOrdinal = current.ordinal + 1
            if (nextOrdinal < SettingsSection.entries.size) {
                updateSettingsSection(SettingsSection.entries[nextOrdinal])
            }
            return
        }
        val nextOrdinal = selectedTopLevel.ordinal + 1
        if (nextOrdinal < TopLevelDestination.entries.size) {
            selectTopLevel(TopLevelDestination.entries[nextOrdinal])
        }
    }

    /**
     * The reverse of [swipeForward]. Backing out of Settings' first sub-section falls through to
     * the previous top-level destination (Map), which swiping into is allowed even though
     * swiping back out of Map itself is not.
     */
    fun swipeBackward() {
        if (selectedTopLevel == TopLevelDestination.SETTINGS) {
            val current = (currentRoute as? AppRoute.Settings)?.section
                ?: SettingsSection.ACCESS_KEYS
            if (current.ordinal > 0) {
                updateSettingsSection(SettingsSection.entries[current.ordinal - 1])
                return
            }
        }
        val previousOrdinal = selectedTopLevel.ordinal - 1
        if (previousOrdinal >= 0) {
            selectTopLevel(TopLevelDestination.entries[previousOrdinal])
        }
    }

    fun navigateUp(): Boolean {
        val stack = stacks.getValue(selectedTopLevel)
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    internal fun stackFor(destination: TopLevelDestination): List<AppRoute> =
        stacks.getValue(destination).toList()

    private fun switchTopLevel(
        destination: TopLevelDestination,
        forwardWhenUnchanged: Boolean = false,
    ) {
        val destinationOffset = destination.ordinal - selectedTopLevel.ordinal
        topLevelTransitionForward = when {
            destinationOffset > 0 -> true
            destinationOffset < 0 -> false
            forwardWhenUnchanged -> true
            else -> topLevelTransitionForward
        }
        selectedTopLevel = destination
    }

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
                    ?.let { encoded ->
                        runCatching {
                            TopLevelDestination.valueOf(
                                if (encoded == "TOOLS") {
                                    TopLevelDestination.OPERATIONS.name
                                } else {
                                    encoded
                                },
                            )
                        }.getOrNull()
                    }
                    ?: TopLevelDestination.HOME
                val restored = TopLevelDestination.entries.associateWith { destination ->
                    val current = bundle.getStringArrayList(destination.name)
                    val legacy = if (destination == TopLevelDestination.OPERATIONS) {
                        bundle.getStringArrayList("TOOLS")
                    } else {
                        null
                    }
                    (current ?: legacy)
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
    TopLevelDestination.OPERATIONS -> AppRoute.Operations
    TopLevelDestination.MAP -> AppRoute.Map
    TopLevelDestination.SETTINGS -> AppRoute.Settings()
    TopLevelDestination.DEVICE -> AppRoute.Device
}

private fun AppRoute.encode(): String = when (this) {
    AppRoute.Home -> "home"
    AppRoute.Operations -> "operations"
    AppRoute.Map -> "map"
    is AppRoute.Settings -> "settings|${section.name}"
    AppRoute.Device -> "device"
    is AppRoute.OperationDetail -> "operation|$operationId"
    AppRoute.CommandConsole -> "command-console"
}

private fun AppRoute.Companion.decode(value: String): AppRoute? {
    val parts = value.split('|', limit = 3)
    return when (parts.firstOrNull()) {
        "home" -> AppRoute.Home
        "operations", "tools" -> AppRoute.Operations
        "map" -> AppRoute.Map
        "settings" -> AppRoute.Settings(
            section = parts.getOrNull(1)
                ?.let { encoded -> runCatching { SettingsSection.valueOf(encoded) }.getOrNull() }
                ?: SettingsSection.ACCESS_KEYS,
        )
        "device" -> AppRoute.Device
        "operation" -> parts.getOrNull(1)
            ?.takeIf(String::isNotBlank)
            ?.let(AppRoute::OperationDetail)
        "command-console" -> AppRoute.CommandConsole.takeIf { BuildConfig.DEBUG }
        // Old feature details pointed to an intermediate menu that no longer exists.
        "feature" -> null
        else -> null
    }
}
