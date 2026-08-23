package com.karr.key.desktop.data

import com.karr.key.data.DeviceReadRecord
import com.karr.key.data.StringCipher
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Desktop equivalent of the Android LocalDeviceReadStore: the latest decoded READ
 * result per (device address, command). Encrypted at rest; VIN auto-decode runs
 * off the UI thread and caches into [DesktopVehicleIdentityStore].
 */
class DesktopDeviceReadStore(
    cipher: StringCipher = FileStringCipher(),
    file: Path = AppPaths.file("device_reads.json"),
    private val identityStore: DesktopVehicleIdentityStore = DesktopVehicleIdentityStore(cipher),
) {
    private val store = EncryptedTextFile(file, cipher)
    private val lock = Any()
    private val identityDecodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun autoDecodeVin(vin: String) {
        identityDecodeScope.launch {
            if (identityStore.get(vin) == null) {
                runCatching { decodeVinWithVpic(vin) }.onSuccess(identityStore::put)
            }
        }
    }

    fun record(
        deviceAddress: String,
        command: String,
        heading: String,
        lines: List<String>,
        unitModel: String?,
        capturedUtc: Instant = Instant.now(),
    ) = synchronized(lock) {
        val address = deviceAddress.uppercase()
        val grouped = readAll().toMutableMap()
        val forAddress = grouped.getOrPut(address) { mutableListOf() }
        forAddress.removeAll { it.command == command }
        forAddress.add(DeviceReadRecord(command, heading, lines, unitModel, capturedUtc))
        writeAll(grouped)
    }

    fun upsertAll(recordsByAddress: Map<String, List<DeviceReadRecord>>) {
        if (recordsByAddress.isEmpty()) return
        synchronized(lock) {
            val grouped = readAll().toMutableMap()
            recordsByAddress.forEach { (rawAddress, records) ->
                val address = rawAddress.uppercase()
                val forAddress = grouped.getOrPut(address) { mutableListOf() }
                records.forEach { record ->
                    forAddress.removeAll { it.command == record.command }
                    forAddress.add(record)
                }
            }
            writeAll(grouped)
        }
    }

    fun readsFor(deviceAddress: String): List<DeviceReadRecord> = synchronized(lock) {
        readAll()[deviceAddress.uppercase()].orEmpty().sortedByDescending { it.capturedUtc }
    }

    fun allGroupedByAddress(): Map<String, List<DeviceReadRecord>> = synchronized(lock) {
        readAll().mapValues { (_, records) -> records.sortedByDescending { it.capturedUtc } }
    }

    fun clear() = synchronized(lock) { store.save(JSONObject().toString()) }

    fun close() {
        identityDecodeScope.cancel()
    }

    private fun readAll(): MutableMap<String, MutableList<DeviceReadRecord>> {
        val text = store.load() ?: return mutableMapOf()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return mutableMapOf()
        val result = mutableMapOf<String, MutableList<DeviceReadRecord>>()
        json.keys().forEach { address ->
            val array = json.getJSONArray(address)
            val records = (0 until array.length()).mapNotNull { index ->
                runCatching { array.getJSONObject(index).toRecord() }.getOrNull()
            }.toMutableList()
            result[address] = records
        }
        return result
    }

    private fun writeAll(grouped: Map<String, List<DeviceReadRecord>>) {
        val json = JSONObject()
        grouped.forEach { (address, records) ->
            val array = JSONArray()
            records.forEach { array.put(it.toJson()) }
            json.put(address, array)
        }
        store.save(json.toString())
    }

    private fun DeviceReadRecord.toJson(): JSONObject = JSONObject()
        .put("command", command)
        .put("heading", heading)
        .put("lines", JSONArray(lines))
        .put("unitModel", unitModel ?: JSONObject.NULL)
        .put("capturedUtc", capturedUtc.toString())

    private fun JSONObject.toRecord(): DeviceReadRecord = DeviceReadRecord(
        command = getString("command"),
        heading = getString("heading"),
        lines = getJSONArray("lines").let { arr -> (0 until arr.length()).map { arr.getString(it) } },
        unitModel = if (isNull("unitModel")) null else getString("unitModel"),
        capturedUtc = Instant.parse(getString("capturedUtc")),
    )
}
