package com.karr.key.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QtErrorCodeTest {
    @Test
    fun decodesInvalidModeFromObservedAckError() {
        val packet = QtPacket(
            mode = ProtocolMode.ORIGINAL,
            commandCode = QtCommand.ACK_ERROR.code,
            payload = byteArrayOf(0x03),
        )

        assertEquals(QtErrorCode.INVALID_MODE, QtErrorCode.fromAckError(packet))
    }

    @Test
    fun rejectsNonErrorAndMalformedPackets() {
        assertNull(
            QtErrorCode.fromAckError(
                QtPacket(
                    mode = ProtocolMode.ORIGINAL,
                    commandCode = QtCommand.ACK_OK.code,
                    payload = byteArrayOf(0x03),
                ),
            ),
        )
        assertNull(
            QtErrorCode.fromAckError(
                QtPacket(
                    mode = ProtocolMode.ORIGINAL,
                    commandCode = QtCommand.ACK_ERROR.code,
                    payload = byteArrayOf(),
                ),
            ),
        )
    }
}
