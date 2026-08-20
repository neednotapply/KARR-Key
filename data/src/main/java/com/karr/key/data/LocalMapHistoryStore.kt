package com.karr.key.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.util.UUID

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

class LocalMapHistoryStore(
    context: Context,
    private val cipher: StringCipher = AndroidKeystoreCipher("karr_key_map_history_v1"),
) : SQLiteOpenHelper(context, "karr_key_map_history.db", null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE map_observations (
                id TEXT PRIMARY KEY NOT NULL,
                observed_utc TEXT NOT NULL,
                encrypted_device_address TEXT NOT NULL,
                encrypted_device_name TEXT NULL,
                classification_tier TEXT NOT NULL,
                encrypted_classification_evidence TEXT NOT NULL,
                rssi INTEGER NOT NULL,
                encrypted_phone_latitude TEXT NOT NULL,
                encrypted_phone_longitude TEXT NOT NULL,
                phone_accuracy_meters REAL NOT NULL,
                encrypted_location_provider TEXT NOT NULL,
                estimated_range_meters REAL NOT NULL,
                confidence_radius_meters REAL NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX map_observations_seen_idx ON map_observations(observed_utc DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == newVersion) {
            "No destructive map-history migration is defined."
        }
    }

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
    ): LocalMapObservation {
        val observation = LocalMapObservation(
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
        )
        writableDatabase.insertOrThrow("map_observations", null, observation.toValues())
        return observation
    }

    /** Persists an already-built observation (converted upstream), so a single app-level recorder
     * can own map persistence while the Map screen only builds observations for live display. */
    fun add(observation: LocalMapObservation): LocalMapObservation {
        writableDatabase.insertOrThrow("map_observations", null, observation.toValues())
        return observation
    }

    /** Records a unit's own reported GPS (extended READ_STATE) as a [VEHICLE_GPS_PROVIDER]-tagged
     * observation. The map keeps the unit's plotted position RSSI-based (the fix is a stale
     * last-known location) and uses this only as the secondary "recently reported" annotation. */
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

    /**
     * Bulk-imports previously-decoded observations (e.g. from [MapHistoryCsv]) in one
     * transaction. Device address is encrypted with a randomized IV, so it cannot be matched
     * via SQL; existing rows are decrypted first and matched in memory by
     * (deviceAddress, observedUtc) — the same device re-observed at the exact same moment,
     * which is what a re-imported export or an overlapping WiGLE file produces. A match updates
     * that row in place instead of inserting a duplicate; anything new is inserted.
     */
    fun upsertAll(observations: List<LocalMapObservation>): MapHistoryImportSummary {
        val existingIdByKey = recent(limit = MAX_LOOKUP_ROWS)
            .associateTo(mutableMapOf()) { (it.deviceAddress to it.observedUtc) to it.id }
        val db = writableDatabase
        var inserted = 0
        var updated = 0
        db.beginTransaction()
        try {
            observations.forEach { raw ->
                // Normalize casing so a lowercase WiGLE-import MAC matches an existing
                // uppercase live-scan record for the same device instead of duplicating it.
                val incoming = raw.copy(deviceAddress = raw.deviceAddress.uppercase())
                val key = incoming.deviceAddress to incoming.observedUtc
                val existingId = existingIdByKey[key]
                if (existingId != null) {
                    db.update(
                        "map_observations",
                        incoming.copy(id = existingId).toValues(),
                        "id = ?",
                        arrayOf(existingId),
                    )
                    updated++
                } else {
                    val newId = UUID.randomUUID().toString()
                    db.insertOrThrow(
                        "map_observations",
                        null,
                        incoming.copy(id = newId).toValues(),
                    )
                    existingIdByKey[key] = newId
                    inserted++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return MapHistoryImportSummary(inserted = inserted, updated = updated)
    }

    fun recent(limit: Int = 1_000): List<LocalMapObservation> {
        require(limit in 1..5_000)
        return readableDatabase.query(
            "map_observations",
            COLUMNS,
            null,
            null,
            null,
            null,
            "observed_utc DESC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LocalMapObservation(
                            id = cursor.getString(0),
                            observedUtc = Instant.parse(cursor.getString(1)),
                            deviceAddress = cipher.decrypt(cursor.getString(2)),
                            deviceName = cursor.getString(3)?.let(cipher::decrypt),
                            classificationTier = MapCandidateTier.valueOf(cursor.getString(4)),
                            classificationEvidence = cipher.decrypt(cursor.getString(5)),
                            rssi = cursor.getInt(6),
                            phoneLatitude = cipher.decrypt(cursor.getString(7)).toDouble(),
                            phoneLongitude = cipher.decrypt(cursor.getString(8)).toDouble(),
                            phoneAccuracyMeters = cursor.getFloat(9),
                            locationProvider = cipher.decrypt(cursor.getString(10)),
                            estimatedRangeMeters = cursor.getDouble(11),
                            confidenceRadiusMeters = cursor.getDouble(12),
                        ),
                    )
                }
            }
        }
    }

    fun count(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM map_observations",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    fun clear() {
        writableDatabase.delete("map_observations", null, null)
    }

    /**
     * Compacts stored history by removing repeat sightings, returning the number of rows removed.
     * Rows are encrypted with a randomized IV so they cannot be grouped in SQL; they are decrypted,
     * thinned in memory by [mapObservationIdsToPrune] (keeping the most recent observation in every
     * [minMeters] neighbourhood per device and provider), and deleted by id in one transaction.
     */
    fun deduplicate(minMeters: Double = DEFAULT_DEDUP_METERS): Int =
        deleteByIds(mapObservationIdsToPrune(recent(limit = MAX_LOOKUP_ROWS), minMeters))

    private fun deleteByIds(ids: List<String>): Int {
        if (ids.isEmpty()) {
            return 0
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.chunked(DELETE_CHUNK).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.delete("map_observations", "id IN ($placeholders)", chunk.toTypedArray())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ids.size
    }

    private fun LocalMapObservation.toValues() = ContentValues().apply {
        put("id", id)
        put("observed_utc", observedUtc.toString())
        put("encrypted_device_address", cipher.encrypt(deviceAddress))
        if (deviceName == null) {
            putNull("encrypted_device_name")
        } else {
            put("encrypted_device_name", cipher.encrypt(deviceName))
        }
        put("classification_tier", classificationTier.name)
        put("encrypted_classification_evidence", cipher.encrypt(classificationEvidence))
        put("rssi", rssi)
        put("encrypted_phone_latitude", cipher.encrypt(phoneLatitude.toString()))
        put("encrypted_phone_longitude", cipher.encrypt(phoneLongitude.toString()))
        put("phone_accuracy_meters", phoneAccuracyMeters)
        put("encrypted_location_provider", cipher.encrypt(locationProvider))
        put("estimated_range_meters", estimatedRangeMeters)
        put("confidence_radius_meters", confidenceRadiusMeters)
    }

    private companion object {
        const val DATABASE_VERSION = 1
        const val MAX_LOOKUP_ROWS = 5_000
        const val DELETE_CHUNK = 500
        val COLUMNS = arrayOf(
            "id",
            "observed_utc",
            "encrypted_device_address",
            "encrypted_device_name",
            "classification_tier",
            "encrypted_classification_evidence",
            "rssi",
            "encrypted_phone_latitude",
            "encrypted_phone_longitude",
            "phone_accuracy_meters",
            "encrypted_location_provider",
            "estimated_range_meters",
            "confidence_radius_meters",
        )
    }
}
