package com.karr.key.ui

import com.karr.key.ble.BleSessionState
import com.karr.key.ble.DiscoveredDevice
import com.karr.key.ble.KarrCandidateTier
import com.karr.key.ble.KarrDeviceClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionDiscoveryTest {
    @Test
    fun scanningStatusKeepsDiscoveryCountsInTheResultsHeader() {
        val label = BleSessionState.Scanning.connectionStatusLabel()

        assertEquals("Scanning", label)
        assertFalse(label.contains("confirmed"))
        assertFalse(label.contains("possible"))
        assertFalse(label.contains("nearby"))
    }

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

        val karr = karrDevicesForConnection(devices)
        val other = otherDevicesForConnection(devices)
        assertEquals(
            listOf(
                "00:00:00:00:00:02",
                "00:00:00:00:00:03",
                "00:00:00:00:00:01",
            ),
            karr.map { it.address },
        )
        assertEquals(
            listOf("00:00:00:00:00:04"),
            other.map { it.address },
        )
        assertEquals(
            "3 KARR units / 4 nearby",
            scanCountLabel(
                karrUnitCount = karr.size,
                nearbyCount = devices.size,
            ),
        )
        assertEquals(
            "Possible KARR match: test",
            karr.first().classificationDescription(),
        )
        assertEquals("Probe connection", karr.first().connectionActionLabel())
        assertEquals("1 KARR unit / 4 nearby", scanCountLabel(1, devices.size))
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
