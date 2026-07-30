package com.karr.master.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryFreshnessTest {
    @Test
    fun expiresAdvertisementsAtTenSecondsUsingMonotonicTime() {
        val lastSeen = 50_000L

        assertFalse(
            DiscoveryFreshness.isExpired(
                lastSeenElapsedRealtimeMillis = lastSeen,
                nowElapsedRealtimeMillis =
                    lastSeen + DiscoveryFreshness.MAX_AGE_MILLIS - 1,
            ),
        )
        assertTrue(
            DiscoveryFreshness.isExpired(
                lastSeenElapsedRealtimeMillis = lastSeen,
                nowElapsedRealtimeMillis =
                    lastSeen + DiscoveryFreshness.MAX_AGE_MILLIS,
            ),
        )
    }

    @Test
    fun aNewObservationExtendsTheFreshnessWindow() {
        val now = 75_000L
        val refreshedAt = now - 500L

        assertFalse(
            DiscoveryFreshness.isExpired(
                lastSeenElapsedRealtimeMillis = refreshedAt,
                nowElapsedRealtimeMillis = now,
            ),
        )
    }
}
