package com.karr.key.ui

import com.karr.key.ble.BleSessionState
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeConnectionTest {
    @Test
    fun missingAccessKeysReplaceIdleOrScanningStatusWithSetupGuidance() {
        val expected = "Enter an access key before connecting to a KARR unit."

        assertEquals(expected, BleSessionState.Idle.homeStatus(accessConfigured = false))
        assertEquals(expected, BleSessionState.Scanning.homeStatus(accessConfigured = false))
        assertEquals("Ready to scan.", BleSessionState.Idle.homeStatus(accessConfigured = true))
    }

    @Test
    fun readyHomeStatusShowsOnlyModelAndMode() {
        assertEquals(
            "DR1 CAN • User mode",
            BleSessionState.Ready(
                address = "00:11:22:33:44:55",
                name = "DR D029A54D",
                unitModel = KarrUnitModel.DR1_CAN,
                unitMode = KarrDeviceMode.USER,
            ).homeStatus(),
        )
        assertEquals(
            "DR1 CAN",
            BleSessionState.Ready(
                address = "00:11:22:33:44:55",
                name = "DR D029A54D",
                unitModel = KarrUnitModel.DR1_CAN,
            ).homeStatus(),
        )
        assertEquals(
            "Model not identified • User mode",
            BleSessionState.Ready(
                address = "00:11:22:33:44:55",
                name = "DR D029A54D",
                unitMode = KarrDeviceMode.USER,
            ).homeStatus(),
        )
    }

    @Test
    fun startsHomeScanOnlyWhenItWillNotInterruptConnectionWork() {
        assertTrue(BleSessionState.Idle.shouldStartHomeScan())
        assertTrue(BleSessionState.Error("test").shouldStartHomeScan())
        assertTrue(
            BleSessionState.ReconnectFailed(
                serial = "DR D029A54D",
                attempts = 5,
                message = "test",
            ).shouldStartHomeScan(),
        )

        assertFalse(BleSessionState.Scanning.shouldStartHomeScan())
        assertFalse(
            BleSessionState.Connecting("00:11:22:33:44:55")
                .shouldStartHomeScan(),
        )
        assertFalse(
            BleSessionState.Reconnecting(
                serial = "DR D029A54D",
                attempt = 2,
                maxAttempts = 5,
                step = "test",
            ).shouldStartHomeScan(),
        )
        assertFalse(
            BleSessionState.Ready(
                address = "00:11:22:33:44:55",
                name = "DR D029A54D",
            ).shouldStartHomeScan(),
        )
    }
}
