package com.karr.key.ble

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

        assertTrue(message.contains("application authentication"))
        assertTrue(message.contains("provisioned key"))
    }

    @Test
    fun explainsCommonAndroidConnectionFailuresWithoutBareStatusCodes() {
        val idle = BleSessionState.Idle
        assertTrue(GattStatus.disconnectMessage(0, idle).contains("closed without an Android error"))
        assertTrue(GattStatus.disconnectMessage(8, idle).contains("stopped responding"))
        assertTrue(GattStatus.disconnectMessage(19, idle).contains("KARR unit closed"))
        assertTrue(GattStatus.disconnectMessage(22, idle).contains("locally"))
        assertTrue(GattStatus.disconnectMessage(62, idle).contains("could not establish"))
        assertTrue(GattStatus.disconnectMessage(133, idle).contains("could not establish or maintain"))
    }

    @Test
    fun explainsGattWriteFailuresInOperationContext() {
        assertTrue(
            GattStatus.operationFailure("UART write", 8)
                .contains("not authorized"),
        )
        assertTrue(
            GattStatus.operationFailure("UART write", 133)
                .contains("lost the active Bluetooth GATT session"),
        )
        assertTrue(
            GattStatus.operationFailure("UART write", 143)
                .contains("congested"),
        )
    }

    @Test
    fun explainsAndroidScanFailures() {
        assertTrue(GattStatus.scanFailure(1).contains("already running"))
        assertTrue(GattStatus.scanFailure(5).contains("no Bluetooth scanning resources"))
        assertTrue(GattStatus.scanFailure(6).contains("limiting Bluetooth scans"))
    }
}
