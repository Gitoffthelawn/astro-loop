package com.astroloop.game.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a railgun recall shot turns around.
 *
 * At the edge of the screen the player can see, in either orientation (owner, 2026-09-29). A
 * 2026-09-13 cap to the short edge made a rotated screen's shots turn in open space, 480 units
 * out against a 1071-unit half-screen — an invisible wall.
 */
class RecallTriggerTest {

    @Test
    fun `portrait is exactly the half-width that shipped`() {
        assertEquals(960f / 2f, Boss.recallHalfWidth(960f), 0f)
        assertEquals(1080f / 2f, Boss.recallHalfWidth(1080f), 0f)
        assertEquals(1204.8f / 2f, Boss.recallHalfWidth(1204.8f), 0f)
    }

    @Test
    fun `landscape turns the shot at the screen edge, not in open space`() {
        assertEquals(2142f / 2f, Boss.recallHalfWidth(2142f), 0f)
        assertEquals(2276f / 2f, Boss.recallHalfWidth(2276f), 0f)
    }

    @Test
    fun `the trigger never sits outside the viewport`() {
        // A trigger wider than the screen could never fire, and the shot would never come back.
        val profiles = listOf(960f to 2155f, 2142f to 1205f, 2276f to 960f, 3427f to 1339f)
        for ((w, h) in profiles) {
            assertTrue("trigger outside viewport at ${w}x$h", Boss.recallHalfWidth(w) <= w / 2f)
        }
    }
}
