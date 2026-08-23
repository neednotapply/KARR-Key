package com.karr.key.desktop.data

import java.nio.file.Files
import java.nio.file.Path
import org.json.JSONObject

/**
 * Desktop equivalent of the Android AutoConnectReadPreferences: the auto-connect
 * sweep toggle and the operation it sends on connect. Plaintext JSON (no secrets),
 * mirroring the Android SharedPreferences behaviour including the "read-state"
 * default so upgrades keep doing the same thing.
 */
class DesktopAutoConnectPreferences(
    private val file: Path = AppPaths.file("preferences.json"),
) {
    private val lock = Any()

    var enabled: Boolean
        get() = read().optBoolean(KEY_ENABLED, false)
        set(value) = write { it.put(KEY_ENABLED, value) }

    var operationId: String
        get() = read().optString(KEY_OPERATION_ID, DEFAULT_OPERATION_ID).ifBlank { DEFAULT_OPERATION_ID }
        set(value) = write { it.put(KEY_OPERATION_ID, value) }

    private fun read(): JSONObject = synchronized(lock) {
        if (!Files.exists(file)) return JSONObject()
        runCatching { JSONObject(Files.readString(file)) }.getOrDefault(JSONObject())
    }

    private fun write(mutate: (JSONObject) -> Unit) {
        synchronized(lock) {
            val json = if (Files.exists(file)) {
                runCatching { JSONObject(Files.readString(file)) }.getOrDefault(JSONObject())
            } else {
                JSONObject()
            }
            mutate(json)
            Files.createDirectories(file.parent)
            Files.writeString(file, json.toString())
        }
    }

    private companion object {
        const val KEY_ENABLED = "auto_connect_read_enabled"
        const val KEY_OPERATION_ID = "auto_connect_operation_id"
        const val DEFAULT_OPERATION_ID = "read-state"
    }
}
