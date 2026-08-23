package com.karr.key.desktop.map

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A coarse, IP-derived location estimate — the way a website locates a visitor without GPS. */
data class DesktopLocationEstimate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val label: String?,
)

/**
 * Estimates the desktop's location from its internet connection (IP geolocation),
 * the cross-platform, no-GPS equivalent of a browser's coarse location. It is
 * user-triggered (a map button), not automatic, and city-level in accuracy.
 *
 * A future enhancement could prefer the OS location service where available
 * (Windows `Windows.Devices.Geolocation`, macOS CoreLocation, Linux GeoClue) for
 * a consented, WiFi-assisted fix; those need native interop and are not wired yet.
 */
object DesktopLocationProvider {
    private const val ENDPOINT = "https://ipwho.is/"
    private const val USER_AGENT = "KARR-Key/1.0.5 (+https://github.com/neednotapply/KARR-Key)"

    // IP geolocation is city-level; a coarse radius keeps the "you are here" marker honest.
    private const val IP_ACCURACY_METERS = 5_000.0

    suspend fun estimate(): Result<DesktopLocationEstimate> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URI(ENDPOINT).toURL()
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                check(connection.responseCode in 200..299) {
                    "Location lookup returned HTTP ${connection.responseCode}."
                }
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                check(json.optBoolean("success", true)) {
                    json.optString("message").ifBlank { "Location lookup failed." }
                }
                val latitude = json.getDouble("latitude")
                val longitude = json.getDouble("longitude")
                require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                    "Location lookup returned an invalid position."
                }
                val label = listOf("city", "region")
                    .mapNotNull { json.optString(it).takeIf(String::isNotBlank) }
                    .joinToString(", ")
                    .ifBlank { null }
                DesktopLocationEstimate(latitude, longitude, IP_ACCURACY_METERS, label)
            } finally {
                connection.disconnect()
            }
        }
    }
}
