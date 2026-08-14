package com.karr.key.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KarrTraceTest {
    @Test
    fun `hex formats bytes as spaced uppercase`() {
        val bytes = byteArrayOf(0xAA.toByte(), 0x00, 0x02, 0x18, 0x0A, 0xDC.toByte())
        assertEquals("AA 00 02 18 0A DC", KarrTrace.hex(bytes))
    }

    @Test
    fun `hex of empty array is empty`() {
        assertEquals("", KarrTrace.hex(byteArrayOf()))
    }

    @Test
    fun `describeOutgoing redacts an Original-mode PROVIDE_AUTH payload`() {
        // 0xAA marker, command byte 0x0F (PROVIDE_AUTH = 15) at index 3, then the
        // response payload that must never appear in a log.
        val secret = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
        val packet = byteArrayOf(0xAA.toByte(), 0x00, 0x09, 0x0F) + secret + byteArrayOf(0x00)

        val described = KarrTrace.describeOutgoing(packet)

        assertTrue(described.contains("PROVIDE_AUTH"))
        assertTrue(described.contains("redacted"))
        assertFalse("payload bytes leaked into trace", described.contains("11 22 33"))
    }

    @Test
    fun `describeOutgoing redacts a Counter-mode PROVIDE_AUTH payload`() {
        // 0xBB marker, counter at index 1, command byte 0x0F at index 4.
        val secret = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val packet = byteArrayOf(0xBB.toByte(), 0x07, 0x00, 0x05, 0x0F) + secret + byteArrayOf(0x00)

        val described = KarrTrace.describeOutgoing(packet)

        assertTrue(described.contains("redacted"))
        assertFalse(described.contains("11 22 33"))
    }

    @Test
    fun `describeOutgoing shows hex for a non-auth command`() {
        // 0xAA marker, command byte 0x22 (READ_MODE = 34), not a secret.
        val packet = byteArrayOf(0xAA.toByte(), 0x00, 0x01, 0x22, 0xDD.toByte())
        assertEquals("AA 00 01 22 DD", KarrTrace.describeOutgoing(packet))
    }

    @Test
    fun `describeOutgoing handles an empty packet`() {
        assertEquals("(empty)", KarrTrace.describeOutgoing(byteArrayOf()))
    }
}
