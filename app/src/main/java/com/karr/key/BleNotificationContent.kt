package com.karr.key

import com.karr.key.ble.BleSessionState
import com.karr.key.ble.DiscoveredDevice

internal enum class BleNotificationAction {
    STOP_SCAN,
    DISCONNECT,
}

internal data class BleNotificationContent(
    val title: String,
    val text: String,
    val expandedText: String,
    val action: BleNotificationAction,
    val showProgress: Boolean,
)

internal fun bleNotificationContent(
    state: BleSessionState,
    devices: List<DiscoveredDevice>,
): BleNotificationContent? = when (state) {
    BleSessionState.Idle,
    is BleSessionState.Error,
    is BleSessionState.ReconnectFailed,
    -> null

    BleSessionState.Scanning -> {
        val probeable = devices.filter(DiscoveredDevice::manualProbeEligible)
        val count = probeable.size
        val strongest = probeable.maxByOrNull(DiscoveredDevice::rssi)
        BleNotificationContent(
            title = when (count) {
                0 -> "KARR scan active"
                1 -> "1 KARR unit found"
                else -> "$count KARR units found"
            },
            text = strongest?.let { device ->
                "${device.displayName()} | ${device.rssi} dBm"
            } ?: "No KARR signals found yet",
            expandedText = strongest?.let { device ->
                "Device: ${device.displayName()}\n" +
                    "Address: ${device.address}\n" +
                    "Signal: ${device.rssi} dBm\n" +
                    "Scanning continues. " +
                    "Open KARR Key to connect."
            } ?: "Scanning for connectable confirmed or possible " +
                "KARR signals. Keep KARR Key visible for best results.",
            action = BleNotificationAction.STOP_SCAN,
            showProgress = true,
        )
    }

    is BleSessionState.Reconnecting -> BleNotificationContent(
        title = "Reconnecting to ${state.serial}",
        text = "Attempt ${state.attempt} of ${state.maxAttempts}",
        expandedText = "Device: ${state.serial}\nStatus: ${state.step}",
        action = BleNotificationAction.DISCONNECT,
        showProgress = true,
    )

    is BleSessionState.Connecting -> connectionContent(
        title = "Connecting to KARR device",
        address = state.address,
        name = devices.nameFor(state.address),
        status = "Opening Bluetooth connection",
    )

    is BleSessionState.DiscoveringUart -> connectionContent(
        title = "Connecting to KARR device",
        address = state.address,
        name = devices.nameFor(state.address),
        status = "Discovering KARR UART",
    )

    is BleSessionState.UartSubscribed -> connectionContent(
        title = "Connected to ${state.name ?: state.address}",
        address = state.address,
        name = state.name,
        status = "Waiting for device authentication",
    )

    is BleSessionState.AuthenticationRequired -> connectionContent(
        title = "Authenticating ${state.name ?: state.address}",
        address = state.address,
        name = state.name,
        status = "Challenge received (${state.challengeBytes} bytes)",
    )

    is BleSessionState.Authenticating -> connectionContent(
        title = "Authenticating ${state.name ?: state.address}",
        address = state.address,
        name = state.name,
        status = state.step,
    )

    is BleSessionState.Ready -> {
        val deviceName = state.name ?: state.address
        val model = state.unitModel?.displayName ?: "Model not reported"
        val mode = state.unitMode?.let { unitMode -> "${unitMode.displayName} mode" }
        BleNotificationContent(
            title = "Connected to $deviceName",
            text = listOfNotNull("Authenticated", model, mode).joinToString(" | "),
            expandedText = buildString {
                append("Device: ")
                append(deviceName)
                append("\nModel: ")
                append(model)
                mode?.let {
                    append("\nOperating mode: ")
                    append(it)
                }
                append("\nAddress: ")
                append(state.address)
                append("\nStatus: Authenticated and ready")
            },
            action = BleNotificationAction.DISCONNECT,
            showProgress = false,
        )
    }
}

private fun connectionContent(
    title: String,
    address: String,
    name: String?,
    status: String,
): BleNotificationContent {
    val deviceName = name ?: address
    return BleNotificationContent(
        title = title,
        text = status,
        expandedText = "Device: $deviceName\nAddress: $address\nStatus: $status",
        action = BleNotificationAction.DISCONNECT,
        showProgress = true,
    )
}

private fun List<DiscoveredDevice>.nameFor(address: String): String? =
    firstOrNull { device -> device.address == address }?.name

private fun DiscoveredDevice.displayName(): String = name ?: address
