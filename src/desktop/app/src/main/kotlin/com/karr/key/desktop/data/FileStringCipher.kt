package com.karr.key.desktop.data

import com.karr.key.data.StringCipher
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop [StringCipher]: AES-256/GCM with a master key held in a per-user file.
 * The ciphertext format matches [com.karr.key.data.AndroidKeystoreCipher] —
 * `[ivLen(1)][iv][ciphertext]`, Base64 — so both hosts encode identically.
 *
 * Parity with the "no key material in source, tests, resources, or release
 * artifacts" invariant: the master key is generated at first run and stored only
 * in the user profile, never in the build. (Hardening note: wrapping this file
 * key with Windows DPAPI would bind it to the OS user; that needs native interop
 * and is a follow-up — see the desktop persistence notes.)
 */
class FileStringCipher(
    keyFile: Path = AppPaths.file("secret.key"),
) : StringCipher {
    private val secretKey: SecretKey = loadOrCreateKey(keyFile)

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val result = ByteArray(1 + iv.size + encrypted.size)
        result[0] = iv.size.toByte()
        iv.copyInto(result, destinationOffset = 1)
        encrypted.copyInto(result, destinationOffset = 1 + iv.size)
        return Base64.getEncoder().encodeToString(result)
    }

    override fun decrypt(cipherText: String): String {
        val stored = Base64.getDecoder().decode(cipherText)
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

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256

        fun loadOrCreateKey(keyFile: Path): SecretKey {
            if (Files.exists(keyFile)) {
                val bytes = Base64.getDecoder().decode(Files.readString(keyFile).trim())
                return SecretKeySpec(bytes, "AES")
            }
            val generator = KeyGenerator.getInstance("AES")
            generator.init(KEY_BITS, SecureRandom())
            val key = generator.generateKey()
            Files.createDirectories(keyFile.parent)
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(key.encoded))
            restrictToOwner(keyFile)
            return key
        }

        /** Best-effort owner-only ACL on Windows so the key file is not world-readable. */
        fun restrictToOwner(path: Path) {
            runCatching {
                val view = Files.getFileAttributeView(
                    path,
                    java.nio.file.attribute.AclFileAttributeView::class.java,
                )
                if (view != null) {
                    val owner = Files.getOwner(path)
                    val entry = java.nio.file.attribute.AclEntry.newBuilder()
                        .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(java.nio.file.attribute.AclEntryPermission.values().toSet())
                        .build()
                    view.acl = listOf(entry)
                }
            }
        }
    }
}
