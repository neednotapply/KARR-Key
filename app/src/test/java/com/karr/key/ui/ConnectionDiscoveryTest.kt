package com.karr.key.ui

import com.karr.key.ble.DiscoveredDevice
import com.karr.key.ble.KarrCandidateTier
import com.karr.key.ble.KarrDeviceClassification
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionDiscoveryTest {
    @Test
    fun connectionListKeepsPossibleMatchesVisibleAndSortsByStrongestRssi() {
        val devices = listOf(
            device(
                address = "00:00:00:00:00:01",
                name = "DR WEAKER01",
                rssi = -88,
                tier = KarrCandidateTier.CONFIRMED_FORMAT,
            ),
            device(
                address = "00:00:00:00:00:02",
                name = "KARR POSSIBLE",
                rssi = -30,
                tier = KarrCandidateTier.POSSIBLE,
            ),
            device(
                address = "00:00:00:00:00:03",
                name = "QT STRONG01",
                rssi = -51,
                tier = KarrCandidateTier.CONFIRMED_FORMAT,
            ),
            device(
                address = "00:00:00:00:00:04",
                name = "Nearby speaker",
                rssi = -20,
                tier = KarrCandidateTier.OTHER,
            ),
        )

        val confirmed = confirmedDevicesForConnection(devices)
        val possible = possibleDevicesForConnection(devices)
        val other = otherDevicesForConnection(devices)
        assertEquals(
            listOf(
                "00:00:00:00:00:03",
                "00:00:00:00:00:01",
            ),
            confirmed.map { it.address },
        )
        assertEquals(
            listOf("00:00:00:00:00:02"),
            possible.map { it.address },
        )
        assertEquals(
            listOf("00:00:00:00:00:04"),
            other.map { it.address },
        )
        assertEquals(
            "2 confirmed / 1 possible KARR / 4 nearby",
            scanCountLabel(
                confirmedCount = confirmed.size,
                possibleCount = possible.size,
                nearbyCount = devices.size,
            ),
        )
    }

    private fun device(
        address: String,
        name: String,
        rssi: Int,
        tier: KarrCandidateTier,
    ): DiscoveredDevice = DiscoveredDevice(
        address = address,
        name = name,
        rssi = rssi,
        connectable = true,
        classification = KarrDeviceClassification(
            tier = tier,
            evidence = "test",
        ),
    )
}
