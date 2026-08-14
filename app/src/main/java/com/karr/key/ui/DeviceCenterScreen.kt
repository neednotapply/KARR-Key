package com.karr.key.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.karr.key.R
import com.karr.key.ble.BleController
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.DiscoveredDevice
import com.karr.key.ble.KarrCandidateTier
import kotlinx.coroutines.launch

@Composable
internal fun DeviceCenterScreen(
    controller: BleController,
    autoConnectReadEnabled: Boolean,
    onAutoConnectReadEnabledChanged: (Boolean) -> Unit,
    requestPermissions: (((Boolean) -> Unit) -> Unit),
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val devices by controller.devices.collectAsState()
    val karrDevices = karrDevicesForConnection(devices)
    val otherDevices = otherDevicesForConnection(devices)
    val karrUnitCount = karrDevices.size
    val nearbyCount = devices.size
    val scope = rememberCoroutineScope()
    var showOtherDevices by remember { mutableStateOf(false) }

    val startScan = {
        requestPermissions { granted ->
            if (granted) {
                controller.startScan().onFailure { failure ->
                    scope.launch {
                        snackbar.showSnackbar(failure.message ?: "BLE scan failed.")
                    }
                }
            } else {
                scope.launch {
                    snackbar.showSnackbar("Bluetooth permission was not granted.")
                }
            }
        }
    }

    ConnectionSection(
        state = state,
        nearbyCount = nearbyCount,
        karrDevices = karrDevices,
        otherDevices = otherDevices,
        karrUnitCount = karrUnitCount,
        showOtherDevices = showOtherDevices,
        onShowOtherDevicesChanged = { showOtherDevices = it },
        autoConnectReadEnabled = autoConnectReadEnabled,
        onAutoConnectReadEnabledChanged = onAutoConnectReadEnabledChanged,
        onScan = startScan,
        onStopScan = controller::stopScan,
        onDisconnect = controller::disconnect,
        onRetryReconnect = {
            controller.retryReconnect().onFailure { failure ->
                scope.launch {
                    snackbar.showSnackbar(
                        failure.message ?: "Automatic reconnect could not be retried.",
                    )
                }
            }
        },
        onConnect = { device ->
            controller.connect(device.address).onFailure { failure ->
                scope.launch {
                    snackbar.showSnackbar(
                        failure.message ?: "Connection failed.",
                    )
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun ConnectionSection(
    state: BleSessionState,
    nearbyCount: Int,
    karrDevices: List<DiscoveredDevice>,
    otherDevices: List<DiscoveredDevice>,
    karrUnitCount: Int,
    showOtherDevices: Boolean,
    onShowOtherDevicesChanged: (Boolean) -> Unit,
    autoConnectReadEnabled: Boolean,
    onAutoConnectReadEnabledChanged: (Boolean) -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onRetryReconnect: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 20.dp,
            end = 16.dp,
            bottom = 32.dp,
        ),
    ) {
        item {
            SectionHeader(
                title = "Connection",
                supportingText = "Unexpected drops reconnect automatically.",
            )
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = state.connectionStatusLabel(),
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = state.connectionStatusLabel()
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = if (state is BleSessionState.Scanning) {
                                onStopScan
                            } else {
                                onScan
                            },
                            enabled = state is BleSessionState.Scanning || state.canScan(),
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (state is BleSessionState.Scanning) {
                                        R.drawable.ic_stop
                                    } else {
                                        R.drawable.ic_wifi
                                    },
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = if (state is BleSessionState.Scanning) "Stop" else "Scan",
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        if (state is BleSessionState.ReconnectFailed) {
                            OutlinedButton(onClick = onRetryReconnect) {
                                Text("Retry reconnect")
                            }
                        }
                        OutlinedButton(
                            onClick = onDisconnect,
                            enabled = state.canDisconnect(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_block),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = if (
                                    state is BleSessionState.Reconnecting ||
                                    state is BleSessionState.ReconnectFailed
                                ) {
                                    "Cancel reconnect"
                                } else {
                                    "Disconnect"
                                },
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Auto-connect & read",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "While scanning, automatically connects to the next confirmed " +
                                "KARR unit found, reads all unit settings once connected, " +
                                "then disconnects and resumes scanning for the next unit.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoConnectReadEnabled,
                        onCheckedChange = onAutoConnectReadEnabledChanged,
                    )
                }
            }
        }
        item {
            SectionHeader(
                title = scanCountLabel(
                    karrUnitCount,
                    nearbyCount,
                ),
            )
        }
        if (karrDevices.isEmpty()) {
            item {
                Text(
                    if (state is BleSessionState.Scanning) {
                        "Scanning for KARR signals."
                    } else {
                        "No KARR signals found."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(karrDevices, key = { "karr:${it.address}" }) { device ->
                DiscoveredDeviceCard(
                    device = device,
                    onConnect = if (device.manualProbeEligible) {
                        { onConnect(device) }
                    } else {
                        null
                    },
                )
            }
        }
        if (otherDevices.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = {
                        onShowOtherDevicesChanged(!showOtherDevices)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (showOtherDevices) {
                            "Hide other nearby (${otherDevices.size})"
                        } else {
                            "Show other nearby (${otherDevices.size})"
                        },
                    )
                }
            }
        }
        if (showOtherDevices) {
            item {
                SectionHeader(
                    title = "Other nearby advertisements",
                    supportingText = "No recovered KARR identifier was found.",
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
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                device.name ?: "Unnamed BLE device",
                style = MaterialTheme.typography.titleMedium,
            )
            CodeSurface("${device.address}  RSSI ${device.rssi}")
            val isConfirmed =
                device.classification.tier == KarrCandidateTier.CONFIRMED_FORMAT
            Text(
                device.classificationDescription(),
                color = if (isConfirmed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (device.classification.isCandidate && !device.connectable) {
                Text(
                    "Broadcast-only advertisement; connection unavailable.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (onConnect != null) {
                OutlinedButton(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(device.connectionActionLabel())
                }
            }
        }
    }
}

private fun BleSessionState.canDisconnect(): Boolean =
    this is BleSessionState.Ready ||
        this is BleSessionState.Reconnecting ||
        this is BleSessionState.ReconnectFailed ||
        this is BleSessionState.Connecting ||
        this is BleSessionState.DiscoveringUart ||
        this is BleSessionState.UartSubscribed ||
        this is BleSessionState.AuthenticationRequired ||
        this is BleSessionState.Authenticating

private fun BleSessionState.canScan(): Boolean =
    this is BleSessionState.Idle ||
        this is BleSessionState.Error ||
        this is BleSessionState.ReconnectFailed

internal fun BleSessionState.connectionStatusLabel(): String = when (this) {
    BleSessionState.Idle -> "Idle"
    BleSessionState.Scanning -> "Scanning"
    is BleSessionState.Reconnecting ->
        "Reconnecting to $serial (attempt $attempt of $maxAttempts): $step"
    is BleSessionState.ReconnectFailed ->
        "Reconnect to $serial stopped after $attempts attempts: $message"
    is BleSessionState.Connecting -> "Connecting to $address"
    is BleSessionState.DiscoveringUart -> "Discovering KARR UART on $address"
    is BleSessionState.UartSubscribed ->
        "Bluetooth connected: ${name ?: address}; waiting for KARR authentication"
    is BleSessionState.AuthenticationRequired ->
        "KARR authentication required: ${name ?: address} " +
            "($challengeBytes-byte challenge). Bluetooth link is healthy."
    is BleSessionState.Authenticating ->
        "Authenticating ${name ?: address}: $step"
    is BleSessionState.Ready ->
        "Authenticated and ready: ${name ?: address}; " +
            listOfNotNull(
                unitModel?.displayName,
                unitMode?.let { mode -> "${mode.displayName} mode" },
            ).ifEmpty {
                listOf("compatibility details not identified")
            }.joinToString("; ")
    is BleSessionState.Error -> "Error: $message"
}

internal fun scanCountLabel(
    karrUnitCount: Int,
    nearbyCount: Int,
): String {
    val karrUnits = if (karrUnitCount == 1) {
        "1 KARR unit"
    } else {
        "$karrUnitCount KARR units"
    }
    return "$karrUnits / $nearbyCount nearby"
}

internal fun karrDevicesForConnection(
    devices: List<DiscoveredDevice>,
): List<DiscoveredDevice> = devices
    .asSequence()
    .filter { it.classification.isCandidate }
    .sortedWith(
        compareByDescending<DiscoveredDevice> { it.rssi }
            .thenBy { it.name ?: it.address },
    )
    .toList()

internal fun DiscoveredDevice.classificationDescription(): String =
    when (classification.tier) {
        KarrCandidateTier.CONFIRMED_FORMAT ->
            "Confirmed KARR format: ${classification.evidence}"
        KarrCandidateTier.POSSIBLE ->
            "Possible KARR match: ${classification.evidence}"
        KarrCandidateTier.OTHER -> classification.evidence
    }

internal fun DiscoveredDevice.connectionActionLabel(): String =
    if (classification.tier == KarrCandidateTier.CONFIRMED_FORMAT) {
        "Connect"
    } else {
        "Probe connection"
    }

internal fun otherDevicesForConnection(
    devices: List<DiscoveredDevice>,
): List<DiscoveredDevice> = devices.filter {
    it.classification.tier == KarrCandidateTier.OTHER
}
