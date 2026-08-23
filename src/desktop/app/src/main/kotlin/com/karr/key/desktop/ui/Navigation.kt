package com.karr.key.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.karr.key.desktop.resources.Res
import com.karr.key.desktop.resources.ic_nav_bluetooth
import com.karr.key.desktop.resources.ic_nav_home
import com.karr.key.desktop.resources.ic_nav_installer
import com.karr.key.desktop.resources.ic_nav_map
import com.karr.key.desktop.resources.ic_nav_settings
import com.karr.key.protocol.RecoveredDeviceOperation
import org.jetbrains.compose.resources.DrawableResource

/**
 * Ported from the Android app's Navigation.kt. The back-stack / section state
 * machine is unchanged; navigation3's NavKey marker, the Bundle-based Saver
 * (config-change restoration is an Android concern), and the R/BuildConfig
 * references are dropped for the desktop host.
 */
internal enum class TopLevelDestination(
    val label: String,
    val icon: DrawableResource,
) {
    HOME("Home", Res.drawable.ic_nav_home),
    DEVICE("Device", Res.drawable.ic_nav_bluetooth),
    OPERATIONS("Operations", Res.drawable.ic_nav_installer),
    MAP("Map", Res.drawable.ic_nav_map),
    SETTINGS("Settings", Res.drawable.ic_nav_settings),
}

internal enum class SettingsSection(val label: String) {
    ACCESS_KEYS("Access keys"),
    MAP("Map"),
    DIAGNOSTICS("Diagnostics"),
}

internal sealed interface AppRoute {
    companion object

    data object Home : AppRoute

    data object Operations : AppRoute

    data object Map : AppRoute

    data class Settings(val section: SettingsSection = SettingsSection.ACCESS_KEYS) : AppRoute

    data object Device : AppRoute

    data class OperationDetail(val operationId: String) : AppRoute

    data object CommandConsole : AppRoute
}

internal class KarrNavigationState {
    private val stacks = mutableStateMapOf<TopLevelDestination, SnapshotStateList<AppRoute>>()

    var selectedTopLevel by mutableStateOf(TopLevelDestination.HOME)
        private set

    var topLevelTransitionForward by mutableStateOf(true)
        private set

    init {
        TopLevelDestination.entries.forEach { destination ->
            stacks[destination] = listOf(rootRoute(destination)).toMutableStateList()
        }
    }

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
        switchTopLevel(TopLevelDestination.OPERATIONS, forwardWhenUnchanged = true)
    }

    fun openOperationsAfterSuccessfulConnection(
        wasAuthenticated: Boolean,
        isAuthenticated: Boolean,
    ): Boolean {
        if (wasAuthenticated || !isAuthenticated || currentRoute !is AppRoute.Device) {
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
        switchTopLevel(TopLevelDestination.OPERATIONS, forwardWhenUnchanged = true)
    }

    fun openCommandConsole() {
        if (!DesktopFlags.DEBUG) return
        val stack = stacks.getValue(TopLevelDestination.OPERATIONS)
        if (stack.firstOrNull() != AppRoute.Operations) {
            stack.clear()
            stack.add(AppRoute.Operations)
        }
        while (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        }
        stack.add(AppRoute.CommandConsole)
        switchTopLevel(TopLevelDestination.OPERATIONS, forwardWhenUnchanged = true)
    }

    fun openDevice() {
        val stack = stacks.getValue(TopLevelDestination.DEVICE)
        stack.clear()
        stack.add(AppRoute.Device)
        switchTopLevel(TopLevelDestination.DEVICE, forwardWhenUnchanged = true)
    }

    fun openSettings(section: SettingsSection = SettingsSection.ACCESS_KEYS) {
        val stack = stacks.getValue(TopLevelDestination.SETTINGS)
        stack.clear()
        stack.add(AppRoute.Settings(section))
        switchTopLevel(TopLevelDestination.SETTINGS, forwardWhenUnchanged = true)
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
}

private fun rootRoute(destination: TopLevelDestination): AppRoute = when (destination) {
    TopLevelDestination.HOME -> AppRoute.Home
    TopLevelDestination.OPERATIONS -> AppRoute.Operations
    TopLevelDestination.MAP -> AppRoute.Map
    TopLevelDestination.SETTINGS -> AppRoute.Settings()
    TopLevelDestination.DEVICE -> AppRoute.Device
}
