package com.karr.key.data

/** Symmetric string encryption the local stores use. Android backs it with the
 * Android Keystore; the desktop host backs it with a file-held AES key. */
interface StringCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String

    /**
     * True when [cipherText] is readable but stored in a slower, superseded format that a store
     * may re-encrypt in place. Ciphers with a single format never need an upgrade.
     */
    fun needsUpgrade(cipherText: String): Boolean = false
}
