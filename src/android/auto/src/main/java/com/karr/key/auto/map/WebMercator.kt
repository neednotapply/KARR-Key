package com.karr.key.auto.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Web Mercator (EPSG:3857) tile math, matching the OSM/osmdroid scheme the phone map uses. At an
 * integer zoom [z] the world is a square of `256 * 2^z` pixels; a geographic point maps to a world
 * pixel, and the renderer offsets those against the viewport centre to get screen pixels.
 *
 * Kept deliberately free of Android types so it can be unit-tested on the JVM.
 */
internal object WebMercator {
    const val TILE_SIZE = 256

    fun worldSize(z: Int): Double = (TILE_SIZE.toDouble()) * (1 shl z)

    fun lonToWorldX(lon: Double, z: Int): Double =
        (lon + 180.0) / 360.0 * worldSize(z)

    fun latToWorldY(lat: Double, z: Int): Double {
        val clamped = lat.coerceIn(-MAX_LAT, MAX_LAT)
        val rad = Math.toRadians(clamped)
        val y = 0.5 - ln((1 + sin(rad)) / (1 - sin(rad))) / (4 * PI)
        return y * worldSize(z)
    }

    fun worldXToLon(worldX: Double, z: Int): Double =
        worldX / worldSize(z) * 360.0 - 180.0

    fun worldYToLat(worldY: Double, z: Int): Double {
        val n = PI - 2.0 * PI * (worldY / worldSize(z))
        return Math.toDegrees(Math.atan(Math.sinh(n)))
    }

    /** Metres per screen pixel at [lat] and zoom [z] — used to size range circles. */
    fun metersPerPixel(lat: Double, z: Int): Double =
        EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) / worldSize(z)

    /** Great-circle distance in metres between two coordinates (Haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private const val MAX_LAT = 85.05112878
    private const val EARTH_RADIUS_M = 6_378_137.0
    private const val EARTH_CIRCUMFERENCE_M = 2 * PI * EARTH_RADIUS_M
}
