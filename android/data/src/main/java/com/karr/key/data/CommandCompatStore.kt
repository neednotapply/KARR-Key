package com.karr.key.data

import android.content.Context

/**
 * A lightweight, per-unit-model record of how each raw command-console command responded on a given
 * model — deliberately kept OUT of the primary device/read database.
 *
 * It drives the console's "hide working" filter (which uses only the accepted commands), and, because
 * it is plain [android.content.SharedPreferences], the full map is readable straight off a debug
 * build with no screenshots:
 *
 * ```
 * adb shell run-as com.karr.key cat \
 *   /data/data/com.karr.key/shared_prefs/karr_key_command_compat.xml
 * ```
 *
 * Each entry is keyed `"<modelCode>:<COMMAND_NAME>:tx=<HEX_OR_DASH>"`. Successful values begin with
 * [OUTCOME_OK] and may include the response command and payload, while failures use `"REJ:0x.."`,
 * [OUTCOME_TIMEOUT], or [OUTCOME_LINK]. Re-testing the same command/payload pair overwrites only that
 * pair, so payload surveys remain intact across screen changes and app restarts.
 */
class CommandCompatStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records the [outcome] the unit gave this exact [commandName] + [payload] combination. */
    fun record(modelCode: Int, commandName: String, payload: ByteArray, outcome: String) {
        preferences.edit().putString(key(modelCode, commandName, payload), outcome).apply()
    }

    /** The commands the unit accepted on [modelCode] — the "confirmed working" set the filter hides. */
    fun workingCommands(modelCode: Int): Set<String> =
        outcomes(modelCode)
            .filterValues(::isAccepted)
            .keys
            .map { it.substringBefore(PAYLOAD_KEY_SEPARATOR) }
            .toSet()

    /** Every recorded command -> outcome for [modelCode]. */
    fun outcomes(modelCode: Int): Map<String, String> {
        val prefix = prefix(modelCode)
        return preferences.all
            .asSequence()
            .filter { (storedKey, _) -> storedKey.startsWith(prefix) }
            .mapNotNull { (storedKey, value) ->
                (value as? String)?.let { storedKey.removePrefix(prefix) to it }
            }
            .toMap()
    }

    /** Forgets everything recorded for [modelCode] so its commands can be re-tested from scratch. */
    fun clear(modelCode: Int) {
        val prefix = prefix(modelCode)
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    private fun prefix(modelCode: Int) = "$modelCode:"

    private fun key(modelCode: Int, commandName: String, payload: ByteArray): String {
        val tx = if (payload.isEmpty()) "-" else payload.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).uppercase().padStart(2, '0')
        }
        return "${prefix(modelCode)}$commandName$PAYLOAD_KEY_SEPARATOR$tx"
    }

    companion object {
        /** Outcome token for a command the unit accepted. */
        const val OUTCOME_OK = "OK"

        /** True for both the legacy `OK` token and detailed `OK rx=...` success records. */
        fun isAccepted(outcome: String): Boolean =
            outcome == OUTCOME_OK || outcome.startsWith("$OUTCOME_OK ")

        /** Outcome token for a command that got no unit reply within the timeout. */
        const val OUTCOME_TIMEOUT = "TIMEOUT"

        /** Outcome token for a command whose BLE write itself failed (inconclusive). */
        const val OUTCOME_LINK = "LINK"

        /** Prefix for a rejection token; the unit's error code follows, e.g. `REJ:0x01`. */
        const val OUTCOME_REJECT_PREFIX = "REJ:"

        private const val PREFS_NAME = "karr_key_command_compat"
        private const val PAYLOAD_KEY_SEPARATOR = ":tx="
    }
}
