package com.karr.key.auto

import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.constraints.ConstraintManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.karr.key.ble.BlePermissions
import com.karr.key.ble.BleSessionState
import com.karr.key.ble.DiscoveredDevice
import com.karr.key.core.ResearchAccess
import com.karr.key.core.Session
import com.karr.key.data.KarrCarServices
import com.karr.key.data.LocalKeyRepository
import com.karr.key.data.LocalMapHistoryStore
import com.karr.key.protocol.DeviceKeyPurpose
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

internal class ConnectionScreen(
    carContext: CarContext,
    private val services: KarrCarServices,
    private val executor: CarCommandExecutor,
    private val mapHistoryStore: LocalMapHistoryStore,
) : Screen(carContext) {

    init {
        services.bleController.state.onEach { invalidate() }.launchIn(lifecycleScope)
    }

    override fun onGetTemplate(): Template {
        if (!hasBlePermissions()) {
            return ListTemplate.Builder()
                .setHeader(Header.Builder().setStartHeaderAction(Action.APP_ICON).setTitle("KARR Key").build())
                .setSingleList(
                    ItemList.Builder()
                        .addItem(
                            Row.Builder()
                                .setTitle("Bluetooth permission needed")
                                .addText("Open KARR Key on your phone to grant Bluetooth access.")
                                .build(),
                        )
                        .build(),
                )
                .build()
        }

        val session = ResearchAccess.restrictSession(
            base = LocalKeyRepository().localSession(),
            customerKeyProvisioned = services.deviceKeys.isProvisioned(DeviceKeyPurpose.CUSTOMER),
            dealerKeyProvisioned = services.deviceKeys.isProvisioned(DeviceKeyPurpose.DEALER),
        )

        return when (val state = services.bleController.state.value) {
            is BleSessionState.Ready -> readyTemplate(state, session)
            else -> scanTemplate(state)
        }
    }

    private fun readyTemplate(state: BleSessionState.Ready, session: Session): Template {
        val itemList = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(state.name ?: state.address)
                    .addText(
                        "Connected" +
                            (state.unitModel?.let { model -> " · ${model.displayName}" } ?: ""),
                    )
                    .build(),
            )
            .addItem(
                Row.Builder()
                    .setTitle("Operations")
                    .addText("View available commands")
                    .setOnClickListener {
                        screenManager.push(
                            OperationsScreen(
                                carContext = carContext,
                                executor = executor,
                                session = session,
                                deviceAuthenticated = true,
                                unitModel = state.unitModel,
                                unitMode = state.unitMode,
                            ),
                        )
                    }
                    .build(),
            )
            .addItem(
                Row.Builder()
                    .setTitle("Disconnect")
                    .setOnClickListener { services.bleController.disconnect() }
                    .build(),
            )
            .addItem(mapRow())
            .build()
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setStartHeaderAction(Action.APP_ICON).setTitle("KARR Key").build())
            .setSingleList(itemList)
            .build()
    }

    private fun scanTemplate(state: BleSessionState): Template {
        val itemList = ItemList.Builder()
        when (state) {
            is BleSessionState.Idle -> itemList.addItem(
                Row.Builder()
                    .setTitle("Scan for units")
                    .setOnClickListener { services.bleController.startScan() }
                    .build(),
            )
            is BleSessionState.Scanning -> {
                val devices = services.bleController.devices.value
                    .filter(DiscoveredDevice::manualProbeEligible)
                if (devices.isEmpty()) {
                    itemList.addItem(
                        Row.Builder().setTitle("Scanning for nearby KARR units...").build(),
                    )
                } else {
                    // The host caps list length; show the strongest-signal units up to that cap
                    // rather than overflow it (a scan can surface more devices than the limit).
                    // Reserve one slot for the always-appended map row below.
                    val limit = carContext.contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
                    devices.sortedByDescending { it.rssi }
                        .take((limit - 1).coerceAtLeast(1))
                        .forEach { device ->
                        itemList.addItem(
                            Row.Builder()
                                .setTitle(device.name ?: device.address)
                                .addText("Signal ${device.rssi} dBm")
                                .setOnClickListener {
                                    services.bleController.stopScan()
                                    services.bleController.connect(device.address)
                                }
                                .build(),
                        )
                    }
                }
            }
            is BleSessionState.Connecting -> itemList.addItem(
                Row.Builder().setTitle("Connecting to ${state.address}...").build(),
            )
            is BleSessionState.DiscoveringUart -> itemList.addItem(
                Row.Builder().setTitle("Discovering the unit's Bluetooth service...").build(),
            )
            is BleSessionState.UartSubscribed -> itemList.addItem(
                Row.Builder().setTitle("Waiting for the unit to start authentication...").build(),
            )
            is BleSessionState.AuthenticationRequired -> itemList.addItem(
                Row.Builder().setTitle("Authenticating with the unit...").build(),
            )
            is BleSessionState.Authenticating -> itemList.addItem(
                Row.Builder().setTitle("Authenticating with the unit...").build(),
            )
            is BleSessionState.Reconnecting -> itemList.addItem(
                Row.Builder()
                    .setTitle("Reconnecting (attempt ${state.attempt}/${state.maxAttempts})...")
                    .build(),
            )
            is BleSessionState.ReconnectFailed -> {
                itemList.addItem(Row.Builder().setTitle(state.message).build())
                itemList.addItem(
                    Row.Builder()
                        .setTitle("Retry")
                        .setOnClickListener { services.bleController.retryReconnect() }
                        .build(),
                )
            }
            is BleSessionState.PatchedUnitDetected -> itemList.addItem(
                Row.Builder()
                    .setTitle(state.name ?: state.address)
                    .addText(
                        "Did not respond to the legacy authentication this app uses — " +
                            "likely running patched firmware.",
                    )
                    .build(),
            )
            is BleSessionState.Error -> {
                itemList.addItem(Row.Builder().setTitle(state.message).build())
                itemList.addItem(
                    Row.Builder()
                        .setTitle("Scan for units")
                        .setOnClickListener { services.bleController.startScan() }
                        .build(),
                )
            }
            is BleSessionState.Ready -> Unit
        }
        itemList.addItem(mapRow())
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setStartHeaderAction(Action.APP_ICON).setTitle("KARR Key").build())
            .setSingleList(itemList.build())
            .build()
    }

    private fun mapRow(): Row =
        Row.Builder()
            .setTitle("Nearby units map")
            .addText("See where KARR units were spotted")
            .setBrowsable(true)
            .setOnClickListener {
                screenManager.push(CarNavMapScreen(carContext, services, mapHistoryStore))
            }
            .build()

    private fun hasBlePermissions(): Boolean =
        BlePermissions.scanAndConnect().all { permission ->
            ContextCompat.checkSelfPermission(carContext, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
}
