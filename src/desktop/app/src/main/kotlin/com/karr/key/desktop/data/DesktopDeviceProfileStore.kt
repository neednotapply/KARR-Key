package com.karr.key.desktop.data

import com.karr.key.data.DeviceProfile
import com.karr.key.data.StringCipher
import com.karr.key.protocol.KarrVulnerabilityStatus
import java.nio.file.Path
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Desktop equivalent of the Android LocalDeviceProfileStore: one [DeviceProfile]
 * per device address, with the same merge semantics — a null model never erases a
 * known one, and a definite verdict replaces UNKNOWN. Encrypted at rest.
 */
class DesktopDeviceProfileStore(
    cipher: StringCipher = FileStringCipher(),
    file: Path = AppPaths.file("device_profiles.json"),
) {
    private val store = EncryptedTextFile(file, cipher)
    private val lock = Any()

    fun record(
        deviceAddress: String,
        unitModelCode: Int?,
        vulnerability: KarrVulnerabilityStatus,
        updatedUtc: Instant = Instant.now(),
    ): DeviceProfile = synchronized(lock) {
        val address = deviceAddress.uppercase()
        val current = readAll().associateBy { it.deviceAddress }.toMutableMap()
        val existing = current[address]
        val merged = DeviceProfile(
            deviceAddress = address,
            unitModelCode = unitModelCode ?: existing?.unitModelCode,
            vulnerability = if (vulnerability != KarrVulnerabilityStatus.UNKNOWN) {
                vulnerability
            } else {
                existing?.vulnerability ?: KarrVulnerabilityStatus.UNKNOWN
            },
            updatedUtc = updatedUtc,
        )
        current[address] = merged
        writeAll(current.values.toList())
        merged
    }

    fun all(): Map<String, DeviceProfile> = synchronized(lock) {
        readAll().associateBy { it.deviceAddress }
    }

    fun clear() = synchronized(lock) { store.save(JSONArray().toString()) }

    private fun readAll(): List<DeviceProfile> {
        val text = store.load() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { array.getJSONObject(index).toProfile() }.getOrNull()
        }
    }

    private fun writeAll(profiles: List<DeviceProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        store.save(array.toString())
    }

    private fun DeviceProfile.toJson(): JSONObject = JSONObject()
        .put("deviceAddress", deviceAddress)
        .put("unitModelCode", unitModelCode ?: JSONObject.NULL)
        .put("vulnerability", vulnerability.name)
        .put("updatedUtc", updatedUtc.toString())

    private fun JSONObject.toProfile(): DeviceProfile = DeviceProfile(
        deviceAddress = getString("deviceAddress"),
        unitModelCode = if (isNull("unitModelCode")) null else getInt("unitModelCode"),
        vulnerability = runCatching { KarrVulnerabilityStatus.valueOf(getString("vulnerability")) }
            .getOrDefault(KarrVulnerabilityStatus.UNKNOWN),
        updatedUtc = Instant.parse(getString("updatedUtc")),
    )
}
