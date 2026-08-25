package com.karr.key.auto.map

import android.location.Location
import android.os.SystemClock
import androidx.car.app.CarContext

/**
 * The car map's location source, preferring the car's own GPS over the phone's. Both trackers run
 * at once: a car fix wins and, while the car keeps delivering, suppresses the phone; if the car
 * goes quiet — or never serves location on this head unit — the phone fills back in. Bearing comes
 * from whichever fix is in play (GPS course-over-ground, present only while moving), which is what
 * [CarMapRenderer]'s heading-up mode rotates by.
 */
internal class FusedCarLocation(
    carContext: CarContext,
    private val onLocation: (latitude: Double, longitude: Double, bearingDeg: Float?) -> Unit,
) {
    private var lastCarFixElapsedMs = Long.MIN_VALUE

    private val carTracker = CarHardwareLocationTracker(carContext) { location ->
        lastCarFixElapsedMs = SystemClock.elapsedRealtime()
        emit(location)
    }

    private val phoneTracker = CarLocationTracker(carContext) { latitude, longitude, bearing ->
        // Fall through to the phone only while the car isn't actively serving fixes.
        if (!carActive()) onLocation(latitude, longitude, bearing)
    }

    private fun carActive(): Boolean =
        lastCarFixElapsedMs != Long.MIN_VALUE &&
            SystemClock.elapsedRealtime() - lastCarFixElapsedMs < CAR_FIX_STALE_MS

    private fun emit(location: Location) {
        val bearing = if (location.hasBearing()) location.bearing else null
        onLocation(location.latitude, location.longitude, bearing)
    }

    fun start() {
        carTracker.start()
        phoneTracker.start()
    }

    fun stop() {
        carTracker.stop()
        phoneTracker.stop()
    }

    private companion object {
        // If the car hasn't produced a fix in this long, treat it as gone and let the phone drive.
        const val CAR_FIX_STALE_MS = 5_000L
    }
}
