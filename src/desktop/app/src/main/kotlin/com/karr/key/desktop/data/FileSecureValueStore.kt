package com.karr.key.desktop.data

import com.karr.key.data.SecureValueStore
import com.karr.key.data.StringCipher
import java.nio.file.Files
import java.nio.file.Path
import org.json.JSONObject

/**
 * Desktop [SecureValueStore]: a JSON map persisted to the per-user profile, with
 * each value encrypted by [cipher]. Mirrors the Android EncryptedLocalValueStore
 * (plaintext names, encrypted values), so [com.karr.key.data.EncryptedDeviceKeyStore]
 * layers on top of it unchanged.
 */
class FileSecureValueStore(
    private val cipher: StringCipher = FileStringCipher(),
    private val file: Path = AppPaths.file("secure_values.json"),
) : SecureValueStore {
    private val lock = Any()

    override fun put(name: String, value: String) = synchronized(lock) {
        val json = load()
        json.put(name, cipher.encrypt(value))
        save(json)
    }

    override fun get(name: String): String? = synchronized(lock) {
        val json = load()
        if (!json.has(name)) return null
        runCatching { cipher.decrypt(json.getString(name)) }.getOrNull()
    }

    override fun remove(name: String) = synchronized(lock) {
        val json = load()
        json.remove(name)
        save(json)
    }

    override fun clear() = synchronized(lock) {
        save(JSONObject())
    }

    private fun load(): JSONObject =
        if (Files.exists(file)) {
            runCatching { JSONObject(Files.readString(file)) }.getOrDefault(JSONObject())
        } else {
            JSONObject()
        }

    private fun save(json: JSONObject) {
        Files.createDirectories(file.parent)
        Files.writeString(file, json.toString())
    }
}
