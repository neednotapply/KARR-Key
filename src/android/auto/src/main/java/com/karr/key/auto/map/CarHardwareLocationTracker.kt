package com.karr.key.auto.map

import android.location.Location
import androidx.car.app.CarContext
import androidx.car.app.versioning.CarAppApiLevels
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarHardwareLocation
import androidx.car.app.hardware.info.CarSensors
import androidx.core.content.ContextCompat

/**
 * The car's own GPS, read through the Car App Library's CarHardware API. The head unit's antenna
 * lives outside the metal cabin, so when the car serves location it beats the phone fix a
 * [CarLocationTracker] reads from inside. This is best-effort: the API needs the host to negotiate
 * car-api level 3+ AND a head unit that actually exposes location (many projected cars serve none),
 * so a missing service, a denied `ACCESS_CAR_HARDWARE`, or a non-success [CarValue] all just leave
 * this quiet — [FusedCarLocation] keeps the phone tracker as the fallback.
 */
internal class CarHardwareLocationTracker(
    private val carContext: CarContext,
    private val onLocation: (Location) -> Unit,
) {
    private var sensors: CarSensors? = null

    private val listener = OnCarDataAvailableListener<CarHardwareLocation> { data ->
        val value = data.location
        // Ignore UNAVAILABLE / UNIMPLEMENTED / UNKNOWN — those mean "this car has no fix for us",
        // which must not count as a car fix or it would starve the phone fallback.
        if (value.status == CarValue.STATUS_SUCCESS) {
            value.value?.let(onLocation)
        }
    }

    fun start() {
        // CarHardware landed at car-api level 3; older hosts don't offer the service at all, and
        // asking for it throws rather than degrading.
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) return
        runCatching {
            val carSensors = carContext.getCarService(CarHardwareManager::class.java).carSensors
            carSensors.addCarHardwareLocationListener(
                // UI rate, not NORMAL — heading-up needs fixes fast enough to track a turn.
                CarSensors.UPDATE_RATE_UI,
                ContextCompat.getMainExecutor(carContext),
                listener,
            )
            sensors = carSensors
        }
    }

    fun stop() {
        runCatching { sensors?.removeCarHardwareLocationListener(listener) }
        sensors = null
    }
}
