package com.astroloop.game.hangar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch sequence moves the world by the same amount whichever way the device is held (owner,
 * 2026-09-29). Its streaks and ship are fixed in size, so a speed that scaled with the screen's
 * height made a rotated launch look like long, slow streaks.
 */
class LaunchSpanTest {

    // A Pixel 9 Pro's design space.
    private val w = 960f
    private val h = 2142f

    @Test
    fun `the span is the same upright and rotated`() {
        assertEquals(HangarRenderer.launchSpan(w, h), HangarRenderer.launchSpan(h, w), 0f)
    }

    @Test
    fun `upright the span is the screen height, so portrait is unchanged`() {
        assertEquals(h, HangarRenderer.launchSpan(w, h), 0f)
    }

    @Test
    fun `streaks scroll the same distance either way up`() {
        for (phase in listOf(0.1f, 0.5f, 0.67f, 0.8f, 1f)) {
            assertEquals(
                HangarRenderer.hyperStreakOffset(phase, 0.67f, HangarRenderer.launchSpan(w, h)),
                HangarRenderer.hyperStreakOffset(phase, 0.67f, HangarRenderer.launchSpan(h, w)),
                0f
            )
        }
    }

    @Test
    fun `the old portrait scroll is reproduced exactly`() {
        // The formula as shipped, with screenHeight = h.
        fun shipped(p: Float): Float {
            val travel = p.coerceAtMost(0.67f) * h * 0.6f
            if (p < 0.67f) return travel
            val a = (p - 0.67f) / (1f - 0.67f)
            return travel + (a - 0.4f * a * a) * (1f - 0.67f) * h * 0.6f
        }
        for (p in listOf(0f, 0.3f, 0.67f, 0.9f, 1f)) {
            assertEquals(shipped(p), HangarRenderer.hyperStreakOffset(p, 0.67f, h), 0f)
        }
    }

    @Test
    fun `the arrival decelerates rather than jumping`() {
        val span = HangarRenderer.launchSpan(w, h)
        val atStart = HangarRenderer.hyperStreakOffset(0.67f, 0.67f, span)
        val justBefore = HangarRenderer.hyperStreakOffset(0.6699f, 0.67f, span)
        assertEquals("continuous at the join", atStart, justBefore, 0.5f)
        assertTrue(HangarRenderer.hyperStreakOffset(1f, 0.67f, span) > atStart)
    }
}
