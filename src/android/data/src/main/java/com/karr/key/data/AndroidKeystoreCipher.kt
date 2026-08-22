package com.karr.key.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface StringCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

class AndroidKeystoreCipher(
    private val alias: String = "karr_key_local_v1",
) : StringCipher {
    // KeyStore.load() and getKey() are binder calls into the keystore daemon;
    // loading them once per instance instead of per encrypt/decrypt call avoids
    // repeated IPC round-trips when decrypting many stored values in a row.
    private val keyStore: KeyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
    private val secretKey: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadOrCreateSecretKey()
    }

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val result = ByteArray(1 + cipher.iv.size + encrypted.size)
        result[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(result, destinationOffset = 1)
        encrypted.copyInto(result, destinationOffset = 1 + cipher.iv.size)
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String {
        val stored = Base64.decode(cipherText, Base64.NO_WRAP)
        require(stored.isNotEmpty()) { "Encrypted value is empty." }
        val ivSize = stored[0].toInt() and 0xff
        require(ivSize in 12..16 && stored.size > ivSize + 1) {
            "Encrypted value has an invalid IV."
        }
        val iv = stored.copyOfRange(1, 1 + ivSize)
        val encrypted = stored.copyOfRange(1 + ivSize, stored.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun loadOrCreateSecretKey(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
