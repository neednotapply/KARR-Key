package com.karr.key.desktop.map

import com.karr.key.data.DeviceProfile
import com.karr.key.data.DeviceReadRecord
import com.karr.key.data.LocalMapObservation
import com.karr.key.data.MapCandidateTier
import com.karr.key.data.VEHICLE_GPS_PROVIDER
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.KarrVulnerabilityStatus
import java.time.Instant
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

// Ported from the Android MapScreen.kt estimation logic (RSSI range, recency-weighted position,
// confidence radius). Kept byte-faithful to the algorithm and covered by the same golden vectors
// (MapEstimationTest / MapEvidenceLineTest, copied to the desktop test source set). A future
// cleanup can unify this with the Android copy behind a shared:geo module; today it is duplicated
// so the desktop map ships without refactoring the 1,900-line Android MapScreen.

/** Platform-free geographic point (the Android side uses osmdroid's GeoPoint for rendering). */
data class GeoPoint(val latitude: Double, val longitude: Double)

data class DeviceMapEstimate(
    val address: String,
    val label: String,
    val tier: MapCandidateTier,
    val evidence: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val rssi: Int,
    val lastSeenUtc: Instant,
    val reportedPosition: GeoPoint? = null,
)

fun estimatedBleRangeMeters(
    rssi: Int,
    txPowerAtOneMeter: Int = RSSI_ONE_METER_DBM,
    pathLossExponent: Double = PATH_LOSS_EXPONENT,
): Double {
    if (rssi >= 0) return MAX_RANGE_METERS
    val estimate = 10.0.pow((txPowerAtOneMeter - rssi) / (10.0 * pathLossExponent))
    return estimate.coerceIn(MIN_RANGE_METERS, MAX_RANGE_METERS)
}

fun confidenceRadiusMeters(
    rangeMeters: Double,
    phoneAccuracyMeters: Float,
): Double = (rangeMeters + phoneAccuracyMeters).coerceIn(
    MIN_CONFIDENCE_RADIUS_METERS,
    MAX_CONFIDENCE_RADIUS_METERS,
)

fun estimateDeviceLocations(
    observations: List<LocalMapObservation>,
): List<DeviceMapEstimate> = observations
    .groupBy { it.deviceAddress.uppercase() }
    .map { (normalizedAddress, group) ->
        val sorted = group
            .filterNot { it.locationProvider == VEHICLE_GPS_PROVIDER }
            .ifEmpty { group }
            .sortedByDescending(LocalMapObservation::observedUtc)
        val latestUtcMillis = sorted.first().observedUtc.toEpochMilli()
        val weighted = sorted.fold(WeightedLocation()) { accumulator, observation ->
            val confidence = max(observation.confidenceRadiusMeters, 1.0)
            val signalWeight = (observation.rssi + 100).coerceIn(1, 80).toDouble()
            val ageSeconds = (latestUtcMillis - observation.observedUtc.toEpochMilli()) / 1000.0
            val recencyWeight = exp(-ageSeconds / RECENCY_DECAY_SECONDS)
            val weight = signalWeight * recencyWeight / (confidence * confidence)
            accumulator.add(observation, weight)
        }
        val latest = sorted.first()
        var radiusWeightSum = 0.0
        var radiusValueSum = 0.0
        sorted.forEach { observation ->
            val ageSeconds = (latestUtcMillis - observation.observedUtc.toEpochMilli()) / 1000.0
            val recencyWeight = exp(-ageSeconds / RECENCY_DECAY_SECONDS)
            radiusWeightSum += recencyWeight
            radiusValueSum += observation.confidenceRadiusMeters * recencyWeight
        }
        val currentRadiusMeters = if (radiusWeightSum > 0.0) {
            radiusValueSum / radiusWeightSum
        } else {
            latest.confidenceRadiusMeters
        }
        val reportedPosition = group
            .filter { it.locationProvider == VEHICLE_GPS_PROVIDER }
            .maxByOrNull(LocalMapObservation::observedUtc)
            ?.let { GeoPoint(it.phoneLatitude, it.phoneLongitude) }
        DeviceMapEstimate(
            address = normalizedAddress,
            label = latest.deviceName ?: "Unnamed KARR candidate",
            tier = latest.classificationTier,
            evidence = latest.classificationEvidence,
            latitude = weighted.latitude,
            longitude = weighted.longitude,
            radiusMeters = currentRadiusMeters.coerceAtLeast(MIN_CONFIDENCE_RADIUS_METERS),
            rssi = latest.rssi,
            lastSeenUtc = latest.observedUtc,
            reportedPosition = reportedPosition,
        )
    }
    .sortedByDescending(DeviceMapEstimate::lastSeenUtc)

private data class WeightedLocation(
    val latitudeSum: Double = 0.0,
    val longitudeSum: Double = 0.0,
    val weightSum: Double = 0.0,
) {
    val latitude: Double get() = latitudeSum / weightSum
    val longitude: Double get() = longitudeSum / weightSum

    fun add(observation: LocalMapObservation, weight: Double): WeightedLocation =
        copy(
            latitudeSum = latitudeSum + observation.phoneLatitude * weight,
            longitudeSum = longitudeSum + observation.phoneLongitude * weight,
            weightSum = weightSum + weight,
        )
}

fun reportedPositionFromReads(reads: List<DeviceReadRecord>): GeoPoint? = reads
    .sortedByDescending(DeviceReadRecord::capturedUtc)
    .asSequence()
    .flatMap { record -> record.lines.asSequence() }
    .mapNotNull(::reportedPositionFromLine)
    .firstOrNull()

private fun reportedPositionFromLine(line: String): GeoPoint? {
    val match = REPORTED_POSITION_REGEX.matchEntire(line.trim()) ?: return null
    val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
    val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return GeoPoint(latitude, longitude)
}

fun KarrVulnerabilityStatus.statusGlyph(): String? = when (this) {
    KarrVulnerabilityStatus.VULNERABLE -> "⚠️"
    KarrVulnerabilityStatus.PATCHED -> "🛡️"
    KarrVulnerabilityStatus.UNKNOWN -> null
}

fun deviceEvidenceLine(
    scanEvidence: String,
    deviceName: String?,
    profile: DeviceProfile?,
): String {
    val baseEvidence = familySpecificScanEvidence(scanEvidence, deviceName)
    if (profile == null) return baseEvidence
    val modelName = profile.unitModelCode
        ?.let(KarrUnitModel::fromCode)
        ?.takeIf(KarrUnitModel::isRecognized)
        ?.displayName
    return when (profile.vulnerability) {
        KarrVulnerabilityStatus.VULNERABLE ->
            if (modelName != null) "Vulnerable · $modelName" else "Vulnerable KARR unit"
        KarrVulnerabilityStatus.PATCHED ->
            if (modelName != null) "Patched · $modelName" else "Patched KARR unit"
        KarrVulnerabilityStatus.UNKNOWN -> baseEvidence
    }
}

private fun familySpecificScanEvidence(scanEvidence: String, deviceName: String?): String {
    if (scanEvidence != LEGACY_DR_QT_SERIAL_EVIDENCE) return scanEvidence
    val family = deviceName?.trim()?.uppercase()?.take(2)
    return if (family == "DR" || family == "QT") "Recovered $family serial format" else scanEvidence
}

private const val LEGACY_DR_QT_SERIAL_EVIDENCE = "Recovered DR/QT serial format"
private const val RSSI_ONE_METER_DBM = -59
private const val PATH_LOSS_EXPONENT = 2.4
private const val MIN_RANGE_METERS = 1.0
private const val MAX_RANGE_METERS = 120.0
private const val MIN_CONFIDENCE_RADIUS_METERS = 5.0
private const val MAX_CONFIDENCE_RADIUS_METERS = 250.0
private const val RECENCY_DECAY_SECONDS = 10.0
private val REPORTED_POSITION_REGEX = Regex(
    "^Position:\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*,\\s*" +
        "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))$",
)
