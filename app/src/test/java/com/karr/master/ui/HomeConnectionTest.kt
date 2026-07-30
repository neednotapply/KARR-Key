package com.karr.master.ui

import com.karr.master.ble.BleSessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeConnectionTest {
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
