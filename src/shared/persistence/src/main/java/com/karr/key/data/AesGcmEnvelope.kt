package com.karr.key.data

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Software AES-GCM codec for the "data key" storage format.
 *
 * Every Android Keystore operation is a binder call (~2-3 ms), which made loading a map history
 * of ~1,000 rows (6 encrypted fields each) take ~16 s. In this format a random data key is
 * protected once by the Keystore key, and values are then encrypted with that data key in-process.
 *
 * Wire format: `[MARKER][12-byte IV][ciphertext + 16-byte GCM tag]`. The legacy Keystore-direct
 * format begins with its IV length (12..16), so [MARKER] can never be mistaken for it.
 */
class AesGcmEnvelope(private val key: SecretKey) {
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected GCM IV length ${iv.size}." }
        val encrypted = cipher.doFinal(plain)
        val result = ByteArray(1 + IV_BYTES + encrypted.size)
        result[0] = MARKER
        iv.copyInto(result, destinationOffset = 1)
        encrypted.copyInto(result, destinationOffset = 1 + IV_BYTES)
        return result
    }

    fun decrypt(stored: ByteArray): ByteArray {
        require(isEnvelope(stored) && stored.size > 1 + IV_BYTES) {
            "Value is not in the data-key format."
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, stored, 1, IV_BYTES),
        )
        return cipher.doFinal(stored, 1 + IV_BYTES, stored.size - 1 - IV_BYTES)
    }

    companion object {
        const val MARKER: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun isEnvelope(stored: ByteArray): Boolean = stored.isNotEmpty() && stored[0] == MARKER
    }
}
