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
import com.karr.key.R
import com.karr.key.protocol.RecoveredDeviceOperation

internal enum class TopLevelDestination(
    val label: String,
    @param:DrawableRes val iconRes: Int,
) {
    HOME("Home", R.drawable.ic_nav_home),
    OPERATIONS("Operations", R.drawable.ic_nav_installer),
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

    data object Operations : AppRoute

    data class Device(val section: DeviceSection = DeviceSection.CONNECTION) : AppRoute

    data class OperationDetail(val operationId: String) : AppRoute
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

    fun moveTopLevelBy(offset: Int): Boolean {
        require(offset == -1 || offset == 1) {
            "Top-level navigation only supports adjacent moves."
        }
        if (currentBackStack.size > 1) return false
        val destination = TopLevelDestination.entries
            .getOrNull(selectedTopLevel.ordinal + offset)
            ?: return false
        switchTopLevel(destination)
        return true
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

    fun openDevice(section: DeviceSection = DeviceSection.CONNECTION) {
        val stack = stacks.getValue(TopLevelDestination.DEVICE)
        stack.clear()
        stack.add(AppRoute.Device(section))
        switchTopLevel(
            destination = TopLevelDestination.DEVICE,
            forwardWhenUnchanged = true,
        )
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
    TopLevelDestination.DEVICE -> AppRoute.Device()
}

private fun AppRoute.encode(): String = when (this) {
    AppRoute.Home -> "home"
    AppRoute.Operations -> "operations"
    is AppRoute.Device -> "device|${section.name}"
    is AppRoute.OperationDetail -> "operation|$operationId"
}

private fun AppRoute.Companion.decode(value: String): AppRoute? {
    val parts = value.split('|', limit = 3)
    return when (parts.firstOrNull()) {
        "home" -> AppRoute.Home
        "operations", "tools" -> AppRoute.Operations
        "device" -> AppRoute.Device(
            section = parts.getOrNull(1)
                ?.let { encoded -> runCatching { DeviceSection.valueOf(encoded) }.getOrNull() }
                ?: DeviceSection.CONNECTION,
        )
        "operation" -> parts.getOrNull(1)
            ?.takeIf(String::isNotBlank)
            ?.let(AppRoute::OperationDetail)
        // Old feature details pointed to an intermediate menu that no longer exists.
        "feature" -> null
        else -> null
    }
}
