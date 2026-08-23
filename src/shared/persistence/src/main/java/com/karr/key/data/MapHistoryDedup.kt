package com.karr.key.data

import kotlin.math.cos
import kotlin.math.sqrt

/** Default spatial threshold for [mapObservationIdsToPrune], mirroring the live recorder's
 * per-device minimum move distance so compaction and recording agree on what "the same spot" is. */
const val DEFAULT_DEDUP_METERS = 8.0

/**
 * Ids of the observations to remove when compacting map history. Within each (device, provider)
 * group it keeps the most recent observation in every [minMeters] neighbourhood and marks the rest
 * — the repeat sightings a stationary unit produces when it is re-scanned from the same spot across
 * sessions — for deletion. Distinct vantage points (farther than [minMeters] apart) are all kept, so
 * a device's location estimate still has spread to work with; only true same-spot repeats collapse.
 *
 * Pure and side-effect free so it can be unit-tested without a database; [LocalMapHistoryStore]
 * decrypts its rows, calls this, and deletes the returned ids.
 */
fun mapObservationIdsToPrune(
    observations: List<LocalMapObservation>,
    minMeters: Double = DEFAULT_DEDUP_METERS,
): List<String> {
    val remove = mutableListOf<String>()
    observations
        .groupBy { it.deviceAddress.uppercase() to it.locationProvider }
        .forEach { (_, group) ->
            val kept = mutableListOf<LocalMapObservation>()
            group.sortedByDescending { it.observedUtc }.forEach { candidate ->
                val nearKept = kept.any { rep ->
                    approxDistanceMeters(
                        rep.phoneLatitude,
                        rep.phoneLongitude,
                        candidate.phoneLatitude,
                        candidate.phoneLongitude,
                    ) < minMeters
                }
                if (nearKept) remove.add(candidate.id) else kept.add(candidate)
            }
        }
    return remove
}

/** Equirectangular-approximation distance in metres; accurate well under the sub-kilometre scales
 * this thinning works at. Mirrors the Map screen's own distance helper, duplicated here because the
 * data module cannot depend on the UI module. */
private fun approxDistanceMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val meanLatRad = Math.toRadians((lat1 + lat2) / 2)
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1) * cos(meanLatRad)
    return EARTH_RADIUS_METERS * sqrt(dLat * dLat + dLon * dLon)
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
