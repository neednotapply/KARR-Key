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

internal data class DeviceDiscoveryContent(
    val title: String,
    val text: String,
    val expandedText: String,
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
        val compatible = devices.filter(DiscoveredDevice::connectionEligible)
        val count = compatible.size
        BleNotificationContent(
            title = "Scanning for KARR devices",
            text = when (count) {
                0 -> "No compatible devices found yet"
                1 -> "1 compatible device found"
                else -> "$count compatible devices found"
            },
            expandedText = if (count == 0) {
                "Looking for connectable devices with a confirmed KARR identifier."
            } else {
                "Open KARR Key to review compatible devices. " +
                    "Scanning continues until you stop or connect."
            },
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
        BleNotificationContent(
            title = "Connected to $deviceName",
            text = "Authenticated | $model",
            expandedText = buildString {
                append("Device: ")
                append(deviceName)
                append("\nModel: ")
                append(model)
                append("\nAddress: ")
                append(state.address)
                append("\nStatus: Authenticated and ready")
            },
            action = BleNotificationAction.DISCONNECT,
            showProgress = false,
        )
    }
}

internal fun newlyDiscoveredCompatibleDevices(
    devices: List<DiscoveredDevice>,
    alreadyAlertedAddresses: Set<String>,
): List<DiscoveredDevice> = devices.filter { device ->
    device.connectionEligible && device.address !in alreadyAlertedAddresses
}

internal fun deviceDiscoveryContent(
    device: DiscoveredDevice,
    compatibleCount: Int,
): DeviceDiscoveryContent {
    val deviceName = device.displayName()
    return DeviceDiscoveryContent(
        title = if (compatibleCount == 1) {
            "KARR device found"
        } else {
            "$compatibleCount KARR devices found"
        },
        text = "$deviceName | ${device.rssi} dBm",
        expandedText = "Device: $deviceName\n" +
            "Address: ${device.address}\n" +
            "Signal: ${device.rssi} dBm\n" +
            "Open KARR Key to connect.",
    )
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
