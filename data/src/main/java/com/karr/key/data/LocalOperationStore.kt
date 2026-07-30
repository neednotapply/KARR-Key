package com.karr.key.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.karr.key.core.UserRole
import java.time.Instant
import java.util.UUID

enum class LocalOperationState {
    PENDING,
    IN_FLIGHT,
    DELIVERED_TO_GATT,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
}

data class LocalOperation(
    val id: String,
    val role: UserRole,
    val type: String,
    val scope: String,
    val payload: String,
    val idempotencyKey: String,
    val state: LocalOperationState,
    val attempts: Int,
    val createdUtc: Instant,
    val updatedUtc: Instant,
    val lastError: String?,
)

class LocalOperationStore(
    context: Context,
    private val cipher: StringCipher = AndroidKeystoreCipher("karr_key_queue_v1"),
) : SQLiteOpenHelper(context, "karr_key_operations.db", null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE operations (
                id TEXT PRIMARY KEY NOT NULL,
                role TEXT NOT NULL,
                type TEXT NOT NULL,
                encrypted_scope TEXT NOT NULL,
                encrypted_payload TEXT NOT NULL,
                idempotency_key TEXT NOT NULL UNIQUE,
                state TEXT NOT NULL,
                attempts INTEGER NOT NULL,
                created_utc TEXT NOT NULL,
                updated_utc TEXT NOT NULL,
                encrypted_last_error TEXT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX operations_state_idx ON operations(state, created_utc)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == newVersion) {
            "No destructive local-operation migration is defined."
        }
    }

    fun enqueue(
        role: UserRole,
        type: String,
        scope: String,
        payload: String,
    ): LocalOperation {
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
        writableDatabase.insertOrThrow("operations", null, operation.toValues())
        return operation
    }

    fun pending(limit: Int = 100): List<LocalOperation> {
        require(limit in 1..500)
        return query(
            selection = "state IN (?, ?)",
            selectionArgs = arrayOf(
                LocalOperationState.PENDING.name,
                LocalOperationState.FAILED_RETRYABLE.name,
            ),
            orderBy = "created_utc ASC",
            limit = limit,
        )
    }

    fun recent(limit: Int = 100): List<LocalOperation> {
        require(limit in 1..500)
        return query(
            selection = null,
            selectionArgs = null,
            orderBy = "created_utc DESC",
            limit = limit,
        )
    }

    private fun query(
        selection: String?,
        selectionArgs: Array<String>?,
        orderBy: String,
        limit: Int,
    ): List<LocalOperation> =
        readableDatabase.query(
            "operations",
            COLUMNS,
            selection,
            selectionArgs,
            null,
            null,
            orderBy,
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LocalOperation(
                            id = cursor.getString(0),
                            role = UserRole.valueOf(cursor.getString(1)),
                            type = cursor.getString(2),
                            scope = cipher.decrypt(cursor.getString(3)),
                            payload = cipher.decrypt(cursor.getString(4)),
                            idempotencyKey = cursor.getString(5),
                            state = LocalOperationState.valueOf(cursor.getString(6)),
                            attempts = cursor.getInt(7),
                            createdUtc = Instant.parse(cursor.getString(8)),
                            updatedUtc = Instant.parse(cursor.getString(9)),
                            lastError = cursor.getString(10)?.let(cipher::decrypt),
                        ),
                    )
                }
            }
        }

    fun updateState(
        id: String,
        state: LocalOperationState,
        attempts: Int,
        lastError: String? = null,
    ) {
        val values = ContentValues().apply {
            put("state", state.name)
            put("attempts", attempts)
            put("updated_utc", Instant.now().toString())
            if (lastError == null) {
                putNull("encrypted_last_error")
            } else {
                put("encrypted_last_error", cipher.encrypt(lastError))
            }
        }
        val changed = writableDatabase.update(
            "operations",
            values,
            "id = ?",
            arrayOf(id),
        )
        check(changed == 1) { "Local operation was not found." }
    }

    private fun LocalOperation.toValues() = ContentValues().apply {
        put("id", id)
        put("role", role.name)
        put("type", type)
        put("encrypted_scope", cipher.encrypt(scope))
        put("encrypted_payload", cipher.encrypt(payload))
        put("idempotency_key", idempotencyKey)
        put("state", state.name)
        put("attempts", attempts)
        put("created_utc", createdUtc.toString())
        put("updated_utc", updatedUtc.toString())
        putNull("encrypted_last_error")
    }

    private companion object {
        const val DATABASE_VERSION = 1
        val COLUMNS = arrayOf(
            "id",
            "role",
            "type",
            "encrypted_scope",
            "encrypted_payload",
            "idempotency_key",
            "state",
            "attempts",
            "created_utc",
            "updated_utc",
            "encrypted_last_error",
        )
    }
}
