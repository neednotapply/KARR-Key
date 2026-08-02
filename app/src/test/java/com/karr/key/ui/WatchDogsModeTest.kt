package com.karr.key.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDogsModeTest {
    @Test
    fun countsDownAndActivatesOnTheSeventhTap() {
        var tapCount = 0

        val progress = (1..7).map {
            advanceWatchDogsMode(tapCount).also { tap ->
                tapCount = tap.nextTapCount
            }
        }

        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), progress.map { it.remainingTaps })
        assertTrue(progress.take(6).all { !it.activated })
        assertTrue(progress.last().activated)
        assertEquals(0, tapCount)
    }

    @Test
    fun usesAndroidStyleCountdownMessages() {
        val firstTap = advanceWatchDogsMode(0)
        val sixthTap = advanceWatchDogsMode(5)
        val seventhTap = advanceWatchDogsMode(6)

        assertEquals("You are now 6 taps away from Watch Dogs mode.", firstTap.message)
        assertEquals("You are now 1 tap away from Watch Dogs mode.", sixthTap.message)
        assertEquals("Watch Dogs mode enabled.", seventhTap.message)
        assertFalse(firstTap.activated)
        assertTrue(seventhTap.activated)
        assertEquals("https://fuckyou.gay/?KARR-Key", WATCH_DOGS_MODE_URL)
    }
}
