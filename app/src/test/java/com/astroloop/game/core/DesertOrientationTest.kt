package com.astroloop.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * The desert flashback must play the same story whichever way the device is held (owner,
 * 2026-09-29), and "driving south" must count as stopping at Tobar's warning.
 */
class DesertOrientationTest {

    // A Pixel 9 Pro's design space, upright and rotated.
    private val portraitW = 960f
    private val portraitH = 2142f

    @Test
    fun `world distances use the same span upright and rotated`() {
        // The beach, the waterline and the settlement's distance are all multiples of this.
        assertEquals(
            GameSurfaceView.desertWorldSpan(portraitW, portraitH),
            GameSurfaceView.desertWorldSpan(portraitH, portraitW),
            0f
        )
    }

    @Test
    fun `upright the span is the screen height, so portrait is unchanged`() {
        assertEquals(portraitH, GameSurfaceView.desertWorldSpan(portraitW, portraitH), 0f)
    }

    @Test
    fun `nothing on a landscape screen can be despawned`() {
        // The single-argument radius was 1.1 of the height, which on a rotated phone (1056) is
        // shorter than the view's own half-diagonal (1173): an enemy near a corner was culled
        // while visible.
        val w = portraitH
        val h = portraitW
        val halfDiagonal = 0.5f * sqrt(w * w + h * h)
        assertTrue(GameSurfaceView.desertDespawnDistance(w, h) > halfDiagonal)
        assertEquals(
            "upright, the radius is what it always was",
            GameSurfaceView.desertDespawnDistance(portraitH),
            GameSurfaceView.desertDespawnDistance(portraitW, portraitH),
            0f
        )
    }

    @Test
    fun `a rotated phone sees the canyon walls at a centred ship`() {
        val halfView = portraitH / 2f
        val wallShown = halfView - GameSurfaceView.DESERT_CORRIDOR_HALF_WIDTH
        assertTrue("some rock at each edge, got $wallShown", wallShown > 0f)
        assertTrue("but only a strip of it, got $wallShown", wallShown < halfView * 0.2f)
    }

    private val north = (-PI / 2).toFloat()
    private val south = (PI / 2).toFloat()
    private val east = 0f

    @Test
    fun `only driving forward while facing north runs toward the horror path`() {
        assertTrue(GameSurfaceView.desertDrivingNorth(200f, north))
    }

    @Test
    fun `driving south counts as stopping`() {
        assertFalse("forward, facing south", GameSurfaceView.desertDrivingNorth(200f, south))
        assertFalse("reversing south while still facing north",
            GameSurfaceView.desertDrivingNorth(-100f, north))
        assertFalse("standing still", GameSurfaceView.desertDrivingNorth(0f, north))
        assertFalse("crawling, under the 20 threshold", GameSurfaceView.desertDrivingNorth(15f, north))
        assertFalse("driving along the canyon, not up it", GameSurfaceView.desertDrivingNorth(200f, east))
    }
}
