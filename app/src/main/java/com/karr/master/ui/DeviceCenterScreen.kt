package com.karr.master.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.karr.master.ble.BleController
import com.karr.master.ble.BleSessionState
import com.karr.master.ble.DiscoveredDevice
import com.karr.master.ble.KarrCandidateTier
import com.karr.master.ble.KarrGatt
import com.karr.master.protocol.PacketParseResult
import com.karr.master.protocol.QtCommand
import com.karr.master.protocol.QtProtocolCodec
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun DeviceCenterScreen(
    controller: BleController,
    requestPermissions: (((Boolean) -> Unit) -> Unit),
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val devices by controller.devices.collectAsState()
    val candidates = devices.filter { it.classification.isCandidate }
    val otherDevices = devices.filterNot { it.classification.isCandidate }
    val confirmedCount = candidates.count {
        it.classification.tier == KarrCandidateTier.CONFIRMED_FORMAT
    }
    val possibleCount = candidates.size - confirmedCount
    val scope = rememberCoroutineScope()
    var lastNotification by remember { mutableStateOf<String?>(null) }
    var selfTest by remember { mutableStateOf<String?>(null) }
    var showOtherDevices by remember { mutableStateOf(false) }

    LaunchedEffect(controller) {
        controller.notifications.collectLatest { bytes ->
            lastNotification = when (val parsed = QtProtocolCodec.parse(bytes)) {
                is PacketParseResult.Success -> {
                    val command = parsed.packet.command?.name
                        ?: "COMMAND_${parsed.packet.commandCode}"
                    "${bytes.toHex()}  -> $command (${parsed.packet.payload.size} data bytes)"
                }
                is PacketParseResult.Failure -> "${bytes.toHex()}  -> ${parsed.reason}"
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Device center", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Manual BLE discovery and the shared KARR UART protocol layer.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    Text(state.label(confirmedCount, possibleCount, devices.size))
                    Text(
                        "UART service: ${KarrGatt.UART_SERVICE}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                requestPermissions { granted ->
                                    if (granted) {
                                        controller.startScan().onFailure { failure ->
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    failure.message ?: "BLE scan failed.",
                                                )
                                            }
                                        }
                                    } else {
                                        scope.launch {
                                            snackbar.showSnackbar("Bluetooth permission was not granted.")
                                        }
                                    }
                                }
                            },
                            enabled = state !is BleSessionState.Scanning,
                        ) {
                            Text("Scan")
                        }
                        OutlinedButton(
                            onClick = controller::stopScan,
                            enabled = state is BleSessionState.Scanning,
                        ) {
                            Text("Stop")
                        }
                        OutlinedButton(
                            onClick = controller::disconnect,
                            enabled = state is BleSessionState.Ready ||
                                state is BleSessionState.Connecting ||
                                state is BleSessionState.DiscoveringUart ||
                                state is BleSessionState.UartSubscribed ||
                                state is BleSessionState.AuthenticationRequired,
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Protocol diagnostics", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The local self-test builds and parses READ_STATE without contacting a device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val packet = QtProtocolCodec.generateOriginal(QtCommand.READ_STATE)
                                selfTest = when (val parsed = QtProtocolCodec.parse(packet)) {
                                    is PacketParseResult.Success ->
                                        "${packet.toHex()} -> ${parsed.packet.command?.name}"
                                    is PacketParseResult.Failure -> parsed.reason
                                }
                            },
                        ) {
                            Text("Run self-test")
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    controller.send(
                                        QtProtocolCodec.generateOriginal(QtCommand.READ_STATE),
                                    ).onSuccess {
                                        snackbar.showSnackbar("READ_STATE sent.")
                                    }.onFailure { failure ->
                                        snackbar.showSnackbar(
                                            failure.message ?: "Command send failed.",
                                        )
                                    }
                                }
                            },
                            enabled = state is BleSessionState.Ready,
                        ) {
                            Text("Send READ_STATE")
                        }
                    }
                    if (state is BleSessionState.AuthenticationRequired) {
                        Text(
                            "The unit requested application authentication. The recovered " +
                                "local challenge flow is the next compatibility layer; " +
                                "Android Bluetooth pairing alone does not satisfy it.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    selfTest?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    lastNotification?.let {
                        Text("Last notification: $it", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item {
            Text(
                scanCountLabel(confirmedCount, possibleCount, devices.size),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Confirmed formats come from the recovered apps: DR/QT serial names " +
                    "and dealer manufacturer frames. Weaker evidence is marked Possible.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (candidates.isEmpty()) {
            item {
                Text(
                    if (state is BleSessionState.Scanning) {
                        "Scanning for KARR-format signals; all nearby BLE advertisements " +
                            "are still being counted."
                    } else {
                        "No KARR-format signals found. Scanning never auto-connects."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(candidates, key = { "candidate:${it.address}" }) { device ->
                DiscoveredDeviceCard(
                    device = device,
                    onConnect = if (device.connectionEligible) {
                        {
                            controller.connect(device.address).onFailure { failure ->
                                scope.launch {
                                    snackbar.showSnackbar(
                                        failure.message ?: "Connection failed.",
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
        if (otherDevices.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = { showOtherDevices = !showOtherDevices },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (showOtherDevices) {
                            "Hide Other Nearby (${otherDevices.size})"
                        } else {
                            "Show Other Nearby (${otherDevices.size})"
                        },
                    )
                }
            }
        }
        if (showOtherDevices) {
            item {
                Text(
                    "Other nearby advertisements",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Visible for diagnosis only. Connect is disabled because these " +
                        "advertisements contain no recovered KARR identifier.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(otherDevices, key = { "other:${it.address}" }) { device ->
                DiscoveredDeviceCard(device = device, onConnect = null)
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceCard(
    device: DiscoveredDevice,
    onConnect: (() -> Unit)?,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name ?: "Unnamed BLE device")
                Text(
                    "${device.address}  RSSI ${device.rssi}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    when (device.classification.tier) {
                        KarrCandidateTier.CONFIRMED_FORMAT ->
                            "Confirmed KARR format: ${device.classification.evidence}"
                        KarrCandidateTier.POSSIBLE ->
                            "Possible KARR: ${device.classification.evidence}"
                        KarrCandidateTier.OTHER ->
                            device.classification.evidence
                    },
                    color = when (device.classification.tier) {
                        KarrCandidateTier.CONFIRMED_FORMAT ->
                            MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (device.classification.isCandidate && !device.connectable) {
                    Text(
                        "Broadcast-only advertisement; connection unavailable.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (
                    device.classification.tier == KarrCandidateTier.POSSIBLE
                ) {
                    Text(
                        "Possible evidence only; connection disabled.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (onConnect != null) {
                OutlinedButton(onClick = onConnect) {
                    Text("Connect")
                }
            }
        }
    }
}

private fun BleSessionState.label(
    confirmedCount: Int,
    possibleCount: Int,
    nearbyCount: Int,
): String = when (this) {
    BleSessionState.Idle -> "Idle"
    BleSessionState.Scanning ->
        "Scanning - ${scanCountLabel(confirmedCount, possibleCount, nearbyCount)}"
    is BleSessionState.Connecting -> "Connecting to $address"
    is BleSessionState.DiscoveringUart -> "Discovering KARR UART on $address"
    is BleSessionState.UartSubscribed ->
        "BLE connected: ${name ?: address}; waiting for KARR authentication"
    is BleSessionState.AuthenticationRequired ->
        "KARR authentication required: ${name ?: address} " +
            "($challengeBytes-byte challenge). BLE link is healthy."
    is BleSessionState.Ready -> "Authenticated and ready: ${name ?: address}"
    is BleSessionState.Error -> "Error: $message"
}

private fun scanCountLabel(
    confirmedCount: Int,
    possibleCount: Int,
    nearbyCount: Int,
): String =
    "$confirmedCount Confirmed KARR / $possibleCount Possible Signals" +
        " / $nearbyCount Nearby Devices"

private fun ByteArray.toHex(): String =
    joinToString(separator = " ") { byte -> "%02X".format(byte.toInt() and 0xff) }
