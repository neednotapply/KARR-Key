package com.karr.master.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import com.karr.master.protocol.PacketParseResult
import com.karr.master.protocol.QtCommand
import com.karr.master.protocol.QtProtocolCodec
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

@SuppressLint("MissingPermission")
class AndroidBleController(
    context: Context,
) : BleController {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = manager?.adapter

    private val writeMutex = Mutex()
    private val discovered = ConcurrentHashMap<String, DiscoveredDevice>()
    private val _state = MutableStateFlow<BleSessionState>(BleSessionState.Idle)
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    private val _notifications = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 32,
    )

    private var gatt: BluetoothGatt? = null
    private var uartCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var pendingWrite: CompletableDeferred<Boolean>? = null

    override val state: StateFlow<BleSessionState> = _state.asStateFlow()
    override val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()
    override val notifications: SharedFlow<ByteArray> = _notifications.asSharedFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            recordScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::recordScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = BleSessionState.Error("BLE scan failed with code $errorCode.")
        }
    }

    private fun recordScanResult(result: ScanResult) {
        val device = result.device ?: return
        val scanRecord = result.scanRecord
        val existing = discovered[device.address]
        val name = device.name ?: scanRecord?.deviceName ?: existing?.name
        val advertisedServices = scanRecord
            ?.serviceUuids
            ?.map { parcelUuid -> parcelUuid.uuid }
            .orEmpty()
        val manufacturerData = buildList {
            val records = scanRecord?.manufacturerSpecificData
            if (records != null) {
                for (index in 0 until records.size()) {
                    add(records.valueAt(index).copyOf())
                }
            }
        }
        val observedClassification = CompatibleKarrDevice.classify(
            name = name,
            advertisedServices = advertisedServices,
            manufacturerData = manufacturerData,
        )
        val existingClassification = existing?.classification
        val classification = if (
            existingClassification != null &&
            existingClassification.tier.rank > observedClassification.tier.rank
        ) {
            existingClassification
        } else {
            observedClassification
        }
        val displayName = name
            ?: classification.recoveredName
            ?: existing?.classification?.recoveredName
        discovered[device.address] = DiscoveredDevice(
            address = device.address,
            name = displayName,
            rssi = result.rssi,
            classification = classification,
        )
        _devices.value = discovered.values.sortedWith(
            compareByDescending<DiscoveredDevice> { it.classification.tier.rank }
                .thenByDescending { it.rssi }
                .thenBy { it.name ?: it.address },
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            callbackGatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                _state.value = BleSessionState.DiscoveringUart(callbackGatt.device.address)
                if (!callbackGatt.discoverServices()) {
                    failAndClose("GATT service discovery could not be started.", callbackGatt)
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val previousState = _state.value
                pendingWrite?.complete(false)
                closeGatt(callbackGatt)
                _state.value = if (status == BluetoothGatt.GATT_SUCCESS) {
                    BleSessionState.Idle
                } else {
                    BleSessionState.Error(
                        GattStatus.disconnectMessage(status, previousState),
                    )
                }
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                pendingWrite?.complete(false)
                failAndClose("BLE connection failed with GATT status $status.", callbackGatt)
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose("GATT service discovery failed with status $status.", callbackGatt)
                return
            }
            val characteristic = callbackGatt
                .getService(KarrGatt.UART_SERVICE)
                ?.getCharacteristic(KarrGatt.UART_CHARACTERISTIC)
            if (characteristic == null) {
                failAndClose("The KARR transparent UART characteristic was not found.", callbackGatt)
                return
            }
            uartCharacteristic = characteristic
            if (!callbackGatt.setCharacteristicNotification(characteristic, true)) {
                failAndClose("UART notifications could not be enabled.", callbackGatt)
                return
            }
            val descriptor = characteristic.getDescriptor(KarrGatt.CLIENT_CONFIGURATION)
            if (descriptor == null) {
                failAndClose("The KARR UART notification descriptor was not found.", callbackGatt)
                return
            }
            val notificationValue =
                if (characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                ) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else if (characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                ) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    failAndClose("The KARR UART does not support notifications.", callbackGatt)
                    return
                }
            val descriptorWriteStarted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    callbackGatt.writeDescriptor(
                        descriptor,
                        notificationValue,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = notificationValue
                    @Suppress("DEPRECATION")
                    callbackGatt.writeDescriptor(descriptor)
                }
            if (!descriptorWriteStarted) {
                failAndClose("UART notification setup could not be started.", callbackGatt)
            }
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != KarrGatt.CLIENT_CONFIGURATION) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose(
                    "UART notification setup failed with GATT status $status.",
                    callbackGatt,
                )
                return
            }
            _state.value = BleSessionState.UartSubscribed(
                address = callbackGatt.device.address,
                name = connectedName(callbackGatt),
            )
        }

        @Deprecated("Used on Android 12 and earlier")
        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            characteristic.value?.copyOf()?.let { value ->
                handleNotification(callbackGatt, value)
            }
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(callbackGatt, value)
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    override fun startScan(): Result<Unit> = runCatching {
        val activeAdapter = requireNotNull(adapter) { "Bluetooth is not available." }
        check(activeAdapter.isEnabled) { "Bluetooth is turned off." }
        val scanner = requireNotNull(activeAdapter.bluetoothLeScanner) {
            "Bluetooth LE scanning is unavailable."
        }
        stopScan()
        discovered.clear()
        _devices.value = emptyList()
        scanner.startScan(scanCallback)
        _state.value = BleSessionState.Scanning
    }

    override fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_state.value == BleSessionState.Scanning) {
            _state.value = BleSessionState.Idle
        }
    }

    override fun connect(address: String): Result<Unit> = runCatching {
        val activeAdapter = requireNotNull(adapter) { "Bluetooth is not available." }
        check(activeAdapter.isEnabled) { "Bluetooth is turned off." }
        check(discovered[address]?.classification?.isCandidate == true) {
            "This advertisement has no recovered KARR identifier."
        }
        stopScan()
        disconnect()
        _state.value = BleSessionState.Connecting(address)
        gatt = activeAdapter.getRemoteDevice(address).connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDeviceTransport.LE,
        )
    }

    override fun disconnect() {
        pendingWrite?.cancel()
        pendingWrite = null
        uartCharacteristic = null
        gatt?.let { activeGatt ->
            runCatching { activeGatt.disconnect() }
            closeGatt(activeGatt)
        }
        gatt = null
        if (_state.value !is BleSessionState.Error) {
            _state.value = BleSessionState.Idle
        }
    }

    override suspend fun send(packet: ByteArray): Result<Unit> = runCatching {
        require(packet.isNotEmpty()) { "Cannot send an empty BLE packet." }
        writeMutex.withLock {
            val activeGatt = requireNotNull(gatt) { "No BLE device is connected." }
            val characteristic = requireNotNull(uartCharacteristic) {
                "KARR UART is not ready."
            }
            check(_state.value is BleSessionState.Ready) { "BLE session is not ready." }

            val supportsResponse =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val writeType = if (supportsResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            val completion = CompletableDeferred<Boolean>()
            pendingWrite = completion
            try {
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activeGatt.writeCharacteristic(characteristic, packet, writeType) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = writeType
                    @Suppress("DEPRECATION")
                    characteristic.value = packet
                    @Suppress("DEPRECATION")
                    activeGatt.writeCharacteristic(characteristic)
                }
                check(started) { "GATT characteristic write could not be started." }
                if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                    check(withTimeout(WRITE_TIMEOUT_MS) { completion.await() }) {
                        "GATT characteristic write failed."
                    }
                }
            } finally {
                if (pendingWrite === completion) {
                    pendingWrite = null
                }
            }
        }
    }

    override fun shutdown() {
        stopScan()
        disconnect()
    }

    private fun failAndClose(message: String, callbackGatt: BluetoothGatt) {
        pendingWrite?.complete(false)
        closeGatt(callbackGatt)
        _state.value = BleSessionState.Error(message)
    }

    private fun handleNotification(
        callbackGatt: BluetoothGatt,
        value: ByteArray,
    ) {
        val copy = value.copyOf()
        _notifications.tryEmit(copy)
        when (val parsed = QtProtocolCodec.parse(copy)) {
            is PacketParseResult.Failure -> Unit
            is PacketParseResult.Success -> {
                when (parsed.packet.command) {
                    QtCommand.REQUEST_AUTH -> {
                        _state.value = BleSessionState.AuthenticationRequired(
                            address = callbackGatt.device.address,
                            name = connectedName(callbackGatt),
                            challengeBytes = parsed.packet.payload.size,
                        )
                    }
                    QtCommand.AUTH_SUCCESS -> {
                        _state.value = BleSessionState.Ready(
                            address = callbackGatt.device.address,
                            name = connectedName(callbackGatt),
                        )
                    }
                    QtCommand.AUTH_FAILED -> {
                        _state.value = BleSessionState.Error(
                            "KARR application authentication was rejected.",
                        )
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun connectedName(callbackGatt: BluetoothGatt): String? =
        discovered[callbackGatt.device.address]?.name ?: callbackGatt.device.name

    private fun closeGatt(callbackGatt: BluetoothGatt) {
        runCatching { callbackGatt.close() }
        if (gatt === callbackGatt) {
            gatt = null
            uartCharacteristic = null
        }
    }

    private object BluetoothDeviceTransport {
        const val LE = 2
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 10_000L
    }
}
