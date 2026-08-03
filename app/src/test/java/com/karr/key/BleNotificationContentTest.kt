package com.karr.key

import com.karr.key.ble.BleSessionState
import com.karr.key.ble.DiscoveredDevice
import com.karr.key.ble.KarrCandidateTier
import com.karr.key.ble.KarrDeviceClassification
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleNotificationContentTest {
    @Test
    fun scanningStatusCountsConnectableConfirmedAndPossibleKarrUnits() {
        val content = bleNotificationContent(
            state = BleSessionState.Scanning,
            devices = listOf(
                device("AA:00", "DR D029A54D", -70, KarrCandidateTier.CONFIRMED_FORMAT),
                device("AA:01", "QT ABC1234", -45, KarrCandidateTier.CONFIRMED_FORMAT),
                device("AA:02", "KARR possible", -20, KarrCandidateTier.POSSIBLE),
            ),
        )

        requireNotNull(content)
        assertEquals("3 KARR units found", content.title)
        assertEquals("KARR possible | -20 dBm", content.text)
        assertTrue(content.expandedText.contains("Address: AA:02"))
        assertTrue(content.expandedText.contains("Signal: -20 dBm"))
        assertTrue(content.expandedText.contains("Scanning continues"))
        assertEquals(BleNotificationAction.STOP_SCAN, content.action)
        assertTrue(content.showProgress)
    }

    @Test
    fun readyStatusIncludesCurrentDeviceModelAddressAndAuthentication() {
        val content = bleNotificationContent(
            state = BleSessionState.Ready(
                address = "00:11:22:33:44:55",
                name = "DR D029A54D",
                unitModel = KarrUnitModel.DR1_CAN,
                unitMode = KarrDeviceMode.USER,
            ),
            devices = emptyList(),
        )

        requireNotNull(content)
        assertEquals("Connected to DR D029A54D", content.title)
        assertTrue(content.text.contains("DR1 CAN"))
        assertTrue(content.text.contains("User mode"))
        assertTrue(content.expandedText.contains("00:11:22:33:44:55"))
        assertTrue(content.expandedText.contains("Operating mode: User mode"))
        assertTrue(content.expandedText.contains("Authenticated"))
        assertEquals(BleNotificationAction.DISCONNECT, content.action)
        assertFalse(content.showProgress)
    }

    @Test
    fun inactiveStatesDoNotKeepForegroundNotification() {
        assertNull(bleNotificationContent(BleSessionState.Idle, emptyList()))
        assertNull(
            bleNotificationContent(
                BleSessionState.Error("Bluetooth is off"),
                emptyList(),
            ),
        )
        assertNull(
            bleNotificationContent(
                BleSessionState.ReconnectFailed(
                    serial = "DR D029A54D",
                    attempts = 3,
                    message = "Out of range",
                ),
                emptyList(),
            ),
        )
    }

    @Test
    fun scanningStatusRetainsSearchMessageUntilAKarrSignalIsFound() {
        val content = bleNotificationContent(
            state = BleSessionState.Scanning,
            devices = listOf(
                device("AA:02", "Nearby device", -20, KarrCandidateTier.OTHER),
            ),
        )

        requireNotNull(content)
        assertEquals("KARR scan active", content.title)
        assertEquals("No KARR signals found yet", content.text)
        assertTrue(content.expandedText.contains("connectable confirmed or possible"))
        assertEquals(BleNotificationAction.STOP_SCAN, content.action)
        assertTrue(content.showProgress)
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
