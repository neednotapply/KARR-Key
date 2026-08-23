package com.karr.key.desktop.data

import com.karr.key.data.StringCipher
import com.karr.key.data.VehicleIdentity
import java.nio.file.Path
import java.time.Instant
import org.json.JSONObject

/**
 * Desktop equivalent of the Android LocalVehicleIdentityStore: a VIN-keyed cache
 * of identities the user explicitly decoded. Encrypted at rest.
 */
class DesktopVehicleIdentityStore(
    cipher: StringCipher = FileStringCipher(),
    file: Path = AppPaths.file("vehicle_identities.json"),
) {
    private val store = EncryptedTextFile(file, cipher)
    private val lock = Any()

    fun get(vin: String): VehicleIdentity? = synchronized(lock) {
        val json = read()
        val key = vin.uppercase()
        if (!json.has(key)) return null
        runCatching { json.getJSONObject(key).toIdentity() }.getOrNull()
    }

    fun put(identity: VehicleIdentity) = synchronized(lock) {
        val json = read()
        json.put(identity.vin.uppercase(), identity.toJson())
        store.save(json.toString())
    }

    private fun read(): JSONObject {
        val text = store.load() ?: return JSONObject()
        return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    private fun VehicleIdentity.toJson(): JSONObject = JSONObject()
        .put("vin", vin)
        .put("modelYear", modelYear ?: JSONObject.NULL)
        .put("make", make ?: JSONObject.NULL)
        .put("model", model ?: JSONObject.NULL)
        .put("trim", trim ?: JSONObject.NULL)
        .put("bodyClass", bodyClass ?: JSONObject.NULL)
        .put("decodedUtc", decodedUtc.toString())

    private fun JSONObject.toIdentity(): VehicleIdentity = VehicleIdentity(
        vin = getString("vin"),
        modelYear = optionalString("modelYear"),
        make = optionalString("make"),
        model = optionalString("model"),
        trim = optionalString("trim"),
        bodyClass = optionalString("bodyClass"),
        decodedUtc = Instant.parse(getString("decodedUtc")),
    )

    private fun JSONObject.optionalString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
