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
 * Each entry is keyed `"<modelCode>:<COMMAND_NAME>"` with an outcome token as its value — [OUTCOME_OK]
 * when the unit accepted it, `"REJ:0x.."` when it was rejected (the code says why), [OUTCOME_TIMEOUT]
 * when there was no reply, or [OUTCOME_LINK] on a transport failure. Re-testing overwrites the entry,
 * so the file always reflects the latest result and survives leaving the screen / restarting the app.
 */
class CommandCompatStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records the [outcome] token the unit gave [commandName] on the model coded [modelCode]. */
    fun record(modelCode: Int, commandName: String, outcome: String) {
        preferences.edit().putString(key(modelCode, commandName), outcome).apply()
    }

    /** The commands the unit accepted on [modelCode] — the "confirmed working" set the filter hides. */
    fun workingCommands(modelCode: Int): Set<String> =
        outcomes(modelCode).filterValues { it == OUTCOME_OK }.keys

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

    private fun key(modelCode: Int, commandName: String) = "${prefix(modelCode)}$commandName"

    companion object {
        /** Outcome token for a command the unit accepted. */
        const val OUTCOME_OK = "OK"

        /** Outcome token for a command that got no unit reply within the timeout. */
        const val OUTCOME_TIMEOUT = "TIMEOUT"

        /** Outcome token for a command whose BLE write itself failed (inconclusive). */
        const val OUTCOME_LINK = "LINK"

        /** Prefix for a rejection token; the unit's error code follows, e.g. `REJ:0x01`. */
        const val OUTCOME_REJECT_PREFIX = "REJ:"

        private const val PREFS_NAME = "karr_key_command_compat"
    }
}
