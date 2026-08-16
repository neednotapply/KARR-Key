package com.karr.key.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.karr.key.protocol.KarrVulnerabilityStatus
import java.time.Instant

/**
 * What a unit's most recent self-reported GPS (extended READ_STATE) plus its authentication
 * challenge revealed when we connected to it: its authoritative unit-type model (from READ_MODE)
 * and whether it is vulnerable or patched (from the challenge width). Null model means we never got
 * a mode read (e.g. a patched unit rejects auth before READ_MODE).
 */
data class DeviceProfile(
    val deviceAddress: String,
    val unitModelCode: Int?,
    val vulnerability: KarrVulnerabilityStatus,
    val updatedUtc: Instant,
)

/**
 * Persists one [DeviceProfile] per device, keyed to the MAC address, so the Map can label a device
 * with what connecting to it proved rather than only the scan-time format guess.
 *
 * A newer observation merges into the stored row: a null model never erases a known one, and a
 * definite verdict replaces [KarrVulnerabilityStatus.UNKNOWN]. The address is encrypted with the
 * same Android Keystore-backed [StringCipher] the other local stores use; because the IV is
 * randomized the address cannot be matched in SQL, so the (tiny) table is scanned and matched in
 * memory, mirroring [LocalDeviceReadStore].
 */
class LocalDeviceProfileStore(
    context: Context,
    private val cipher: StringCipher = AndroidKeystoreCipher("karr_key_device_profiles_v1"),
) : SQLiteOpenHelper(context, "karr_key_device_profiles.db", null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE device_profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                encrypted_device_address TEXT NOT NULL,
                unit_model_code INTEGER NULL,
                vulnerability TEXT NOT NULL,
                updated_utc TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == newVersion) {
            "No destructive device-profile migration is defined."
        }
    }

    /**
     * Merges what a connection just revealed about [deviceAddress] into its stored profile: a null
     * [unitModelCode] keeps any previously-known model, and an [KarrVulnerabilityStatus.UNKNOWN]
     * verdict keeps any previously-known one. Returns the stored (merged) profile.
     */
    fun record(
        deviceAddress: String,
        unitModelCode: Int?,
        vulnerability: KarrVulnerabilityStatus,
        updatedUtc: Instant = Instant.now(),
    ): DeviceProfile {
        val address = deviceAddress.uppercase()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = findRow(db, address)
            val merged = DeviceProfile(
                deviceAddress = address,
                unitModelCode = unitModelCode ?: existing?.second?.unitModelCode,
                vulnerability = if (vulnerability != KarrVulnerabilityStatus.UNKNOWN) {
                    vulnerability
                } else {
                    existing?.second?.vulnerability ?: KarrVulnerabilityStatus.UNKNOWN
                },
                updatedUtc = updatedUtc,
            )
            existing?.let { db.delete("device_profiles", "id = ?", arrayOf(it.first.toString())) }
            db.insertOrThrow("device_profiles", null, merged.toValues())
            db.setTransactionSuccessful()
            return merged
        } finally {
            db.endTransaction()
        }
    }

    /** All stored profiles, keyed by uppercased device address (one per device). */
    fun all(): Map<String, DeviceProfile> =
        allRows().associate { (_, profile) -> profile.deviceAddress to profile }

    fun clear() {
        writableDatabase.delete("device_profiles", null, null)
    }

    private fun findRow(db: SQLiteDatabase, address: String): Pair<Long, DeviceProfile>? =
        db.query("device_profiles", COLUMNS, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                if (cipher.decrypt(cursor.getString(1)) == address) {
                    return@use cursor.getLong(0) to cursor.toProfile(address)
                }
            }
            null
        }

    private fun allRows(): List<Pair<Long, DeviceProfile>> =
        readableDatabase.query(
            "device_profiles",
            COLUMNS,
            null,
            null,
            null,
            null,
            "updated_utc DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val address = cipher.decrypt(cursor.getString(1))
                    add(cursor.getLong(0) to cursor.toProfile(address))
                }
            }
        }

    private fun Cursor.toProfile(address: String) = DeviceProfile(
        deviceAddress = address,
        unitModelCode = if (isNull(2)) null else getInt(2),
        vulnerability = runCatching { KarrVulnerabilityStatus.valueOf(getString(3)) }
            .getOrDefault(KarrVulnerabilityStatus.UNKNOWN),
        updatedUtc = Instant.parse(getString(4)),
    )

    private fun DeviceProfile.toValues() = ContentValues().apply {
        put("encrypted_device_address", cipher.encrypt(deviceAddress))
        if (unitModelCode == null) {
            putNull("unit_model_code")
        } else {
            put("unit_model_code", unitModelCode)
        }
        put("vulnerability", vulnerability.name)
        put("updated_utc", updatedUtc.toString())
    }

    private companion object {
        const val DATABASE_VERSION = 1
        val COLUMNS = arrayOf(
            "id",
            "encrypted_device_address",
            "unit_model_code",
            "vulnerability",
            "updated_utc",
        )
    }
}
