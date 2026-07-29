package com.karr.master.data

import android.content.Context

interface TokenStore {
    fun put(name: String, value: String)
    fun get(name: String): String?
    fun remove(name: String)
    fun clear()
}

class EncryptedTokenStore(
    context: Context,
    private val cipher: StringCipher = AndroidKeystoreCipher("karr_master_tokens_v1"),
) : TokenStore {
    private val preferences = context.getSharedPreferences(
        "karr_master_secure_values",
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
