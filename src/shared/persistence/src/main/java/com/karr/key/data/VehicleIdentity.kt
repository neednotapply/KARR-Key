package com.karr.key.data

import java.time.Instant

/** A VIN's decoded identity. Platform-free value type shared by every host; the
 * Android host also owns the NHTSA network decode and its encrypted cache. */
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
