package com.karr.key.desktop.data

import com.karr.key.core.UserRole
import com.karr.key.data.LocalOperation
import com.karr.key.data.LocalOperationState
import com.karr.key.data.StringCipher
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Desktop journal of device operations, mirroring the Android LocalOperationStore
 * API. Backed by an encrypted JSON file rather than SQLite.
 */
class DesktopOperationStore(
    cipher: StringCipher = FileStringCipher(),
    file: java.nio.file.Path = AppPaths.file("operations.json"),
) {
    private val store = EncryptedTextFile(file, cipher)
    private val lock = Any()

    fun enqueue(role: UserRole, type: String, scope: String, payload: String): LocalOperation {
        val now = Instant.now()
        val operation = LocalOperation(
            id = UUID.randomUUID().toString(),
            role = role,
            type = type,
            scope = scope,
            payload = payload,
            idempotencyKey = UUID.randomUUID().toString(),
            state = LocalOperationState.PENDING,
            attempts = 0,
            createdUtc = now,
            updatedUtc = now,
            lastError = null,
        )
        synchronized(lock) {
            val all = readAll().toMutableList()
            all.add(operation)
            writeAll(all)
        }
        return operation
    }

    fun pending(limit: Int = 100): List<LocalOperation> = synchronized(lock) {
        readAll()
            .filter {
                it.state == LocalOperationState.PENDING ||
                    it.state == LocalOperationState.FAILED_RETRYABLE
            }
            .sortedBy { it.createdUtc }
            .take(limit)
    }

    fun recent(limit: Int = 100): List<LocalOperation> = synchronized(lock) {
        readAll().sortedByDescending { it.createdUtc }.take(limit)
    }

    fun updateState(id: String, state: LocalOperationState, attempts: Int, lastError: String? = null) {
        synchronized(lock) {
            val all = readAll().toMutableList()
            val index = all.indexOfFirst { it.id == id }
            check(index >= 0) { "Local operation was not found." }
            all[index] = all[index].copy(
                state = state,
                attempts = attempts,
                updatedUtc = Instant.now(),
                lastError = lastError,
            )
            writeAll(all)
        }
    }

    private fun readAll(): List<LocalOperation> {
        val text = store.load() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { array.getJSONObject(index).toOperation() }.getOrNull()
        }
    }

    private fun writeAll(operations: List<LocalOperation>) {
        val array = JSONArray()
        operations.forEach { array.put(it.toJson()) }
        store.save(array.toString())
    }

    private fun LocalOperation.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("role", role.name)
        .put("type", type)
        .put("scope", scope)
        .put("payload", payload)
        .put("idempotencyKey", idempotencyKey)
        .put("state", state.name)
        .put("attempts", attempts)
        .put("createdUtc", createdUtc.toString())
        .put("updatedUtc", updatedUtc.toString())
        .put("lastError", lastError ?: JSONObject.NULL)

    private fun JSONObject.toOperation(): LocalOperation = LocalOperation(
        id = getString("id"),
        role = UserRole.valueOf(getString("role")),
        type = getString("type"),
        scope = getString("scope"),
        payload = getString("payload"),
        idempotencyKey = getString("idempotencyKey"),
        state = LocalOperationState.valueOf(getString("state")),
        attempts = getInt("attempts"),
        createdUtc = Instant.parse(getString("createdUtc")),
        updatedUtc = Instant.parse(getString("updatedUtc")),
        lastError = if (isNull("lastError")) null else getString("lastError"),
    )
}
