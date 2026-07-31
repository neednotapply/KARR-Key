package com.karr.key

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.karr.key.ble.BleController
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.DiscoveredDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class BluetoothSessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private val controller: BleController
        get() = (application as KarrApplication).bleController
    private val contentIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val alertedAddresses = mutableSetOf<String>()
    private var collectingJob: Job? = null
    private var delayedStopJob: Job? = null
    private var scanning = false
    private var lastStatusContent: BleNotificationContent? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        collectingJob = serviceScope.launch {
            combine(controller.state, controller.devices) { state, devices ->
                SessionSnapshot(state, devices)
            }.collect(::render)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        controller.setReconnectAllowed(true)
        val content = bleNotificationContent(
            state = controller.state.value,
            devices = controller.devices.value,
        ) ?: STARTING_CONTENT
        if (!promote(content)) {
            controller.disconnect()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        lastStatusContent = content

        when (intent?.action) {
            ACTION_STOP_SCAN -> controller.stopScan()
            ACTION_DISCONNECT -> controller.disconnect()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        delayedStopJob?.cancel()
        collectingJob?.cancel()
        serviceScope.cancel()
        if (
            bleNotificationContent(
                controller.state.value,
                controller.devices.value,
            ) != null
        ) {
            // Never leave scanning or a GATT session running without its
            // user-visible foreground-service notification.
            controller.disconnect()
        }
        super.onDestroy()
    }

    private fun render(snapshot: SessionSnapshot) {
        val content = bleNotificationContent(snapshot.state, snapshot.devices)
        if (content == null) {
            scheduleStopIfInactive()
            return
        }

        delayedStopJob?.cancel()
        delayedStopJob = null
        if (content != lastStatusContent) {
            if (!promote(content)) {
                controller.disconnect()
                stopSelf()
                return
            }
            lastStatusContent = content
        }

        if (snapshot.state == BleSessionState.Scanning) {
            if (!scanning) {
                scanning = true
                alertedAddresses.clear()
                notificationManager.cancel(DISCOVERY_NOTIFICATION_ID)
            }
            alertForNewCompatibleDevices(snapshot.devices)
        } else {
            scanning = false
            alertedAddresses.clear()
        }
    }

    private fun scheduleStopIfInactive() {
        if (delayedStopJob?.isActive == true) {
            return
        }
        delayedStopJob = serviceScope.launch {
            delay(INACTIVE_STOP_GRACE_MILLIS)
            if (
                bleNotificationContent(
                    controller.state.value,
                    controller.devices.value,
                ) == null
            ) {
                ServiceCompat.stopForeground(
                    this@BluetoothSessionService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE,
                )
                stopSelf()
            }
        }
    }

    private fun promote(content: BleNotificationContent): Boolean = runCatching {
        ServiceCompat.startForeground(
            this,
            STATUS_NOTIFICATION_ID,
            statusNotification(content),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }.isSuccess

    private fun statusNotification(content: BleNotificationContent): Notification {
        val action = when (content.action) {
            BleNotificationAction.STOP_SCAN -> serviceAction(
                requestCode = 1,
                action = ACTION_STOP_SCAN,
            )
            BleNotificationAction.DISCONNECT -> serviceAction(
                requestCode = 2,
                action = ACTION_DISCONNECT,
            )
        }
        val actionLabel = when (content.action) {
            BleNotificationAction.STOP_SCAN -> "Stop scan"
            BleNotificationAction.DISCONNECT -> "Disconnect"
        }

        return NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_bluetooth)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.expandedText))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(0, 0, content.showProgress)
            .addAction(R.drawable.ic_nav_bluetooth, actionLabel, action)
            .setPublicVersion(publicStatusNotification())
            .build()
    }

    private fun publicStatusNotification(): Notification =
        NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_bluetooth)
            .setContentTitle("KARR Key Bluetooth is active")
            .setContentText("Open KARR Key for device details.")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun alertForNewCompatibleDevices(devices: List<DiscoveredDevice>) {
        val newlyFound = newlyDiscoveredCompatibleDevices(devices, alertedAddresses)
        if (newlyFound.isEmpty()) {
            return
        }
        alertedAddresses += newlyFound.map(DiscoveredDevice::address)
        val compatibleCount = devices.count(DiscoveredDevice::connectionEligible)
        postDiscoveryAlert(
            deviceDiscoveryContent(
                device = newlyFound.maxBy(DiscoveredDevice::rssi),
                compatibleCount = compatibleCount,
            ),
        )
    }

    private fun postDiscoveryAlert(content: DeviceDiscoveryContent) {
        if (!canPostNotifications()) {
            return
        }
        val notification = NotificationCompat.Builder(this, DISCOVERY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_bluetooth)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.expandedText))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(DISCOVERY_ALERT_TIMEOUT_MILLIS)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setPublicVersion(publicDiscoveryNotification())
            .build()
        try {
            notificationManager.notify(DISCOVERY_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between canPostNotifications() and notify().
        }
    }

    private fun publicDiscoveryNotification(): Notification =
        NotificationCompat.Builder(this, DISCOVERY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_bluetooth)
            .setContentTitle("Compatible KARR device found")
            .setContentText("Open KARR Key to view it.")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    private fun canPostNotifications(): Boolean {
        val runtimePermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        return runtimePermissionGranted && notificationManager.areNotificationsEnabled()
    }

    private fun serviceAction(
        requestCode: Int,
        action: String,
    ): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, BluetoothSessionService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    STATUS_CHANNEL_ID,
                    "Bluetooth status",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Ongoing KARR scanning and connected-device status"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(
                    DISCOVERY_CHANNEL_ID,
                    "Device discoveries",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Alerts when a compatible KARR device is found"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            ),
        )
    }

    private data class SessionSnapshot(
        val state: BleSessionState,
        val devices: List<DiscoveredDevice>,
    )

    companion object {
        private const val ACTION_KEEP_ALIVE = "com.karr.key.action.KEEP_BLUETOOTH_ACTIVE"
        private const val ACTION_STOP_SCAN = "com.karr.key.action.STOP_SCAN"
        private const val ACTION_DISCONNECT = "com.karr.key.action.DISCONNECT"
        private const val STATUS_CHANNEL_ID = "bluetooth_status"
        private const val DISCOVERY_CHANNEL_ID = "device_discoveries"
        private const val STATUS_NOTIFICATION_ID = 1001
        private const val DISCOVERY_NOTIFICATION_ID = 1002
        private const val DISCOVERY_ALERT_TIMEOUT_MILLIS = 60_000L
        private const val INACTIVE_STOP_GRACE_MILLIS = 250L

        private val STARTING_CONTENT = BleNotificationContent(
            title = "Starting KARR Bluetooth",
            text = "Preparing the Bluetooth session",
            expandedText = "KARR Key is preparing the requested scan or connection.",
            action = BleNotificationAction.DISCONNECT,
            showProgress = true,
        )

        fun keepAlive(context: Context): Result<Unit> = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BluetoothSessionService::class.java)
                    .setAction(ACTION_KEEP_ALIVE),
            )
            Unit
        }
    }
}
