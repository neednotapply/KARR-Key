package com.karr.key.auto.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Minimal current-location source for the car map. The phone's own `FrameworkLocationTracker` lives
 * in `android:app` (which this module can't depend on), so this is a small framework-LocationManager
 * equivalent: seed with the best last-known fix, then stream GPS/network updates. A no-op when the
 * location permission isn't granted — the map still renders, just without the car's position.
 */
internal class CarLocationTracker(
    context: Context,
    private val onLocation: (latitude: Double, longitude: Double, bearingDeg: Float?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val listener = LocationListener { location -> report(location) }

    private fun report(location: Location) {
        // A GPS-derived bearing only exists while moving; null when parked, so heading-up keeps its
        // last heading rather than snapping to north.
        val bearing = if (location.hasBearing()) location.bearing else null
        onLocation(location.latitude, location.longitude, bearing)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission() || locationManager == null) return
        bestLastKnown()?.let(::report)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, MIN_INTERVAL_MS, MIN_DISTANCE_M, listener)
                }
            }
        }
    }

    fun stop() {
        runCatching { locationManager?.removeUpdates(listener) }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(): Location? {
        val manager = locationManager ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val MIN_INTERVAL_MS = 2_000L
        const val MIN_DISTANCE_M = 5f
    }
}
