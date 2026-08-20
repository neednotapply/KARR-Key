package com.karr.key.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapHistoryDedupTest {
    @Test
    fun collapsesRepeatSightingsAtTheSameSpotKeepingTheMostRecent() {
        // One parked unit re-scanned three times from within a metre across sessions.
        val older = obs("a", 36.67770, -121.80180, "2024-05-01T12:00:00Z")
        val middle = obs("b", 36.67771, -121.80181, "2024-05-01T12:05:00Z")
        val newest = obs("c", 36.677705, -121.801805, "2024-05-02T09:00:00Z")

        val removed = mapObservationIdsToPrune(listOf(older, middle, newest))

        assertEquals(setOf("a", "b"), removed.toSet())
        assertTrue("keeps the most recent sighting", "c" !in removed)
    }

    @Test
    fun keepsDistinctVantagePointsFartherApartThanTheThreshold() {
        // ~100 m apart: genuine spread the location estimate should keep.
        val here = obs("a", 36.67770, -121.80180, "2024-05-01T12:00:00Z")
        val downTheStreet = obs("b", 36.67860, -121.80180, "2024-05-01T12:05:00Z")

        assertTrue(mapObservationIdsToPrune(listOf(here, downTheStreet)).isEmpty())
    }

    @Test
    fun neverMergesAcrossDevicesOrProviders() {
        val liveA = obs("a", 36.67770, -121.80180, "2024-05-01T12:00:00Z")
        val otherDevice = obs("b", 36.67770, -121.80180, "2024-05-01T12:01:00Z", address = "AA:BB:CC:DD:EE:FF")
        val vehicleGps = obs("c", 36.67770, -121.80180, "2024-05-01T12:02:00Z", provider = VEHICLE_GPS_PROVIDER)

        // Same spot, but a different device and a different provider are distinct data, not repeats.
        assertTrue(mapObservationIdsToPrune(listOf(liveA, otherDevice, vehicleGps)).isEmpty())
    }

    @Test
    fun matchesRegardlessOfStoredAddressCasing() {
        val upper = obs("a", 36.67770, -121.80180, "2024-05-01T12:00:00Z", address = "44:B7:D0:29:A5:4D")
        val lower = obs("b", 36.67770, -121.80180, "2024-05-01T12:01:00Z", address = "44:b7:d0:29:a5:4d")

        assertEquals(listOf("a"), mapObservationIdsToPrune(listOf(upper, lower)))
    }

    @Test
    fun emptyHistoryRemovesNothing() {
        assertTrue(mapObservationIdsToPrune(emptyList()).isEmpty())
    }

    private fun obs(
        id: String,
        lat: Double,
        lon: Double,
        at: String,
        address: String = "44:B7:D0:29:A5:4D",
        provider: String = "gps",
    ) = LocalMapObservation(
        id = id,
        observedUtc = Instant.parse(at),
        deviceAddress = address,
        deviceName = null,
        classificationTier = MapCandidateTier.CONFIRMED_FORMAT,
        classificationEvidence = "evidence",
        rssi = -60,
        phoneLatitude = lat,
        phoneLongitude = lon,
        phoneAccuracyMeters = 10f,
        locationProvider = provider,
        estimatedRangeMeters = 10.0,
        confidenceRadiusMeters = 20.0,
    )
}
