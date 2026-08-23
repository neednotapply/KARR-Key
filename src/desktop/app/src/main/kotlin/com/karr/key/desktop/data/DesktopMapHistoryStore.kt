package com.karr.key.desktop.data

import com.karr.key.data.DEFAULT_DEDUP_METERS
import com.karr.key.data.LocalMapObservation
import com.karr.key.data.MapCandidateTier
import com.karr.key.data.MapHistoryImportSummary
import com.karr.key.data.StringCipher
import com.karr.key.data.VEHICLE_GPS_PROVIDER
import com.karr.key.data.mapObservationIdsToPrune
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Desktop equivalent of the Android LocalMapHistoryStore: recorded sightings for
 * the map, with the same add / upsert / recent / dedup surface. Encrypted at rest.
 */
class DesktopMapHistoryStore(
    cipher: StringCipher = FileStringCipher(),
    file: Path = AppPaths.file("map_history.json"),
) {
    private val store = EncryptedTextFile(file, cipher)
    private val lock = Any()

    @Suppress("LongParameterList")
    fun add(
        observedUtc: Instant,
        deviceAddress: String,
        deviceName: String?,
        classificationTier: MapCandidateTier,
        classificationEvidence: String,
        rssi: Int,
        phoneLatitude: Double,
        phoneLongitude: Double,
        phoneAccuracyMeters: Float,
        locationProvider: String,
        estimatedRangeMeters: Double,
        confidenceRadiusMeters: Double,
    ): LocalMapObservation = add(
        LocalMapObservation(
            id = UUID.randomUUID().toString(),
            observedUtc = observedUtc,
            deviceAddress = deviceAddress.uppercase(),
            deviceName = deviceName,
            classificationTier = classificationTier,
            classificationEvidence = classificationEvidence,
            rssi = rssi,
            phoneLatitude = phoneLatitude,
            phoneLongitude = phoneLongitude,
            phoneAccuracyMeters = phoneAccuracyMeters,
            locationProvider = locationProvider,
            estimatedRangeMeters = estimatedRangeMeters,
            confidenceRadiusMeters = confidenceRadiusMeters,
        ),
    )

    fun add(observation: LocalMapObservation): LocalMapObservation = synchronized(lock) {
        val all = readAll().toMutableList()
        all.add(observation)
        writeAll(all)
        observation
    }

    fun addVehicleReportedPosition(
        deviceAddress: String,
        deviceName: String?,
        latitude: Double,
        longitude: Double,
    ): LocalMapObservation = add(
        observedUtc = Instant.now(),
        deviceAddress = deviceAddress,
        deviceName = deviceName,
        classificationTier = MapCandidateTier.CONFIRMED_FORMAT,
        classificationEvidence = "Unit-reported GPS position",
        rssi = 0,
        phoneLatitude = latitude,
        phoneLongitude = longitude,
        phoneAccuracyMeters = 1f,
        locationProvider = VEHICLE_GPS_PROVIDER,
        estimatedRangeMeters = 0.0,
        confidenceRadiusMeters = 2.0,
    )

    fun upsertAll(observations: List<LocalMapObservation>): MapHistoryImportSummary =
        synchronized(lock) {
            val all = readAll().toMutableList()
            val indexByKey = all.withIndex()
                .associateTo(mutableMapOf()) { (i, o) -> (o.deviceAddress to o.observedUtc) to i }
            var inserted = 0
            var updated = 0
            observations.forEach { raw ->
                val incoming = raw.copy(deviceAddress = raw.deviceAddress.uppercase())
                val key = incoming.deviceAddress to incoming.observedUtc
                val existingIndex = indexByKey[key]
                if (existingIndex != null) {
                    all[existingIndex] = incoming.copy(id = all[existingIndex].id)
                    updated++
                } else {
                    all.add(incoming)
                    indexByKey[key] = all.lastIndex
                    inserted++
                }
            }
            writeAll(all)
            MapHistoryImportSummary(inserted = inserted, updated = updated)
        }

    fun recent(limit: Int = 1_000): List<LocalMapObservation> = synchronized(lock) {
        readAll().sortedByDescending { it.observedUtc }.take(limit)
    }

    fun count(): Int = synchronized(lock) { readAll().size }

    fun clear() = synchronized(lock) { store.save(JSONArray().toString()) }

    fun deduplicate(minMeters: Double = DEFAULT_DEDUP_METERS): Int = synchronized(lock) {
        val all = readAll()
        val remove = mapObservationIdsToPrune(all, minMeters).toSet()
        if (remove.isEmpty()) return 0
        writeAll(all.filterNot { it.id in remove })
        remove.size
    }

    private fun readAll(): List<LocalMapObservation> {
        val text = store.load() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { array.getJSONObject(index).toObservation() }.getOrNull()
        }
    }

    private fun writeAll(observations: List<LocalMapObservation>) {
        val array = JSONArray()
        observations.forEach { array.put(it.toJson()) }
        store.save(array.toString())
    }

    private fun LocalMapObservation.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("observedUtc", observedUtc.toString())
        .put("deviceAddress", deviceAddress)
        .put("deviceName", deviceName ?: JSONObject.NULL)
        .put("classificationTier", classificationTier.name)
        .put("classificationEvidence", classificationEvidence)
        .put("rssi", rssi)
        .put("phoneLatitude", phoneLatitude)
        .put("phoneLongitude", phoneLongitude)
        .put("phoneAccuracyMeters", phoneAccuracyMeters.toDouble())
        .put("locationProvider", locationProvider)
        .put("estimatedRangeMeters", estimatedRangeMeters)
        .put("confidenceRadiusMeters", confidenceRadiusMeters)

    private fun JSONObject.toObservation(): LocalMapObservation = LocalMapObservation(
        id = getString("id"),
        observedUtc = Instant.parse(getString("observedUtc")),
        deviceAddress = getString("deviceAddress"),
        deviceName = if (isNull("deviceName")) null else getString("deviceName"),
        classificationTier = MapCandidateTier.valueOf(getString("classificationTier")),
        classificationEvidence = getString("classificationEvidence"),
        rssi = getInt("rssi"),
        phoneLatitude = getDouble("phoneLatitude"),
        phoneLongitude = getDouble("phoneLongitude"),
        phoneAccuracyMeters = getDouble("phoneAccuracyMeters").toFloat(),
        locationProvider = getString("locationProvider"),
        estimatedRangeMeters = getDouble("estimatedRangeMeters"),
        confidenceRadiusMeters = getDouble("confidenceRadiusMeters"),
    )
}
