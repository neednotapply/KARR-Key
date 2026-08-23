package com.karr.key.data

import java.time.Instant

enum class MapCandidateTier {
    CONFIRMED_FORMAT,
    POSSIBLE,
}

/** locationProvider marker for observations carrying a unit's own reported GPS (extended
 * READ_STATE). These fixes are stale last-known positions on hardware, so they are NOT used as the
 * unit's plotted position (that stays RSSI-based); instead the map surfaces them as a secondary
 * "recently reported" annotation — a marker and connecting line to the RSSI pin. */
const val VEHICLE_GPS_PROVIDER = "vehicle-gps"

data class LocalMapObservation(
    val id: String,
    val observedUtc: Instant,
    val deviceAddress: String,
    val deviceName: String?,
    val classificationTier: MapCandidateTier,
    val classificationEvidence: String,
    val rssi: Int,
    val phoneLatitude: Double,
    val phoneLongitude: Double,
    val phoneAccuracyMeters: Float,
    val locationProvider: String,
    val estimatedRangeMeters: Double,
    val confidenceRadiusMeters: Double,
)

data class MapHistoryImportSummary(val inserted: Int, val updated: Int) {
    val total: Int get() = inserted + updated
}
