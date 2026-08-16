package com.karr.key.data

import android.content.Context

class AutoConnectReadPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Id of the operation the auto-connect sweep sends on connect. Defaults to a state read so an
     * upgrade from the previous read-only behavior keeps doing the same thing. */
    var operationId: String
        get() = preferences.getString(KEY_OPERATION_ID, DEFAULT_OPERATION_ID) ?: DEFAULT_OPERATION_ID
        set(value) = preferences.edit().putString(KEY_OPERATION_ID, value).apply()

    private companion object {
        const val PREFS_NAME = "karr_key_app_preferences"
        const val KEY_ENABLED = "auto_connect_read_enabled"
        const val KEY_OPERATION_ID = "auto_connect_operation_id"
        const val DEFAULT_OPERATION_ID = "read-state"
    }
}
