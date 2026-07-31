package com.karr.key.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveredActionPreflightTest {
    private val preflight = RecoveredActionPreflight.IGNITION_OFF

    @Test
    fun allowsReadStateWhenIgnitionBitIsClear() {
        assertEquals(
            RecoveredPreflightDecision.Allowed,
            preflight.evaluate(readState(0x00)),
        )
    }

    @Test
    fun deniesReadStateWhenIgnitionBitIsSet() {
        val result = preflight.evaluate(readState(0x40))

        assertTrue(result is RecoveredPreflightDecision.Denied)
        assertTrue((result as RecoveredPreflightDecision.Denied).reason.contains("ignition on"))
    }

    @Test
    fun deniesEmptyReadStatePayload() {
        val result = preflight.evaluate(readState())

        assertTrue(result is RecoveredPreflightDecision.Denied)
        assertTrue((result as RecoveredPreflightDecision.Denied).reason.contains("malformed"))
    }

    @Test
    fun deniesUnexpectedResponseCommand() {
        val result = preflight.evaluate(
            QtPacket(
                mode = ProtocolMode.ORIGINAL,
                commandCode = QtCommand.ACK_OK.code,
                payload = byteArrayOf(),
            ),
        )

        assertTrue(result is RecoveredPreflightDecision.Denied)
        assertTrue((result as RecoveredPreflightDecision.Denied).reason.contains("READ_STATE"))
    }

    private fun readState(vararg payload: Int) = QtPacket(
        mode = ProtocolMode.ORIGINAL,
        commandCode = QtCommand.READ_STATE.code,
        payload = ByteArray(payload.size) { index -> payload[index].toByte() },
    )
}
