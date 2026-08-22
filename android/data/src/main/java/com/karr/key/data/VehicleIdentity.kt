package com.karr.key.data

import android.content.Context
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import org.json.JSONObject

data class VehicleIdentity(
    val vin: String,
    val modelYear: String?,
    val make: String?,
    val model: String?,
    val trim: String?,
    val bodyClass: String?,
    val decodedUtc: Instant,
) {
    val title: String
        get() = listOfNotNull(modelYear, make, model).filter(String::isNotBlank).joinToString(" ")

    val details: String?
        get() = listOfNotNull(trim, bodyClass).filter(String::isNotBlank).distinct().joinToString(" · ")
            .ifBlank { null }
}

/** Encrypted, VIN-keyed cache for identities explicitly decoded by the user. */
class LocalVehicleIdentityStore(
    context: Context,
    private val cipher: StringCipher = AndroidKeystoreCipher("karr_key_vehicle_identity_v1"),
) {
    private val preferences = context.getSharedPreferences(
        "karr_key_vehicle_identities",
        Context.MODE_PRIVATE,
    )

    fun get(vin: String): VehicleIdentity? = runCatching {
        preferences.getString(storageKey(vin), null)
            ?.let(cipher::decrypt)
            ?.let(::decode)
            ?.takeIf { identity -> identity.vin == vin.uppercase() }
    }.getOrNull()

    fun put(identity: VehicleIdentity) {
        preferences.edit()
            .putString(storageKey(identity.vin), cipher.encrypt(encode(identity)))
            .apply()
    }

    private fun storageKey(vin: String): String = MessageDigest.getInstance("SHA-256")
        .digest(vin.uppercase().toByteArray(Charsets.US_ASCII))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun encode(identity: VehicleIdentity): String = JSONObject()
        .put("vin", identity.vin)
        .put("modelYear", identity.modelYear)
        .put("make", identity.make)
        .put("model", identity.model)
        .put("trim", identity.trim)
        .put("bodyClass", identity.bodyClass)
        .put("decodedUtc", identity.decodedUtc.toString())
        .toString()

    private fun decode(value: String): VehicleIdentity = JSONObject(value).let { json ->
        VehicleIdentity(
            vin = json.getString("vin"),
            modelYear = json.optionalString("modelYear"),
            make = json.optionalString("make"),
            model = json.optionalString("model"),
            trim = json.optionalString("trim"),
            bodyClass = json.optionalString("bodyClass"),
            decodedUtc = Instant.parse(json.getString("decodedUtc")),
        )
    }

    private fun JSONObject.optionalString(name: String): String? =
        optString(name).takeIf { value -> value.isNotBlank() && value != "null" }
}

fun decodeVinWithVpic(vin: String): VehicleIdentity {
    val url = URI(
        "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/$vin?format=json",
    ).toURL()
    val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
    }
    return try {
        check(connection.responseCode in 200..299) { "NHTSA returned HTTP ${connection.responseCode}." }
        parseVpicResponse(vin, connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
        connection.disconnect()
    }
}

fun parseVpicResponse(
    vin: String,
    response: String,
    decodedUtc: Instant = Instant.now(),
): VehicleIdentity {
    val result = JSONObject(response).getJSONArray("Results").getJSONObject(0)
    val errorCode = result.optString("ErrorCode")
    check(errorCode.isBlank() || errorCode.substringBefore(',').trim() == "0") {
        result.optString("ErrorText").ifBlank { "NHTSA could not decode this VIN." }
    }
    return VehicleIdentity(
        vin = vin,
        modelYear = result.value("ModelYear"),
        make = result.value("Make"),
        model = result.value("Model"),
        trim = result.value("Trim"),
        bodyClass = result.value("BodyClass"),
        decodedUtc = decodedUtc,
    ).also { identity ->
        check(identity.make != null || identity.model != null) { "NHTSA returned no make or model." }
    }
}

private fun JSONObject.value(name: String): String? =
    optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }
