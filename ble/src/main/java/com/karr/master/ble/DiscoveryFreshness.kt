package com.karr.master.ble

internal object DiscoveryFreshness {
    const val MAX_AGE_MILLIS = 10_000L
    const val SWEEP_INTERVAL_MILLIS = 1_000L

    fun isExpired(
        lastSeenElapsedRealtimeMillis: Long,
        nowElapsedRealtimeMillis: Long,
    ): Boolean =
        nowElapsedRealtimeMillis - lastSeenElapsedRealtimeMillis >= MAX_AGE_MILLIS
}
