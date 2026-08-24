package com.karr.key.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.karr.key.ble.BleSessionState
import com.karr.key.data.KarrCarServices
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Detail for one mapped KARR unit, reached from [CarMapScreen]. Offers a Connect action only when
 * the unit is currently in Bluetooth range; a unit only known from a past sighting shows why it
 * can't be connected. Connecting returns to the root connection screen so its progress is visible.
 */
internal class CarUnitDetailScreen(
    carContext: CarContext,
    private val services: KarrCarServices,
    private val unit: MappedUnit,
) : Screen(carContext) {

    init {
        // The Connect action's availability depends on the live scan; refresh when it changes.
        services.bleController.devices.onEach { invalidate() }.launchIn(lifecycleScope)
    }

    override fun onGetTemplate(): Template {
        val reachable = inRange(services, unit.address)
        val message = buildString {
            append("Last seen ${relativeTime(unit.observedUtc)} · ${unit.rssi} dBm\n")
            append(unit.address)
            append("\n\n")
            append(
                if (reachable) {
                    "This unit is in range now. Connect to authenticate and open its operations."
                } else {
                    "This unit isn't in range right now — only its last recorded location is " +
                        "shown. Move closer and scan, then connect from the home screen."
                },
            )
        }

        val builder = MessageTemplate.Builder(message)
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle(unit.name ?: unit.address)
                    .build(),
            )
        if (reachable) {
            builder.addAction(
                Action.Builder()
                    .setTitle("Connect")
                    .setOnClickListener { connect() }
                    .build(),
            )
        }
        return builder.build()
    }

    private fun connect() {
        when (services.bleController.state.value) {
            is BleSessionState.Idle,
            is BleSessionState.Scanning,
            is BleSessionState.Error,
            is BleSessionState.ReconnectFailed,
            is BleSessionState.PatchedUnitDetected,
            -> {
                services.bleController.stopScan()
                services.bleController.connect(unit.address)
                // Land on the connection screen so connect/auth progress is visible.
                screenManager.popToRoot()
            }
            else ->
                CarToast.makeText(
                    carContext,
                    "A unit is already connecting or connected.",
                    CarToast.LENGTH_LONG,
                ).show()
        }
    }
}
