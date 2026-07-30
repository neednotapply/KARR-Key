package com.karr.key.data

import android.content.Context

interface SecureValueStore {
    fun put(name: String, value: String)
    fun get(name: String): String?
    fun remove(name: String)
    fun clear()
}

class EncryptedLocalValueStore(
    context: Context,
    private val cipher: StringCipher = AndroidKeystoreCipher("karr_key_local_values_v1"),
) : SecureValueStore {
    private val preferences = context.getSharedPreferences(
        "karr_key_secure_values",
        Context.MODE_PRIVATE,
    )

    override fun put(name: String, value: String) {
        preferences.edit().putString(name, cipher.encrypt(value)).apply()
    }

    override fun get(name: String): String? =
        preferences.getString(name, null)?.let(cipher::decrypt)

    override fun remove(name: String) {
        preferences.edit().remove(name).apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }
}
