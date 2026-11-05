package com.karr.key.data

import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AesGcmEnvelopeTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun roundTripsAndMarksFormat() {
        val envelope = AesGcmEnvelope(key)
        val plain = "37.774929".toByteArray()

        val stored = envelope.encrypt(plain)

        assertTrue(AesGcmEnvelope.isEnvelope(stored))
        assertEquals(AesGcmEnvelope.MARKER, stored[0])
        assertArrayEquals(plain, envelope.decrypt(stored))
    }

    @Test
    fun encryptionIsRandomized() {
        val envelope = AesGcmEnvelope(key)
        val plain = "same".toByteArray()
        assertFalse(envelope.encrypt(plain).contentEquals(envelope.encrypt(plain)))
    }

    @Test
    fun handlesEmptyPlaintext() {
        val envelope = AesGcmEnvelope(key)
        assertEquals(0, envelope.decrypt(envelope.encrypt(ByteArray(0))).size)
    }

    @Test
    fun legacyIvLengthsAreNeverTreatedAsEnvelope() {
        // Legacy Keystore-direct values start with their IV length (12..16).
        for (ivLength in 12..16) {
            assertFalse(AesGcmEnvelope.isEnvelope(byteArrayOf(ivLength.toByte(), 0, 0)))
        }
        assertFalse(AesGcmEnvelope.isEnvelope(ByteArray(0)))
        assertNotEquals(12.toByte(), AesGcmEnvelope.MARKER)
    }

    @Test
    fun tamperedOrWrongKeyFailsClosed() {
        val envelope = AesGcmEnvelope(key)
        val stored = envelope.encrypt("secret".toByteArray())

        val tampered = stored.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            envelope.decrypt(tampered)
            fail("Tampered value must not decrypt.")
        } catch (_: GeneralSecurityException) {
        }

        val otherKey = AesGcmEnvelope(SecretKeySpec(ByteArray(32) { 9 }, "AES"))
        try {
            otherKey.decrypt(stored)
            fail("Wrong key must not decrypt.")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun rejectsNonEnvelopeInput() {
        val envelope = AesGcmEnvelope(key)
        try {
            envelope.decrypt(byteArrayOf(12, 1, 2, 3))
            fail("Legacy-format bytes must be rejected.")
        } catch (_: IllegalArgumentException) {
        }
    }
}
