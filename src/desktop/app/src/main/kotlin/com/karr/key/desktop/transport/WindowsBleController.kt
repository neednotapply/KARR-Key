package com.karr.key.desktop.transport

import com.karr.key.ble.BleController
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.BleScanObservation
import com.karr.key.ble.CompatibleKarrDevice
import com.karr.key.ble.DiscoveredDevice
import com.karr.key.ble.isPatchedUnitDisconnect
import com.karr.key.ble.withReadModeObservation
import com.karr.key.core.KarrTrace
import com.karr.key.protocol.AuthenticationAction
import com.karr.key.protocol.DeviceAuthenticator
import com.karr.key.protocol.KarrAuthenticationSession
import com.karr.key.protocol.PacketParseResult
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtProtocolCodec
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A [BleController] for the desktop host, orchestrating the KARR session
 * (classification, the legacy REQUEST_AUTH -> READ_MODE -> PROVIDE_AUTH ->
 * AUTH_SUCCESS handshake via [KarrAuthenticationSession], and patched-unit
 * detection) over a raw [RawBleTransport]. The transport is the Windows-native
 * WinRT helper in production; a fake transport drives the same logic in tests, so
 * the orchestration is verified without a radio. This mirrors the Android
 * AndroidBleController's notification handling.
 *
 * Not yet included (v1): automatic reconnect and PHY diagnostics. Real-hardware
 * validation is pending — treat as source-backed until observed, matching the
 * project's convention for un-exercised paths.
 */
class WindowsBleController(
    authenticator: DeviceAuthenticator,
    private val transport: RawBleTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : BleController {
    private val authentication = KarrAuthenticationSession(authenticator)

    private val _state = MutableStateFlow<BleSessionState>(BleSessionState.Idle)
    override val state: StateFlow<BleSessionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _scanObservations = MutableSharedFlow<BleScanObservation>(extraBufferCapacity = 32)
    override val scanObservations: SharedFlow<BleScanObservation> = _scanObservations.asSharedFlow()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    override val notifications: SharedFlow<ByteArray> = _notifications.asSharedFlow()

    private val discovered = mutableMapOf<String, DiscoveredDevice>()
    private var connectedAddress: String? = null
    private var connectedName: String? = null
    private var uartSubscribed = false
    private var sawLegacyChallenge = false
    private var sawNonLegacyFrame = false

    init {
        scope.launch {
            transport.events.collect { event -> handleEvent(event) }
        }
    }

    override fun startScan(): Result<Unit> {
        KarrTrace.log(KarrTrace.SWEEP) { "manual scan started (windows)" }
        _state.value = BleSessionState.Scanning
        return transport.startScan()
    }

    override fun stopScan() {
        transport.stopScan()
        if (_state.value is BleSessionState.Scanning) _state.value = BleSessionState.Idle
    }

    override fun connect(address: String): Result<Unit> {
        resetSession()
        connectedAddress = address
        connectedName = discovered[address]?.name
        KarrTrace.log(KarrTrace.BLE) { "connect ${connectedName ?: address}" }
        _state.value = BleSessionState.Connecting(address)
        return transport.connect(address)
    }

    override fun disconnect() {
        KarrTrace.log(KarrTrace.BLE) { "disconnect (windows)" }
        transport.disconnect()
        resetSession()
        _state.value = BleSessionState.Idle
    }

    override fun setReconnectAllowed(allowed: Boolean) {
        KarrTrace.log(KarrTrace.POLICY) { "reconnect allowed=$allowed (windows; reconnect not yet implemented)" }
    }

    override fun retryReconnect(): Result<Unit> = Result.success(Unit)

    override suspend fun send(packet: ByteArray): Result<Unit> = transport.write(packet)

    override fun shutdown() {
        transport.shutdown()
        scope.cancel()
    }

    private fun resetSession() {
        authentication.reset()
        uartSubscribed = false
        sawLegacyChallenge = false
        sawNonLegacyFrame = false
    }

    private suspend fun handleEvent(event: RawBleEvent) {
        when (event) {
            is RawBleEvent.ScanResult -> onScanResult(event)
            is RawBleEvent.Connected -> {
                connectedAddress = event.address
                _state.value = BleSessionState.DiscoveringUart(event.address)
            }
            RawBleEvent.UartReady -> {
                uartSubscribed = true
                val address = connectedAddress ?: return
                _state.value = BleSessionState.UartSubscribed(address, connectedName)
            }
            is RawBleEvent.Notification -> onNotification(event.data)
            is RawBleEvent.Disconnected -> onDisconnected(event.status)
            is RawBleEvent.Failure -> {
                _state.value = BleSessionState.Error(event.message)
            }
        }
    }

    private fun onScanResult(result: RawBleEvent.ScanResult) {
        val serviceUuids = result.serviceUuids.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        val classification = CompatibleKarrDevice.classify(
            name = result.name,
            advertisedServices = serviceUuids,
            manufacturerData = result.manufacturerData,
        )
        val device = DiscoveredDevice(
            address = result.address,
            name = result.name,
            rssi = result.rssi,
            connectable = result.connectable,
            classification = classification,
        )
        discovered[result.address] = device
        _devices.value = discovered.values.sortedByDescending { it.rssi }
        val now = System.currentTimeMillis()
        _scanObservations.tryEmit(
            BleScanObservation(
                device = device,
                observedUtcMillis = now,
                observedElapsedRealtimeMillis = System.nanoTime() / 1_000_000,
            ),
        )
    }

    private suspend fun onNotification(value: ByteArray) {
        val copy = value.copyOf()
        val parsed = QtProtocolCodec.parse(copy)
        if (parsed is PacketParseResult.Success) {
            _state.value = _state.value.withReadModeObservation(parsed.packet)
        } else if (_state.value is BleSessionState.UartSubscribed) {
            sawNonLegacyFrame = true
        }
        _notifications.tryEmit(copy)
        if (parsed !is PacketParseResult.Success) return

        val address = connectedAddress ?: return
        val name = connectedName
        val action = authentication.onPacket(unitSerial = name.orEmpty(), packet = parsed.packet)

        if (parsed.packet.command == QtCommand.REQUEST_AUTH) {
            sawLegacyChallenge = true
            _state.value = BleSessionState.AuthenticationRequired(address, name, parsed.packet.payload.size)
        }
        when (action) {
            AuthenticationAction.None -> Unit
            is AuthenticationAction.Send -> {
                _state.value = when (action.command) {
                    QtCommand.READ_MODE -> BleSessionState.Authenticating(address, name, "Reading unit mode")
                    QtCommand.PROVIDE_AUTH ->
                        BleSessionState.Authenticating(address, name, "Sending local challenge response")
                    else -> BleSessionState.Authenticating(address, name, action.command.name)
                }
                val packet = QtProtocolCodec.generateOriginal(action.command, action.payload)
                transport.write(packet).onFailure {
                    authentication.reset()
                    _state.value = BleSessionState.Error(it.message ?: "KARR authentication write failed.")
                }
                if (action.command == QtCommand.PROVIDE_AUTH) {
                    action.payload.fill(0)
                    packet.fill(0)
                }
            }
            is AuthenticationAction.Authenticated -> {
                KarrTrace.log(KarrTrace.AUTH) {
                    "AUTH_SUCCESS ${name ?: address} model=${action.unitModel?.displayName ?: "unknown"} " +
                        "mode=${action.unitMode.name}"
                }
                _state.value = BleSessionState.Ready(
                    address = address,
                    name = name,
                    unitModel = action.unitModel,
                    unitMode = action.unitMode,
                )
            }
            is AuthenticationAction.Failed -> {
                KarrTrace.warn(KarrTrace.AUTH, "AUTH failed: ${action.reason}")
                _state.value = BleSessionState.Error(action.reason)
                transport.disconnect()
            }
        }
    }

    private fun onDisconnected(status: Int) {
        val previous = _state.value
        val patched = isPatchedUnitDisconnect(
            uartSubscribed = uartSubscribed,
            sawLegacyAuthChallenge = sawLegacyChallenge,
            isReconnect = false,
            previousState = previous,
        )
        _state.value = if (patched) {
            val evidence = if (sawNonLegacyFrame) {
                "The unit answered with a non-legacy frame and never issued the 8-byte challenge."
            } else {
                "The unit accepted the link but never issued the legacy authentication challenge."
            }
            BleSessionState.PatchedUnitDetected(
                address = connectedAddress.orEmpty(),
                name = connectedName,
                evidence = evidence,
            )
        } else {
            BleSessionState.Idle
        }
        resetSession()
    }
}
