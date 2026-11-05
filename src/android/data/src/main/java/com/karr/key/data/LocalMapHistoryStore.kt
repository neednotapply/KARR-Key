package com.karr.key.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.util.UUID

// MapCandidateTier, VEHICLE_GPS_PROVIDER, LocalMapObservation, and
// MapHistoryImportSummary moved to shared:persistence (same package) for reuse
// by the desktop host; the SQLite store implementation stays here.
class LocalMapHistoryStore(
    context: Context,
    // Bulk location data: uses the in-process data-key format so loading ~1,000 rows doesn't
    // cost ~6,000 Keystore binder calls (~16 s). Rows written before this change stay readable
    // and are re-encrypted by [upgradeLegacyEncryption].
    private val cipher: StringCipher = AndroidKeystoreCipher("karr_key_map_history_v1", context),
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

    /**
     * Re-encrypts rows still stored in the slow Keystore-direct format into the fast data-key
     * format, returning how many rows were upgraded. Safe to run repeatedly and while the map is
     * reading: the slow decrypt/encrypt happens outside any transaction, and each batch commits
     * atomically, so an interruption leaves every row readable in one format or the other. A row
     * that cannot be decrypted is skipped rather than blocking the rest.
     */
    @Synchronized
    fun upgradeLegacyEncryption(): Int {
        val pendingIds = readableDatabase.query(
            "map_observations",
            arrayOf("id") + ENCRYPTED_COLUMNS,
            null, null, null, null, null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val needsUpgrade = ENCRYPTED_COLUMNS.indices.any { index ->
                        cursor.getString(index + 1)?.let(cipher::needsUpgrade) == true
                    }
                    if (needsUpgrade) add(cursor.getString(0))
                }
            }
        }
        var upgraded = 0
        pendingIds.chunked(UPGRADE_BATCH).forEach { ids ->
            val updates = ids.mapNotNull { id -> runCatching { upgradedValues(id) }.getOrNull() }
            if (updates.isEmpty()) return@forEach
            val db = writableDatabase
            db.beginTransaction()
            try {
                updates.forEach { (id, original, values) ->
                    // Every write re-encrypts with a fresh random IV, so an unchanged address
                    // ciphertext proves the row wasn't rewritten (e.g. by an import) since we
                    // read it; if it was, the newer data wins and this stale upgrade is dropped.
                    val changed = db.update(
                        "map_observations",
                        values,
                        "id = ? AND encrypted_device_address = ?",
                        arrayOf(id, original),
                    )
                    if (changed > 0) upgraded++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return upgraded
    }

    private data class PendingUpgrade(
        val id: String,
        val originalAddressCipher: String,
        val values: ContentValues,
    )

    private fun upgradedValues(id: String): PendingUpgrade? {
        readableDatabase.query(
            "map_observations",
            ENCRYPTED_COLUMNS,
            "id = ?",
            arrayOf(id),
            null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val values = ContentValues()
            ENCRYPTED_COLUMNS.forEachIndexed { index, column ->
                val stored = cursor.getString(index) ?: return@forEachIndexed
                values.put(column, cipher.encrypt(cipher.decrypt(stored)))
            }
            return PendingUpgrade(id, cursor.getString(0), values)
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
        val name = deviceName
        if (name == null) {
            putNull("encrypted_device_name")
        } else {
            put("encrypted_device_name", cipher.encrypt(name))
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
        const val UPGRADE_BATCH = 50
        val ENCRYPTED_COLUMNS = arrayOf(
            "encrypted_device_address",
            "encrypted_device_name",
            "encrypted_classification_evidence",
            "encrypted_phone_latitude",
            "encrypted_phone_longitude",
            "encrypted_location_provider",
        )
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
