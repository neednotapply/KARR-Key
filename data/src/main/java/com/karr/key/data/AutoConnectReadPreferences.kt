package com.karr.key.data

import android.content.Context

class AutoConnectReadPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ENABLED, value).apply()

    private companion object {
        const val PREFS_NAME = "karr_key_app_preferences"
        const val KEY_ENABLED = "auto_connect_read_enabled"
    }
}
