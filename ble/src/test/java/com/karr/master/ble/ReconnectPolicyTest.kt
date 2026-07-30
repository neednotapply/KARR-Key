package com.karr.master.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun usesApprovedFiveAttemptBackoff() {
        assertEquals(
            listOf(1_000L, 2_000L, 5_000L, 10_000L, 20_000L),
            ReconnectPolicy.retryDelaysMillis,
        )
        assertEquals(5, ReconnectPolicy.maxAttempts)
    }

    @Test
    fun matchesOnlyTheSameConfirmedConnectableDrQtSerial() {
        val original = device(
            address = "00:00:00:00:00:01",
            name = "DR D029A54D",
            rssi = -88,
        )
        val target = requireNotNull(ReconnectPolicy.targetFor(original))

        assertTrue(
            ReconnectPolicy.matches(
                target,
                device(
                    address = "00:00:00:00:00:99",
                    name = "dr d029a54d",
                    rssi = -40,
                ),
            ),
        )
        assertFalse(
            ReconnectPolicy.matches(
                target,
                device(
                    address = "00:00:00:00:00:02",
                    name = "QT ABC1234",
                    rssi = -20,
                ),
            ),
        )
    }

    @Test
    fun rejectsStrongerDifferentPossibleAndBroadcastOnlyDevices() {
        val target = requireNotNull(
            ReconnectPolicy.targetFor(
                device(
                    address = "00:00:00:00:00:01",
                    name = "QT ABC1234",
                    rssi = -90,
                ),
            ),
        )

        assertFalse(
            ReconnectPolicy.matches(
                target,
                device(
                    address = "00:00:00:00:00:02",
                    name = "DR D029A54D",
                    rssi = -10,
                ),
            ),
        )
        assertFalse(
            ReconnectPolicy.matches(
                target,
                device(
                    address = "00:00:00:00:00:03",
                    name = "QT ABC1234",
                    rssi = -15,
                    tier = KarrCandidateTier.POSSIBLE,
                ),
            ),
        )
        assertFalse(
            ReconnectPolicy.matches(
                target,
                device(
                    address = "00:00:00:00:00:04",
                    name = "QT ABC1234",
                    rssi = -5,
                    connectable = false,
                ),
            ),
        )
        assertNull(
            ReconnectPolicy.targetFor(
                device(
                    address = "00:00:00:00:00:05",
                    name = "0123456789ABC",
                    rssi = -1,
                ),
            ),
        )
    }

    private fun device(
        address: String,
        name: String,
        rssi: Int,
        connectable: Boolean = true,
        tier: KarrCandidateTier = KarrCandidateTier.CONFIRMED_FORMAT,
    ) = DiscoveredDevice(
        address = address,
        name = name,
        rssi = rssi,
        connectable = connectable,
        classification = KarrDeviceClassification(
            tier = tier,
            evidence = "test",
            recoveredName = name,
        ),
    )
}
