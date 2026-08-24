package com.karr.key.auto

import com.karr.key.data.KarrCarServices
import com.karr.key.data.LocalMapObservation
import java.time.Instant

/**
 * One KARR unit as shown on the car map: its most recent recorded sighting location plus the
 * metadata the map and detail screens display. Derived from [LocalMapObservation] history, the same
 * source the phone Map plots.
 */
internal data class MappedUnit(
    val address: String,
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val rssi: Int,
    val observedUtc: Instant,
    val rangeMeters: Double?,
)

/** Collapses observation history to one [MappedUnit] per device, keyed on its latest sighting. */
internal fun latestPerDevice(observations: List<LocalMapObservation>): List<MappedUnit> =
    observations
        .groupBy { observation -> observation.deviceAddress.uppercase() }
        .map { (address, group) ->
            val latest = group.maxByOrNull(LocalMapObservation::observedUtc) ?: group.first()
            MappedUnit(
                address = address,
                name = latest.deviceName,
                latitude = latest.phoneLatitude,
                longitude = latest.phoneLongitude,
                rssi = latest.rssi,
                observedUtc = latest.observedUtc,
                rangeMeters = latest.confidenceRadiusMeters.takeIf { it > 0 },
            )
        }
        .sortedByDescending { unit -> unit.observedUtc }

/** Whether a unit at [address] is present in the live scan and eligible for a manual connect. */
internal fun inRange(services: KarrCarServices, address: String): Boolean =
    services.bleController.devices.value.any { device ->
        device.address.equals(address, ignoreCase = true) && device.manualProbeEligible
    }

internal fun relativeTime(instant: Instant): String {
    val seconds = (Instant.now().epochSecond - instant.epochSecond).coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}
