package com.karr.key.ui

import com.karr.key.data.LocalMapObservation
import com.karr.key.data.MapCandidateTier
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapEstimationTest {
    @Test
    fun strongerRssiProducesSmallerEstimatedRange() {
        val strong = estimatedBleRangeMeters(-45)
        val medium = estimatedBleRangeMeters(-65)
        val weak = estimatedBleRangeMeters(-90)

        assertTrue(strong < medium)
        assertTrue(medium < weak)
        assertEquals(120.0, estimatedBleRangeMeters(0), 0.0)
    }

    @Test
    fun confidenceRadiusCombinesSignalAndPhoneAccuracy() {
        val close = confidenceRadiusMeters(
            rangeMeters = estimatedBleRangeMeters(-45),
            phoneAccuracyMeters = 5f,
        )
        val uncertain = confidenceRadiusMeters(
            rangeMeters = estimatedBleRangeMeters(-80),
            phoneAccuracyMeters = 40f,
        )

        assertTrue(close < uncertain)
        assertTrue(close >= 5.0)
        assertTrue(uncertain <= 250.0)
    }

    @Test
    fun estimatesUseStrongestAndMostAccurateObservationsMoreHeavily() {
        val weak = observation(
            observedUtc = Instant.parse("2026-08-03T10:00:00Z"),
            latitude = 32.0,
            longitude = -117.0,
            rssi = -92,
            accuracy = 60f,
        )
        val strong = observation(
            observedUtc = Instant.parse("2026-08-03T10:01:00Z"),
            latitude = 32.001,
            longitude = -117.001,
            rssi = -42,
            accuracy = 5f,
        )

        val estimate = estimateDeviceLocations(listOf(weak, strong)).single()

        assertEquals(strong.deviceAddress, estimate.address)
        assertTrue(estimate.latitude > 32.0008)
        assertTrue(estimate.longitude < -117.0008)
        assertEquals(strong.rssi, estimate.rssi)
    }

    @Test
    fun recentSightingsOutweighOlderOnesSoAMovingUnitTracksItsLatestPosition() {
        // Ten strong sightings at an old spot, then one equally-strong sighting a minute later well
        // away. The naive all-history centroid would sit ~91% back at the old spot (~32.0009); with
        // recency weighting the pin should track to within a few metres of the latest 32.0100 fix.
        val oldSpot = (0..9).map { i ->
            observation(
                observedUtc = Instant.parse("2026-08-03T10:00:00Z").plusSeconds(i.toLong()),
                latitude = 32.0000,
                longitude = -117.0000,
                rssi = -50,
                accuracy = 8f,
            )
        }
        val recent = observation(
            observedUtc = Instant.parse("2026-08-03T10:01:00Z"),
            latitude = 32.0100,
            longitude = -117.0100,
            rssi = -50,
            accuracy = 8f,
        )

        val estimate = estimateDeviceLocations(oldSpot + recent).single()

        assertTrue("lat=${estimate.latitude}", estimate.latitude > 32.0080)
        assertTrue("lon=${estimate.longitude}", estimate.longitude < -117.0080)
    }

    private fun observation(
        observedUtc: Instant,
        latitude: Double,
        longitude: Double,
        rssi: Int,
        accuracy: Float,
    ) = LocalMapObservation(
        id = observedUtc.toString(),
        observedUtc = observedUtc,
        deviceAddress = "00:11:22:33:44:55",
        deviceName = "QT000001",
        classificationTier = MapCandidateTier.CONFIRMED_FORMAT,
        classificationEvidence = "test",
        rssi = rssi,
        phoneLatitude = latitude,
        phoneLongitude = longitude,
        phoneAccuracyMeters = accuracy,
        locationProvider = "gps",
        estimatedRangeMeters = estimatedBleRangeMeters(rssi),
        confidenceRadiusMeters = confidenceRadiusMeters(
            rangeMeters = estimatedBleRangeMeters(rssi),
            phoneAccuracyMeters = accuracy,
        ),
    )
}
