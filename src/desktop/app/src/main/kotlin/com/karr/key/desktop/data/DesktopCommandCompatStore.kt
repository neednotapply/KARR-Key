package com.karr.key.desktop.data

import java.nio.file.Files
import java.nio.file.Path
import org.json.JSONObject

/**
 * Desktop equivalent of the Android CommandCompatStore: a per-unit-model record
 * of how each raw command-console command responded, keyed
 * `"<modelCode>:<COMMAND>:tx=<HEX_OR_DASH>"`. Plaintext JSON, mirroring the
 * Android SharedPreferences store, and deliberately kept out of the encrypted
 * device stores.
 */
class DesktopCommandCompatStore(
    private val file: Path = AppPaths.file("command_compat.json"),
) {
    private val lock = Any()

    fun record(modelCode: Int, commandName: String, payload: ByteArray, outcome: String) {
        write { it.put(key(modelCode, commandName, payload), outcome) }
    }

    fun workingCommands(modelCode: Int): Set<String> =
        outcomes(modelCode)
            .filterValues(::isAccepted)
            .keys
            .map { it.substringBefore(PAYLOAD_KEY_SEPARATOR) }
            .toSet()

    fun outcomes(modelCode: Int): Map<String, String> {
        val prefix = prefix(modelCode)
        val json = read()
        return json.keys().asSequence()
            .filter { it.startsWith(prefix) }
            .associate { it.removePrefix(prefix) to json.getString(it) }
    }

    fun clear(modelCode: Int) {
        val prefix = prefix(modelCode)
        write { json ->
            json.keys().asSequence().filter { it.startsWith(prefix) }.toList()
                .forEach(json::remove)
        }
    }

    private fun prefix(modelCode: Int) = "$modelCode:"

    private fun key(modelCode: Int, commandName: String, payload: ByteArray): String {
        val tx = if (payload.isEmpty()) {
            "-"
        } else {
            payload.joinToString("") { (it.toInt() and 0xff).toString(16).uppercase().padStart(2, '0') }
        }
        return "${prefix(modelCode)}$commandName$PAYLOAD_KEY_SEPARATOR$tx"
    }

    private fun read(): JSONObject = synchronized(lock) {
        if (!Files.exists(file)) return JSONObject()
        runCatching { JSONObject(Files.readString(file)) }.getOrDefault(JSONObject())
    }

    private fun write(mutate: (JSONObject) -> Unit) = synchronized(lock) {
        val json = if (Files.exists(file)) {
            runCatching { JSONObject(Files.readString(file)) }.getOrDefault(JSONObject())
        } else {
            JSONObject()
        }
        mutate(json)
        Files.createDirectories(file.parent)
        Files.writeString(file, json.toString())
    }

    companion object {
        const val OUTCOME_OK = "OK"
        const val OUTCOME_TIMEOUT = "TIMEOUT"
        const val OUTCOME_LINK = "LINK"
        const val OUTCOME_REJECT_PREFIX = "REJ:"
        private const val PAYLOAD_KEY_SEPARATOR = ":tx="

        fun isAccepted(outcome: String): Boolean =
            outcome == OUTCOME_OK || outcome.startsWith("$OUTCOME_OK ")
    }
}
