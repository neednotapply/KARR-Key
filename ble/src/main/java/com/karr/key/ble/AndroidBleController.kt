package com.karr.key.ble

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
import android.os.SystemClock
import com.karr.key.protocol.AuthenticationAction
import com.karr.key.protocol.DeviceAuthenticator
import com.karr.key.protocol.KarrAuthenticationSession
import com.karr.key.protocol.PacketParseResult
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtProtocolCodec
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
class AndroidBleController(
    context: Context,
    authenticator: DeviceAuthenticator,
) : BleController {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = manager?.adapter

    private val writeMutex = Mutex()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authentication = KarrAuthenticationSession(authenticator)
    private val notificationEvents = Channel<NotificationEvent>(Channel.UNLIMITED)
    private val discovered = ConcurrentHashMap<String, TimedDiscoveredDevice>()
    private val _state = MutableStateFlow<BleSessionState>(BleSessionState.Idle)
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    private val _notifications = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 32,
    )

    @Volatile
    private var gatt: BluetoothGatt? = null
    @Volatile
    private var uartCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var reconnectAllowed = false
    @Volatile
    private var reconnectScanResult: CompletableDeferred<DiscoveredDevice?>? = null
    @Volatile
    private var reconnectScanFailure: String? = null
    @Volatile
    private var reconnectAttemptResult: CompletableDeferred<ReconnectAttemptResult>? = null
    @Volatile
    private var pendingWrite: CompletableDeferred<GattWriteResult>? = null
    @Volatile
    private var connectionOrigin = ConnectionOrigin.MANUAL
    @Volatile
    private var activeConnectionTarget: ReconnectTarget? = null
    @Volatile
    private var lastAuthenticatedTarget: ReconnectTarget? = null
    @Volatile
    private var reconnectTarget: ReconnectTarget? = null
    @Volatile
    private var reconnectAttempt = 0
    @Volatile
    private var reconnectJob: Job? = null
    private var shuttingDown = false

    override val state: StateFlow<BleSessionState> = _state.asStateFlow()
    override val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()
    override val notifications: SharedFlow<ByteArray> = _notifications.asSharedFlow()

    init {
        callbackScope.launch {
            for (event in notificationEvents) {
                processNotification(event)
            }
        }
        callbackScope.launch {
            while (isActive) {
                delay(DiscoveryFreshness.SWEEP_INTERVAL_MILLIS)
                pruneStaleDevices(SystemClock.elapsedRealtime())
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            recordScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::recordScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            val recoveryScan = reconnectScanResult
            if (recoveryScan != null) {
                reconnectScanFailure = GattStatus.scanFailure(errorCode)
                recoveryScan.complete(null)
            } else {
                _state.value = BleSessionState.Error(GattStatus.scanFailure(errorCode))
            }
        }
    }

    private fun recordScanResult(result: ScanResult) {
        val device = result.device ?: return
        val scanRecord = result.scanRecord
        val existing = discovered[device.address]?.device
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
        val observedConnectable =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || result.isConnectable
        val discoveredDevice = DiscoveredDevice(
            address = device.address,
            name = displayName,
            rssi = result.rssi,
            connectable = existing?.connectable == true || observedConnectable,
            classification = classification,
        )
        discovered[device.address] = TimedDiscoveredDevice(
            device = discoveredDevice,
            lastSeenElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
        )
        publishDevices()
        val target = reconnectTarget
        if (target != null && ReconnectPolicy.matches(target, discoveredDevice)) {
            reconnectScanResult?.complete(discoveredDevice)
        }
    }

    private fun pruneStaleDevices(nowElapsedRealtimeMillis: Long) {
        var removedAny = false
        discovered.entries.forEach { (address, observation) ->
            if (
                DiscoveryFreshness.isExpired(
                    lastSeenElapsedRealtimeMillis =
                        observation.lastSeenElapsedRealtimeMillis,
                    nowElapsedRealtimeMillis = nowElapsedRealtimeMillis,
                ) &&
                discovered.remove(address, observation)
            ) {
                removedAny = true
            }
        }
        if (removedAny) {
            publishDevices()
        }
    }

    private fun publishDevices() {
        _devices.value = discovered.values
            .map(TimedDiscoveredDevice::device)
            .sortedWith(
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
            if (gatt !== callbackGatt) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runCatching { callbackGatt.disconnect() }
                }
                closeGatt(callbackGatt)
                return
            }
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
                val failure = GattStatus.disconnectMessage(status, previousState)
                pendingWrite?.complete(GattWriteResult.LinkLost(failure))
                authentication.reset()
                closeGatt(callbackGatt)
                when {
                    previousState is BleSessionState.Ready -> {
                        val target = lastAuthenticatedTarget
                        if (target == null) {
                            _state.value = BleSessionState.Error(
                                "$failure Automatic reconnect is unavailable because the " +
                                    "authenticated unit had no stable DR/QT serial.",
                            )
                        } else {
                            reconnectTarget = target
                            queueReconnect(target)
                        }
                    }
                    connectionOrigin == ConnectionOrigin.RECONNECT -> {
                        completeReconnectAttempt(
                            ReconnectAttemptResult.Retryable(failure),
                        )
                    }
                    status == BluetoothGatt.GATT_SUCCESS -> {
                        _state.value = BleSessionState.Idle
                    }
                    else -> {
                        _state.value = BleSessionState.Error(failure)
                    }
                }
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose(
                    GattStatus.disconnectMessage(status, _state.value),
                    callbackGatt,
                )
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            if (gatt !== callbackGatt) {
                closeGatt(callbackGatt)
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose(
                    GattStatus.operationFailure("KARR service discovery", status),
                    callbackGatt,
                )
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
            if (gatt !== callbackGatt) {
                closeGatt(callbackGatt)
                return
            }
            if (descriptor.uuid != KarrGatt.CLIENT_CONFIGURATION) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndClose(
                    GattStatus.operationFailure("UART notification setup", status),
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
            if (gatt !== callbackGatt) {
                return
            }
            pendingWrite?.complete(GattWriteResult.Completed(status))
        }
    }

    override fun startScan(): Result<Unit> = runCatching {
        val activeAdapter = requireNotNull(adapter) { "Bluetooth is not available." }
        check(activeAdapter.isEnabled) { "Bluetooth is turned off." }
        val scanner = requireNotNull(activeAdapter.bluetoothLeScanner) {
            "Bluetooth LE scanning is unavailable."
        }
        cancelReconnect(clearTarget = true, closeReconnectConnection = true)
        stopScanner()
        discovered.clear()
        _devices.value = emptyList()
        scanner.startScan(scanCallback)
        _state.value = BleSessionState.Scanning
    }

    override fun stopScan() {
        stopScanner()
        if (_state.value == BleSessionState.Scanning) {
            _state.value = BleSessionState.Idle
        }
    }

    override fun connect(address: String): Result<Unit> = runCatching {
        val activeAdapter = requireNotNull(adapter) { "Bluetooth is not available." }
        check(activeAdapter.isEnabled) { "Bluetooth is turned off." }
        val candidate = checkNotNull(discovered[address]?.device) {
            "This device is no longer being advertised nearby. Scan again."
        }
        check(candidate.classification.isCandidate) {
            "This advertisement has no recovered KARR identifier."
        }
        check(candidate.connectionEligible) {
            "Connect requires a connectable advertisement with a confirmed KARR identifier."
        }
        stopScanner()
        disconnect()
        beginDirectConnection(
            activeAdapter = activeAdapter,
            address = address,
            origin = ConnectionOrigin.MANUAL,
            target = ReconnectPolicy.targetFor(candidate),
            showConnectingState = true,
        )
    }

    override fun disconnect() {
        cancelReconnect(clearTarget = true, closeReconnectConnection = true)
        lastAuthenticatedTarget = null
        disconnectActiveGatt(
            "The Bluetooth connection was manually disconnected before the KARR unit " +
                "acknowledged the pending UART write.",
        )
        _state.value = BleSessionState.Idle
    }

    override fun setReconnectAllowed(allowed: Boolean) {
        reconnectAllowed = allowed
        if (allowed) {
            if (!shuttingDown && reconnectTarget != null && _state.value !is BleSessionState.Ready) {
                startReconnectCycle()
            }
            return
        }

        val target = reconnectTarget ?: return
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectScanResult?.cancel()
        reconnectScanResult = null
        reconnectScanFailure = null
        reconnectAttemptResult?.cancel()
        reconnectAttemptResult = null
        stopScanner()
        if (connectionOrigin == ConnectionOrigin.RECONNECT &&
            _state.value !is BleSessionState.Ready
        ) {
            disconnectActiveGatt(
                "Automatic reconnect paused because the app entered the background.",
            )
        }
        _state.value = BleSessionState.Reconnecting(
            serial = target.serial,
            attempt = reconnectAttempt,
            maxAttempts = ReconnectPolicy.maxAttempts,
            step = "Paused while the app is in the background",
        )
    }

    override fun retryReconnect(): Result<Unit> = runCatching {
        check(reconnectAllowed) {
            "Bring the app to the foreground before retrying the Bluetooth connection."
        }
        check(reconnectTarget != null) {
            "There is no previously authenticated KARR unit to reconnect."
        }
        check(reconnectJob?.isActive != true) {
            "Automatic reconnect is already running."
        }
        reconnectAttempt = 0
        startReconnectCycle()
    }

    private fun disconnectActiveGatt(reason: String) {
        authentication.reset()
        pendingWrite?.complete(GattWriteResult.LinkLost(reason))
        pendingWrite = null
        uartCharacteristic = null
        gatt?.let { activeGatt ->
            runCatching { activeGatt.disconnect() }
            closeGatt(activeGatt)
        }
        gatt = null
        activeConnectionTarget = null
        connectionOrigin = ConnectionOrigin.MANUAL
    }

    private fun queueReconnect(target: ReconnectTarget) {
        if (!reconnectAllowed || shuttingDown) {
            _state.value = BleSessionState.Reconnecting(
                serial = target.serial,
                attempt = reconnectAttempt,
                maxAttempts = ReconnectPolicy.maxAttempts,
                step = "Paused while the app is in the background",
            )
            return
        }
        _state.value = BleSessionState.Reconnecting(
            serial = target.serial,
            attempt = 1,
            maxAttempts = ReconnectPolicy.maxAttempts,
            step = "Connection dropped; preparing a same-unit reconnect",
        )
        startReconnectCycle()
    }

    private fun startReconnectCycle() {
        val target = reconnectTarget ?: return
        if (!reconnectAllowed || shuttingDown || reconnectJob?.isActive == true) {
            return
        }
        val job = callbackScope.launch {
            runReconnectCycle(target)
        }
        reconnectJob = job
        job.invokeOnCompletion {
            if (reconnectJob === job) {
                reconnectJob = null
                if (reconnectAllowed &&
                    reconnectTarget != null &&
                    _state.value is BleSessionState.Reconnecting
                ) {
                    startReconnectCycle()
                }
            }
        }
    }

    private suspend fun runReconnectCycle(target: ReconnectTarget) {
        var lastFailure =
            "The last authenticated KARR unit was not available for automatic reconnect."
        for ((index, delayMillis) in ReconnectPolicy.retryDelaysMillis.withIndex()) {
            reconnectAttempt = index + 1
            _state.value = BleSessionState.Reconnecting(
                serial = target.serial,
                attempt = reconnectAttempt,
                maxAttempts = ReconnectPolicy.maxAttempts,
                step = "Retrying in ${formatDelay(delayMillis)}",
            )
            delay(delayMillis)
            if (!reconnectAllowed || reconnectTarget != target) {
                return
            }

            val candidate = runCatching {
                scanForReconnectTarget(target)
            }.getOrElse { failure ->
                lastFailure = failure.message ?: "Bluetooth scanning could not be started."
                null
            }
            if (candidate == null) {
                if (lastFailure.startsWith("The last authenticated")) {
                    lastFailure =
                        "${target.serial} was not seen during the automatic reconnect scan."
                }
                continue
            }
            if (!reconnectAllowed || reconnectTarget != target) {
                return
            }

            val completion = CompletableDeferred<ReconnectAttemptResult>()
            reconnectAttemptResult = completion
            _state.value = BleSessionState.Reconnecting(
                serial = target.serial,
                attempt = reconnectAttempt,
                maxAttempts = ReconnectPolicy.maxAttempts,
                step = "Connecting to the matched unit",
            )
            val connectionStarted = runCatching {
                val activeAdapter = requireNotNull(adapter) { "Bluetooth is not available." }
                check(activeAdapter.isEnabled) { "Bluetooth is turned off." }
                beginDirectConnection(
                    activeAdapter = activeAdapter,
                    address = candidate.address,
                    origin = ConnectionOrigin.RECONNECT,
                    target = target,
                    showConnectingState = false,
                )
            }
            if (connectionStarted.isFailure) {
                lastFailure = connectionStarted.exceptionOrNull()?.message
                    ?: "Android could not start the Bluetooth reconnect attempt."
                if (reconnectAttemptResult === completion) {
                    reconnectAttemptResult = null
                }
                continue
            }

            val result = withTimeoutOrNull(RECONNECT_CONNECTION_TIMEOUT_MS) {
                completion.await()
            }
            if (reconnectAttemptResult === completion) {
                reconnectAttemptResult = null
            }
            when (result) {
                ReconnectAttemptResult.Authenticated -> return
                is ReconnectAttemptResult.Terminal -> {
                    _state.value = BleSessionState.ReconnectFailed(
                        serial = target.serial,
                        attempts = reconnectAttempt,
                        message = result.reason,
                    )
                    return
                }
                is ReconnectAttemptResult.Retryable -> {
                    lastFailure = result.reason
                }
                null -> {
                    lastFailure =
                        "The Bluetooth connection and fresh authentication did not finish " +
                            "within ${RECONNECT_CONNECTION_TIMEOUT_MS / 1_000} seconds."
                    disconnectActiveGatt(lastFailure)
                }
            }
        }

        _state.value = BleSessionState.ReconnectFailed(
            serial = target.serial,
            attempts = ReconnectPolicy.maxAttempts,
            message = lastFailure,
        )
    }

    private suspend fun scanForReconnectTarget(
        target: ReconnectTarget,
    ): DiscoveredDevice? {
        val activeAdapter = requireNotNull(adapter) { "Bluetooth is not available." }
        check(activeAdapter.isEnabled) { "Bluetooth is turned off." }
        val scanner = requireNotNull(activeAdapter.bluetoothLeScanner) {
            "Bluetooth LE scanning is unavailable."
        }
        discovered.clear()
        _devices.value = emptyList()
        val completion = CompletableDeferred<DiscoveredDevice?>()
        reconnectScanFailure = null
        reconnectScanResult = completion
        _state.value = BleSessionState.Reconnecting(
            serial = target.serial,
            attempt = reconnectAttempt,
            maxAttempts = ReconnectPolicy.maxAttempts,
            step = "Scanning only for this authenticated unit",
        )
        try {
            scanner.startScan(scanCallback)
            val result = withTimeoutOrNull(RECONNECT_SCAN_TIMEOUT_MS) {
                completion.await()
            }
            reconnectScanFailure?.let(::error)
            return result
        } finally {
            if (reconnectScanResult === completion) {
                reconnectScanResult = null
            }
            reconnectScanFailure = null
            stopScanner()
        }
    }

    private fun beginDirectConnection(
        activeAdapter: BluetoothAdapter,
        address: String,
        origin: ConnectionOrigin,
        target: ReconnectTarget?,
        showConnectingState: Boolean,
    ) {
        connectionOrigin = origin
        activeConnectionTarget = target
        if (showConnectingState) {
            _state.value = BleSessionState.Connecting(address)
        }
        gatt = activeAdapter.getRemoteDevice(address).connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDeviceTransport.LE,
        )
    }

    private fun completeReconnectAttempt(result: ReconnectAttemptResult) {
        reconnectAttemptResult?.complete(result)
        val target = reconnectTarget ?: return
        _state.value = BleSessionState.Reconnecting(
            serial = target.serial,
            attempt = reconnectAttempt,
            maxAttempts = ReconnectPolicy.maxAttempts,
            step = when (result) {
                ReconnectAttemptResult.Authenticated -> "Authenticated"
                is ReconnectAttemptResult.Retryable -> result.reason
                is ReconnectAttemptResult.Terminal -> result.reason
            },
        )
    }

    private fun cancelReconnect(
        clearTarget: Boolean,
        closeReconnectConnection: Boolean,
    ) {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectScanResult?.cancel()
        reconnectScanResult = null
        reconnectScanFailure = null
        reconnectAttemptResult?.cancel()
        reconnectAttemptResult = null
        stopScanner()
        if (closeReconnectConnection &&
            connectionOrigin == ConnectionOrigin.RECONNECT &&
            _state.value !is BleSessionState.Ready
        ) {
            disconnectActiveGatt("Automatic reconnect was canceled.")
        }
        if (clearTarget) {
            reconnectTarget = null
            reconnectAttempt = 0
        }
    }

    private fun stopScanner() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun formatDelay(delayMillis: Long): String {
        val seconds = delayMillis / 1_000
        return "$seconds ${if (seconds == 1L) "second" else "seconds"}"
    }

    override suspend fun send(packet: ByteArray): Result<Unit> = runCatching {
        writePacket(packet, requireReady = true).getOrThrow()
    }

    private suspend fun writePacket(
        packet: ByteArray,
        requireReady: Boolean,
    ): Result<Unit> = runCatching {
        require(packet.isNotEmpty()) { "Cannot send an empty BLE packet." }
        writeMutex.withLock {
            val activeGatt = requireNotNull(gatt) { "No BLE device is connected." }
            val characteristic = requireNotNull(uartCharacteristic) {
                "KARR UART is not ready."
            }
            if (requireReady) {
                check(_state.value is BleSessionState.Ready) { "BLE session is not ready." }
            }

            val supportsResponse =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val writeType = if (supportsResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            val completion = CompletableDeferred<GattWriteResult>()
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
                check(started) {
                    "Android could not start the UART write because the Bluetooth operation " +
                        "queue was unavailable. Disconnect and scan again."
                }
                if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                    val result = withTimeoutOrNull(WRITE_TIMEOUT_MS) {
                        completion.await()
                    }
                    checkNotNull(result) {
                        "The KARR unit did not acknowledge the UART write within " +
                            "${WRITE_TIMEOUT_MS / 1_000} seconds. Move closer and scan again."
                    }
                    when (result) {
                        is GattWriteResult.Completed -> {
                            check(result.status == BluetoothGatt.GATT_SUCCESS) {
                                GattStatus.operationFailure("UART write", result.status)
                            }
                        }
                        is GattWriteResult.LinkLost -> {
                            error("The UART write was interrupted. ${result.reason}")
                        }
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
        shuttingDown = true
        reconnectAllowed = false
        cancelReconnect(clearTarget = true, closeReconnectConnection = true)
        lastAuthenticatedTarget = null
        stopScanner()
        disconnectActiveGatt("The BLE controller was shut down.")
        _state.value = BleSessionState.Idle
        notificationEvents.close()
        callbackScope.cancel()
    }

    private fun failAndClose(
        message: String,
        callbackGatt: BluetoothGatt,
        terminalReconnectFailure: Boolean = false,
    ) {
        if (gatt !== callbackGatt) {
            closeGatt(callbackGatt)
            return
        }
        val reconnecting = connectionOrigin == ConnectionOrigin.RECONNECT
        authentication.reset()
        pendingWrite?.complete(GattWriteResult.LinkLost(message))
        closeGatt(callbackGatt)
        if (reconnecting) {
            completeReconnectAttempt(
                if (terminalReconnectFailure) {
                    ReconnectAttemptResult.Terminal(message)
                } else {
                    ReconnectAttemptResult.Retryable(message)
                },
            )
        } else {
            _state.value = BleSessionState.Error(message)
        }
    }

    private fun handleNotification(
        callbackGatt: BluetoothGatt,
        value: ByteArray,
    ) {
        val copy = value.copyOf()
        _notifications.tryEmit(copy)
        notificationEvents.trySend(
            NotificationEvent(
                gatt = callbackGatt,
                value = copy,
            ),
        )
    }

    private suspend fun processNotification(event: NotificationEvent) {
        if (gatt !== event.gatt) {
            return
        }
        val parsed = QtProtocolCodec.parse(event.value)
        if (parsed !is PacketParseResult.Success) {
            return
        }
        val address = event.gatt.device.address
        val name = connectedName(event.gatt)
        val action = authentication.onPacket(
            unitSerial = name.orEmpty(),
            packet = parsed.packet,
        )
        when (action) {
            AuthenticationAction.None -> Unit
            is AuthenticationAction.Send -> {
                _state.value = when (action.command) {
                    QtCommand.READ_MODE -> BleSessionState.AuthenticationRequired(
                        address = address,
                        name = name,
                        challengeBytes = parsed.packet.payload.size,
                    )
                    QtCommand.PROVIDE_AUTH -> BleSessionState.Authenticating(
                        address = address,
                        name = name,
                        step = "Sending local challenge response",
                    )
                    else -> BleSessionState.Authenticating(
                        address = address,
                        name = name,
                        step = action.command.name,
                    )
                }
                val packet = QtProtocolCodec.generateOriginal(
                    command = action.command,
                    payload = action.payload,
                )
                try {
                    writePacket(packet, requireReady = false).getOrElse { failure ->
                        authentication.reset()
                        failAndClose(
                            failure.message ?: "KARR authentication write failed.",
                            event.gatt,
                        )
                        return
                    }
                    if (action.command == QtCommand.READ_MODE) {
                        _state.value = BleSessionState.Authenticating(
                            address = address,
                            name = name,
                            step = "Reading unit mode",
                        )
                    }
                } finally {
                    if (action.command == QtCommand.PROVIDE_AUTH) {
                        action.payload.fill(0)
                        packet.fill(0)
                    }
                }
            }
            is AuthenticationAction.Authenticated -> {
                val target = activeConnectionTarget
                    ?: ReconnectPolicy.targetFor(discovered[address]?.device)
                lastAuthenticatedTarget = target
                if (connectionOrigin == ConnectionOrigin.RECONNECT) {
                    reconnectTarget = null
                    reconnectAttemptResult?.complete(ReconnectAttemptResult.Authenticated)
                }
                _state.value = BleSessionState.Ready(
                    address = address,
                    name = name,
                    unitModel = action.unitModel,
                )
            }
            is AuthenticationAction.Failed -> {
                failAndClose(
                    message = action.reason,
                    callbackGatt = event.gatt,
                    terminalReconnectFailure = true,
                )
            }
        }
    }

    private fun connectedName(callbackGatt: BluetoothGatt): String? =
        discovered[callbackGatt.device.address]?.device?.name
            ?: activeConnectionTarget?.serial
            ?: callbackGatt.device.name

    private fun closeGatt(callbackGatt: BluetoothGatt) {
        runCatching { callbackGatt.close() }
        if (gatt === callbackGatt) {
            authentication.reset()
            gatt = null
            uartCharacteristic = null
            activeConnectionTarget = null
        }
    }

    private data class NotificationEvent(
        val gatt: BluetoothGatt,
        val value: ByteArray,
    )

    private data class TimedDiscoveredDevice(
        val device: DiscoveredDevice,
        val lastSeenElapsedRealtimeMillis: Long,
    )

    private sealed interface GattWriteResult {
        data class Completed(val status: Int) : GattWriteResult
        data class LinkLost(val reason: String) : GattWriteResult
    }

    private sealed interface ReconnectAttemptResult {
        data object Authenticated : ReconnectAttemptResult
        data class Retryable(val reason: String) : ReconnectAttemptResult
        data class Terminal(val reason: String) : ReconnectAttemptResult
    }

    private enum class ConnectionOrigin {
        MANUAL,
        RECONNECT,
    }

    private object BluetoothDeviceTransport {
        const val LE = 2
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 10_000L
        const val RECONNECT_SCAN_TIMEOUT_MS = 8_000L
        const val RECONNECT_CONNECTION_TIMEOUT_MS = 30_000L
    }
}
