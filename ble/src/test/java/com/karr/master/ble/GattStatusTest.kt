package com.karr.master.ble

import org.junit.Assert.assertTrue
import org.junit.Test

class GattStatusTest {
    @Test
    fun explainsPeerTerminationAfterAuthenticationChallenge() {
        val message = GattStatus.disconnectMessage(
            status = GattStatus.PEER_TERMINATED,
            previousState = BleSessionState.AuthenticationRequired(
                address = "00:11:22:33:44:55",
                name = "DR 0F9ACB56",
                challengeBytes = 8,
            ),
        )

        assertTrue(message.contains("authentication timed out"))
        assertTrue(message.contains("status 19"))
    }

    @Test
    fun distinguishesCommonAndroidGattFailures() {
        val idle = BleSessionState.Idle
        assertTrue(GattStatus.disconnectMessage(8, idle).contains("timed out"))
        assertTrue(GattStatus.disconnectMessage(19, idle).contains("peripheral ended"))
        assertTrue(GattStatus.disconnectMessage(22, idle).contains("locally"))
        assertTrue(GattStatus.disconnectMessage(62, idle).contains("could not be established"))
        assertTrue(GattStatus.disconnectMessage(133, idle).contains("generic"))
    }
}
