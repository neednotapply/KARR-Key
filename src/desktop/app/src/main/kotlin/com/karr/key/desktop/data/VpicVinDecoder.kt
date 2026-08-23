package com.karr.key.desktop.data

import com.karr.key.data.VehicleIdentity
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import org.json.JSONObject

/**
 * Desktop NHTSA vPIC VIN decode, mirroring the Android decodeVinWithVpic /
 * parseVpicResponse. This is the only user-triggered network call the desktop
 * host makes besides OSM map tiles, honouring the offline invariant.
 */
fun decodeVinWithVpic(vin: String): VehicleIdentity {
    val url = URI("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/$vin?format=json").toURL()
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
