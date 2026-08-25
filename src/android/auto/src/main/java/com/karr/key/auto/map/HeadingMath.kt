package com.karr.key.auto.map

/**
 * Angle math for the map's heading-up rotation. Raw GPS course-over-ground jumps between fixes, so
 * the renderer eases toward each new bearing instead of snapping — and easing an angle has to go
 * the short way around the circle (350° → 10° is +20°, not −340°). Pure and side-effect free so the
 * wraparound cases are unit-tested; the "feel" still has to be judged in a moving car.
 */
internal object HeadingMath {

    /** Wrap [deg] into [0, 360). */
    fun normalize(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    /** Signed shortest angular difference from [fromDeg] to [toDeg], in (−180, 180]. */
    fun shortestDelta(fromDeg: Float, toDeg: Float): Float {
        var d = (toDeg - fromDeg) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /**
     * Exponentially ease [currentDeg] toward [targetDeg] along the shortest arc. [alpha] is the
     * fraction of the gap closed per step (0 = frozen, 1 = snap straight to target).
     */
    fun circularSmooth(currentDeg: Float, targetDeg: Float, alpha: Float): Float =
        normalize(currentDeg + alpha * shortestDelta(currentDeg, targetDeg))
}
