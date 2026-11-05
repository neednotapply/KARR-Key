package com.karr.key.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// The StringCipher interface moved to shared:persistence (same package) for
// reuse by the desktop host; this Android Keystore implementation stays here.
class AndroidKeystoreCipher(
    private val alias: String = "karr_key_local_v1",
    dataKeyContext: Context? = null,
) : StringCipher {
    private val dataKeyPrefs: SharedPreferences? =
        dataKeyContext?.applicationContext?.getSharedPreferences(
            DATA_KEY_PREFS,
            Context.MODE_PRIVATE,
        )

    // KeyStore.load() and getKey() are binder calls into the keystore daemon;
    // loading them once per instance instead of per encrypt/decrypt call avoids
    // repeated IPC round-trips when decrypting many stored values in a row.
    private val keyStore: KeyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
    private val secretKey: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadOrCreateSecretKey()
    }
    private val envelope: AesGcmEnvelope? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        dataKeyPrefs?.let { prefs -> AesGcmEnvelope(loadOrCreateDataKey(prefs)) }
    }

    override fun encrypt(plainText: String): String {
        val bytes = plainText.toByteArray(Charsets.UTF_8)
        val stored = envelope?.encrypt(bytes) ?: encryptDirect(bytes)
        return Base64.encodeToString(stored, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String {
        val stored = Base64.decode(cipherText, Base64.NO_WRAP)
        require(stored.isNotEmpty()) { "Encrypted value is empty." }
        val plain = if (AesGcmEnvelope.isEnvelope(stored)) {
            checkNotNull(envelope) {
                "Value uses the data-key format but this cipher has no data key."
            }.decrypt(stored)
        } else {
            decryptDirect(stored)
        }
        return plain.toString(Charsets.UTF_8)
    }

    override fun needsUpgrade(cipherText: String): Boolean {
        if (dataKeyPrefs == null) return false
        val stored = Base64.decode(cipherText, Base64.NO_WRAP)
        return stored.isNotEmpty() && !AesGcmEnvelope.isEnvelope(stored)
    }

    private fun encryptDirect(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encrypted = cipher.doFinal(plain)
        val result = ByteArray(1 + cipher.iv.size + encrypted.size)
        result[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(result, destinationOffset = 1)
        encrypted.copyInto(result, destinationOffset = 1 + cipher.iv.size)
        return result
    }

    private fun decryptDirect(stored: ByteArray): ByteArray {
        val ivSize = stored[0].toInt() and 0xff
        require(ivSize in 12..16 && stored.size > ivSize + 1) {
            "Encrypted value has an invalid IV."
        }
        val iv = stored.copyOfRange(1, 1 + ivSize)
        val encrypted = stored.copyOfRange(1 + ivSize, stored.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun loadOrCreateDataKey(prefs: SharedPreferences): SecretKey {
        val prefKey = "$WRAPPED_PREFIX$alias"
        val wrapped = prefs.getString(prefKey, null)
        if (wrapped != null) {
            // An unreadable wrapped key must fail loudly: generating a replacement would
            // silently orphan everything already encrypted with the original.
            val raw = Base64.decode(
                decryptDirect(Base64.decode(wrapped, Base64.NO_WRAP)).toString(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            return try {
                SecretKeySpec(raw, "AES")
            } finally {
                raw.fill(0)
            }
        }
        val raw = ByteArray(DATA_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val wrappedNew = Base64.encodeToString(
                encryptDirect(Base64.encodeToString(raw, Base64.NO_WRAP).toByteArray()),
                Base64.NO_WRAP,
            )
            // commit(), not apply(): the key must be durable before any value encrypted with it
            // can reach disk, or a crash would leave that data permanently unreadable.
            check(prefs.edit().putString(prefKey, wrappedNew).commit()) {
                "Could not persist the wrapped data key."
            }
            return SecretKeySpec(raw, "AES")
        } finally {
            raw.fill(0)
        }
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
        const val DATA_KEY_PREFS = "karr_key_data_keys"
        const val WRAPPED_PREFIX = "wrapped:"
        const val DATA_KEY_BYTES = 32
    }
}
