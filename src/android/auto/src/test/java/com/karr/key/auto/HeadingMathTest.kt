package com.karr.key.auto

import com.karr.key.auto.map.HeadingMath
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingMathTest {

    @Test
    fun normalizeWrapsIntoZeroTo360() {
        assertEquals(10f, HeadingMath.normalize(370f), EPS)
        assertEquals(350f, HeadingMath.normalize(-10f), EPS)
        assertEquals(0f, HeadingMath.normalize(360f), EPS)
        assertEquals(1f, HeadingMath.normalize(-719f), EPS)
    }

    @Test
    fun shortestDeltaTakesTheShortWayAroundNorth() {
        // 350° -> 10° is +20°, not -340°.
        assertEquals(20f, HeadingMath.shortestDelta(350f, 10f), EPS)
        // 10° -> 350° is -20°.
        assertEquals(-20f, HeadingMath.shortestDelta(10f, 350f), EPS)
        assertEquals(0f, HeadingMath.shortestDelta(90f, 90f), EPS)
    }

    @Test
    fun shortestDeltaAtOppositeIsWellDefined() {
        // 180° apart is the boundary — magnitude must be 180, never 340-ish.
        assertEquals(180f, Math.abs(HeadingMath.shortestDelta(0f, 180f)), EPS)
        assertEquals(180f, Math.abs(HeadingMath.shortestDelta(90f, 270f)), EPS)
    }

    @Test
    fun circularSmoothEasesPartwayAlongShortArc() {
        // Halfway from 350° toward 10° (a +20° arc) lands at 0°, not somewhere near 180°.
        assertEquals(0f, HeadingMath.circularSmooth(350f, 10f, 0.5f), EPS)
    }

    @Test
    fun circularSmoothAlphaZeroHoldsAndOneSnaps() {
        assertEquals(120f, HeadingMath.circularSmooth(120f, 300f, 0f), EPS)
        assertEquals(300f, HeadingMath.circularSmooth(120f, 300f, 1f), EPS)
    }

    @Test
    fun circularSmoothConvergesWithoutOvershooting() {
        var heading = 0f
        repeat(50) { heading = HeadingMath.circularSmooth(heading, 90f, 0.35f) }
        assertEquals(90f, heading, 0.5f)
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
