package com.karr.key.desktop.transport

import com.karr.key.ble.BleController
import com.karr.key.ble.BleLinkDiagnostics
import com.karr.key.ble.BlePhy
import com.karr.key.ble.BleScanObservation
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.CompatibleKarrDevice
import com.karr.key.ble.DiscoveredDevice
import com.karr.key.core.KarrTrace
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.PacketParseResult
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtProtocolCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A simulated [BleController] for desktop development without a radio or a real
 * KARR unit. It walks the same session state machine the Android controller and
 * the Windows-native controller drive — scan, connect, UART, the legacy auth
 * exchange, then Ready — and emits the same [KarrTrace] channels, so the
 * Terminal shows an authentic-looking live session and every UI screen can be
 * exercised end to end. No real key material or crypto is involved; the mock is
 * both the transport and the fake unit.
 */
class MockBleController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val unitModel: KarrUnitModel = KarrUnitModel.DR1_CAN,
    private val unitMode: KarrDeviceMode = KarrDeviceMode.USER,
) : BleController {

    private val fakeUnit = DiscoveredDevice(
        address = MOCK_ADDRESS,
        name = MOCK_NAME,
        rssi = -58,
        connectable = true,
        classification = CompatibleKarrDevice.classify(MOCK_NAME),
    )

    private val _state = MutableStateFlow<BleSessionState>(BleSessionState.Idle)
    override val state: StateFlow<BleSessionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _scanObservations = MutableSharedFlow<BleScanObservation>(extraBufferCapacity = 16)
    override val scanObservations: SharedFlow<BleScanObservation> = _scanObservations.asSharedFlow()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    override val notifications: SharedFlow<ByteArray> = _notifications.asSharedFlow()

    private var scanJob: Job? = null
    private var connectJob: Job? = null

    override fun startScan(): Result<Unit> {
        KarrTrace.log(KarrTrace.SWEEP) { "manual scan started (mock)" }
        _state.value = BleSessionState.Scanning
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(500)
            _devices.value = listOf(fakeUnit)
            val now = System.currentTimeMillis()
            _scanObservations.emit(
                BleScanObservation(
                    device = fakeUnit,
                    observedUtcMillis = now,
                    observedElapsedRealtimeMillis = System.nanoTime() / 1_000_000,
                ),
            )
            KarrTrace.log(KarrTrace.SWEEP) {
                "candidate ${fakeUnit.name} rssi=${fakeUnit.rssi} " +
                    fakeUnit.classification.tier.name
            }
        }
        return Result.success(Unit)
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_state.value is BleSessionState.Scanning) {
            _state.value = BleSessionState.Idle
        }
        KarrTrace.log(KarrTrace.SWEEP) { "scan stopped (mock)" }
    }

    override fun connect(address: String): Result<Unit> {
        val target = _devices.value.firstOrNull { it.address == address } ?: fakeUnit
        KarrTrace.log(KarrTrace.BLE) { "connect ${target.name} ($address)" }
        connectJob?.cancel()
        connectJob = scope.launch {
            _state.value = BleSessionState.Connecting(address)
            delay(400)
            KarrTrace.log(KarrTrace.BLE) { "GATT connected; discovering UART service" }
            _state.value = BleSessionState.DiscoveringUart(address)
            delay(400)
            KarrTrace.log(KarrTrace.BLE) { "UART subscribed" }
            _state.value = BleSessionState.UartSubscribed(address, target.name)
            delay(300)
            KarrTrace.log(KarrTrace.AUTH) { "REQUEST_AUTH challenge received (8 bytes)" }
            _state.value = BleSessionState.AuthenticationRequired(address, target.name, 8)
            delay(300)
            KarrTrace.log(KarrTrace.AUTH) { "READ_MODE, selecting provisioned material, PROVIDE_AUTH" }
            _state.value = BleSessionState.Authenticating(address, target.name, "PROVIDE_AUTH")
            delay(500)
            KarrTrace.log(KarrTrace.AUTH) { "AUTH_SUCCESS" }
            KarrTrace.log(KarrTrace.BLE) {
                "ready model=${unitModel.displayName} mode=${unitMode.displayName}"
            }
            _state.value = BleSessionState.Ready(
                address = address,
                name = target.name,
                unitModel = unitModel,
                unitMode = unitMode,
                linkDiagnostics = BleLinkDiagnostics(
                    transmitPhy = BlePhy.LE_1M,
                    receivePhy = BlePhy.LE_1M,
                ),
            )
        }
        return Result.success(Unit)
    }

    override fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        KarrTrace.log(KarrTrace.BLE) { "disconnect (mock)" }
        _state.value = BleSessionState.Idle
    }

    override fun setReconnectAllowed(allowed: Boolean) {
        KarrTrace.log(KarrTrace.POLICY) { "reconnect allowed=$allowed (mock)" }
    }

    override fun retryReconnect(): Result<Unit> = Result.success(Unit)

    override suspend fun send(packet: ByteArray): Result<Unit> {
        val parsed = QtProtocolCodec.parse(packet) as? PacketParseResult.Success
        val command = parsed?.packet?.command
        KarrTrace.log(KarrTrace.CMD) {
            "send ${command?.name ?: "raw"} tx=${KarrTrace.describeOutgoing(packet)}"
        }
        // Mimic a unit reply so command exchanges resolve. A real response schema
        // per command is layered in as command screens are ported.
        scope.launch {
            delay(120)
            val reply = QtProtocolCodec.generateOriginal(QtCommand.ACK_OK)
            _notifications.emit(reply)
            KarrTrace.log(KarrTrace.CMD) { "rx ACK_OK (mock)" }
        }
        return Result.success(Unit)
    }

    override fun shutdown() {
        scope.cancel()
    }

    private companion object {
        const val MOCK_ADDRESS = "AA:BB:CC:DD:EE:01"
        const val MOCK_NAME = "DR 1A2B3C4"
    }
}
