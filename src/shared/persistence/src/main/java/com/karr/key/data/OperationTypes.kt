package com.karr.key.data

import com.karr.key.core.UserRole
import java.time.Instant

/** Lifecycle of a locally-journaled device operation. Platform-free so both the
 * Android app and the desktop host share one definition and journal format. */
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
