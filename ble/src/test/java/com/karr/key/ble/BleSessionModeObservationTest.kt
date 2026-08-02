package com.karr.key.ble

import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.ProtocolMode
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BleSessionModeObservationTest {
    @Test
    fun readySessionUsesLatestReadModeAndModelObservation() {
        val ready = BleSessionState.Ready(
            address = "00:11:22:33:44:55",
            name = "QT000001",
            unitModel = KarrUnitModel.DR1_CAN,
            unitMode = KarrDeviceMode.USER,
        )

        val updated = ready.withReadModeObservation(
            packet(
                QtCommand.READ_MODE,
                byteArrayOf(
                    KarrDeviceMode.DEALER.code.toByte(),
                    KarrUnitModel.QT6_RED.code.toByte(),
                ),
            ),
        ) as BleSessionState.Ready

        assertEquals(KarrDeviceMode.DEALER, updated.unitMode)
        assertEquals(KarrUnitModel.QT6_RED, updated.unitModel)
    }

    @Test
    fun readModeWithoutModelRetainsMostRecentlyObservedModel() {
        val ready = BleSessionState.Ready(
            address = "00:11:22:33:44:55",
            name = "QT000001",
            unitModel = KarrUnitModel.QT6_BLUE,
            unitMode = KarrDeviceMode.USER,
        )

        val updated = ready.withReadModeObservation(
            packet(QtCommand.READ_MODE, byteArrayOf(KarrDeviceMode.VALET.code.toByte())),
        ) as BleSessionState.Ready

        assertEquals(KarrDeviceMode.VALET, updated.unitMode)
        assertEquals(KarrUnitModel.QT6_BLUE, updated.unitModel)
    }

    @Test
    fun nonModeMalformedAndNonReadyPacketsDoNotChangeSession() {
        val ready = BleSessionState.Ready(
            address = "00:11:22:33:44:55",
            name = "QT000001",
            unitModel = KarrUnitModel.QT6_BLUE,
            unitMode = KarrDeviceMode.USER,
        )
        val idle = BleSessionState.Idle

        assertSame(
            ready,
            ready.withReadModeObservation(packet(QtCommand.READ_STATE, byteArrayOf(5))),
        )
        assertSame(
            ready,
            ready.withReadModeObservation(packet(QtCommand.READ_MODE, byteArrayOf())),
        )
        assertSame(
            idle,
            idle.withReadModeObservation(
                packet(QtCommand.READ_MODE, byteArrayOf(KarrDeviceMode.VALET.code.toByte())),
            ),
        )
    }

    private fun packet(command: QtCommand, payload: ByteArray) = QtPacket(
        mode = ProtocolMode.ORIGINAL,
        commandCode = command.code,
        payload = payload,
    )
}
