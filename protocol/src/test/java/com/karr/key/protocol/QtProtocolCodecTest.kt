package com.karr.key.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QtProtocolCodecTest {
    @Test
    fun parsesCapturedVehicleAuthenticationChallenges() {
        listOf(
            "AA 00 09 0E D9 5A BC 77 A9 87 3A 8F 8A",
            "AA 00 09 0E D5 63 08 B0 DA FE 42 52 8D",
        ).forEach { capture ->
            val bytes = capture
                .split(" ")
                .map { it.toInt(16).toByte() }
                .toByteArray()
            val parsed = QtProtocolCodec.parse(bytes)

            assertTrue(parsed is PacketParseResult.Success)
            parsed as PacketParseResult.Success
            assertEquals(QtCommand.REQUEST_AUTH, parsed.packet.command)
            assertEquals(8, parsed.packet.payload.size)
        }
    }

    @Test
    fun originalAckMatchesRecoveredWireFormat() {
        assertArrayEquals(
            bytes(0xAA, 0x00, 0x01, 0x00, 0xFF),
            QtProtocolCodec.generateOriginal(QtCommand.ACK_OK),
        )
    }

    @Test
    fun originalPayloadMatchesRecoveredChecksumRule() {
        val packet = QtProtocolCodec.generateOriginal(
            QtCommand.LOCK_DOORS,
            bytes(0x01, 0x02),
        )

        assertArrayEquals(bytes(0xAA, 0x00, 0x03, 0x0B, 0x01, 0x02, 0xEF), packet)
        val parsed = QtProtocolCodec.parse(packet) as PacketParseResult.Success
        assertEquals(QtCommand.LOCK_DOORS, parsed.packet.command)
        assertArrayEquals(bytes(0x01, 0x02), parsed.packet.payload)
    }

    @Test
    fun counterPacketRoundTrips() {
        val packet = QtProtocolCodec.generateCounter(
            QtCommand.UNLOCK_DOORS,
            bytes(0x7F),
            counter = 5,
        )

        assertArrayEquals(bytes(0xBB, 0x05, 0x00, 0x02, 0x0C, 0x7F, 0x6E), packet)
        val parsed = QtProtocolCodec.parse(packet) as PacketParseResult.Success
        assertEquals(ProtocolMode.COUNTER, parsed.packet.mode)
        assertEquals(5, parsed.packet.counter)
        assertEquals(QtCommand.UNLOCK_DOORS, parsed.packet.command)
    }

    @Test
    fun encryptedPacketRoundTripsWithDeterministicIv() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (15 - it).toByte() }
        val packet = QtProtocolCodec.generateEncrypted(
            command = QtCommand.READ_STATE,
            payload = bytes(0x10, 0x20, 0x30),
            counter = 42,
            key = key,
            iv = iv,
        )

        assertEquals(0x55, packet[0].toInt() and 0xff)
        assertArrayEquals(iv, packet.copyOfRange(1, 17))
        val parsed = QtProtocolCodec.parse(packet, key) as PacketParseResult.Success
        assertEquals(ProtocolMode.ENCRYPTED, parsed.packet.mode)
        assertEquals(42, parsed.packet.counter)
        assertEquals(QtCommand.READ_STATE, parsed.packet.command)
        assertArrayEquals(bytes(0x10, 0x20, 0x30), parsed.packet.payload)
    }

    @Test
    fun rejectsBadChecksum() {
        val packet = QtProtocolCodec.generateOriginal(QtCommand.READ_MODE)
        packet[packet.lastIndex] = 0

        val result = QtProtocolCodec.parse(packet)

        assertTrue(result is PacketParseResult.Failure)
    }

    @Test
    fun rejectsTruncatedFrame() {
        val packet = QtProtocolCodec.generateCounter(
            QtCommand.READ_SERIAL,
            counter = 1,
        ).copyOf(4)

        assertTrue(QtProtocolCodec.parse(packet) is PacketParseResult.Failure)
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }
}
