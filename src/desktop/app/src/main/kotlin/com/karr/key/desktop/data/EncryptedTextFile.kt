package com.karr.key.desktop.data

import com.karr.key.data.StringCipher
import java.nio.file.Files
import java.nio.file.Path

/**
 * A single file whose entire contents are encrypted with [cipher]. The desktop
 * record stores serialize their state to JSON text and persist it through this,
 * so device addresses, payloads, coordinates, and decoded reads are encrypted at
 * rest — the desktop equivalent of the Android stores' per-column encryption.
 */
class EncryptedTextFile(
    private val file: Path,
    private val cipher: StringCipher,
) {
    private val lock = Any()

    fun load(): String? = synchronized(lock) {
        if (!Files.exists(file)) return null
        runCatching { cipher.decrypt(Files.readString(file)) }.getOrNull()
    }

    fun save(text: String) = synchronized(lock) {
        Files.createDirectories(file.parent)
        Files.writeString(file, cipher.encrypt(text))
    }
}
