package com.karr.key.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persists the latest decoded READ result of each operation, keyed to the unit's MAC address, so
 * previously-captured vehicle data can be surfaced on the Map when a device is selected.
 *
 * Only the newest result of a given (address, command) pair is kept — a fresh read overwrites the
 * previous snapshot. Device addresses and the decoded data are encrypted with the same
 * Android Keystore-backed [StringCipher] the map history uses. Because the address is encrypted
 * with a randomized IV it cannot be matched in SQL, so lookups decrypt rows and match in memory;
 * the dataset is tiny (one row per command per device) so this is inexpensive.
 */
class LocalDeviceReadStore(
    context: Context,
    private val cipher: StringCipher = AndroidKeystoreCipher("karr_key_device_reads_v1"),
) : SQLiteOpenHelper(context, "karr_key_device_reads.db", null, DATABASE_VERSION) {
    private val identityStore = LocalVehicleIdentityStore(context.applicationContext)
    private val identityDecodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun autoDecodeVin(vin: String) {
        identityDecodeScope.launch {
            if (identityStore.get(vin) == null) {
                runCatching { decodeVinWithVpic(vin) }
                    .onSuccess(identityStore::put)
            }
        }
    }

    override fun close() {
        identityDecodeScope.cancel()
        super.close()
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE device_reads (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                encrypted_device_address TEXT NOT NULL,
                command TEXT NOT NULL,
                encrypted_heading TEXT NOT NULL,
                encrypted_lines TEXT NOT NULL,
                encrypted_unit_model TEXT NULL,
                captured_utc TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == newVersion) {
            "No destructive device-read migration is defined."
        }
    }

    /**
     * Records the latest [heading]/[lines] for [command] against [deviceAddress]. Address casing is
     * normalized to upper case so it matches the map's device grouping. Replaces any existing
     * record for the same (address, command).
     */
    fun record(
        deviceAddress: String,
        command: String,
        heading: String,
        lines: List<String>,
        unitModel: String?,
        capturedUtc: Instant = Instant.now(),
    ) {
        val address = deviceAddress.uppercase()
        val db = writableDatabase
        db.beginTransaction()
        try {
            deleteMatching(db, address, command)
            db.insertOrThrow(
                "device_reads",
                null,
                contentValuesFor(address, command, heading, lines, unitModel, capturedUtc),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Bulk-imports read snapshots (e.g. from a re-imported WiGLE export). Each incoming record
     * replaces any existing record for the same (address, command).
     */
    fun upsertAll(recordsByAddress: Map<String, List<DeviceReadRecord>>) {
        if (recordsByAddress.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            recordsByAddress.forEach { (rawAddress, records) ->
                val address = rawAddress.uppercase()
                records.forEach { record ->
                    deleteMatching(db, address, record.command)
                    db.insertOrThrow(
                        "device_reads",
                        null,
                        contentValuesFor(
                            address = address,
                            command = record.command,
                            heading = record.heading,
                            lines = record.lines,
                            unitModel = record.unitModel,
                            capturedUtc = record.capturedUtc,
                        ),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun readsFor(deviceAddress: String): List<DeviceReadRecord> {
        val address = deviceAddress.uppercase()
        return allRows().filter { (rowAddress, _) -> rowAddress == address }.map { it.second }
    }

    fun allGroupedByAddress(): Map<String, List<DeviceReadRecord>> =
        allRows()
            .groupBy({ it.first }, { it.second })

    fun clear() {
        writableDatabase.delete("device_reads", null, null)
    }

    private fun deleteMatching(db: SQLiteDatabase, address: String, command: String) {
        // The address is encrypted with a randomized IV, so it cannot be matched in SQL; find the
        // existing (address, command) row in memory and delete it by id before inserting the fresh
        // snapshot.
        val existingId = db.query(
            "device_reads",
            arrayOf("id", "encrypted_device_address"),
            "command = ?",
            arrayOf(command),
            null,
            null,
            null,
        ).use { cursor ->
            var found: Long? = null
            while (cursor.moveToNext()) {
                if (cipher.decrypt(cursor.getString(1)) == address) {
                    found = cursor.getLong(0)
                    break
                }
            }
            found
        }
        if (existingId != null) {
            db.delete("device_reads", "id = ?", arrayOf(existingId.toString()))
        }
    }

    private fun allRows(): List<Pair<String, DeviceReadRecord>> =
        readableDatabase.query(
            "device_reads",
            COLUMNS,
            null,
            null,
            null,
            null,
            "captured_utc DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val address = cipher.decrypt(cursor.getString(1))
                    add(
                        address to DeviceReadRecord(
                            command = cursor.getString(2),
                            heading = cipher.decrypt(cursor.getString(3)),
                            lines = DeviceReadCodec.decodeStringList(cipher.decrypt(cursor.getString(4))),
                            unitModel = cursor.getString(5)?.let(cipher::decrypt),
                            capturedUtc = Instant.parse(cursor.getString(6)),
                        ),
                    )
                }
            }
        }

    private fun contentValuesFor(
        address: String,
        command: String,
        heading: String,
        lines: List<String>,
        unitModel: String?,
        capturedUtc: Instant,
    ) = ContentValues().apply {
        put("encrypted_device_address", cipher.encrypt(address))
        put("command", command)
        put("encrypted_heading", cipher.encrypt(heading))
        put("encrypted_lines", cipher.encrypt(DeviceReadCodec.encodeStringList(lines)))
        if (unitModel == null) {
            putNull("encrypted_unit_model")
        } else {
            put("encrypted_unit_model", cipher.encrypt(unitModel))
        }
        put("captured_utc", capturedUtc.toString())
    }

    private companion object {
        const val DATABASE_VERSION = 1
        val COLUMNS = arrayOf(
            "id",
            "encrypted_device_address",
            "command",
            "encrypted_heading",
            "encrypted_lines",
            "encrypted_unit_model",
            "captured_utc",
        )
    }
}
