package com.karr.key.data

/** Encrypted key/value storage contract shared by every host. Android backs it
 * with EncryptedSharedPreferences-style storage; the desktop host with an
 * encrypted file under the per-user profile directory. */
interface SecureValueStore {
    fun put(name: String, value: String)
    fun get(name: String): String?
    fun remove(name: String)
    fun clear()
}
