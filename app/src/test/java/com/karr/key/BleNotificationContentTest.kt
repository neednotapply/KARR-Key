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
    fun scanningStatusCountsOnlyConnectableConfirmedDevices() {
        val content = bleNotificationContent(
            state = BleSessionState.Scanning,
            devices = listOf(
                device("AA:00", "DR D029A54D", -70, confirmed = true),
                device("AA:01", "QT ABC1234", -45, confirmed = true),
                device("AA:02", "KARR possible", -20, confirmed = false),
            ),
        )

        requireNotNull(content)
        assertEquals("2 compatible devices found", content.text)
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
    fun discoveryAlertsOnlyNewConnectableConfirmedDevices() {
        val devices = listOf(
            device("AA:00", "DR D029A54D", -70, confirmed = true),
            device("AA:01", "QT ABC1234", -45, confirmed = true),
            device("AA:02", "Nearby device", -20, confirmed = false),
        )

        val newlyFound = newlyDiscoveredCompatibleDevices(
            devices = devices,
            alreadyAlertedAddresses = setOf("AA:00"),
        )

        assertEquals(listOf("AA:01"), newlyFound.map(DiscoveredDevice::address))
    }

    private fun device(
        address: String,
        name: String,
        rssi: Int,
        confirmed: Boolean,
    ): DiscoveredDevice = DiscoveredDevice(
        address = address,
        name = name,
        rssi = rssi,
        connectable = true,
        classification = KarrDeviceClassification(
            tier = if (confirmed) {
                KarrCandidateTier.CONFIRMED_FORMAT
            } else {
                KarrCandidateTier.POSSIBLE
            },
            evidence = "test",
        ),
    )
}
