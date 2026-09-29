package com.astroloop.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far the canyon wall is drawn past the corridor, against how far the player can see.
 *
 * Same shape as DesertDespawnDistanceTest: derive the worst case from the real constants, then
 * assert the helper covers it. The corridor is fixed in world space while the viewport is not, so
 * a wide screen sees past the end of the drawn rock.
 */
class DesertCanyonWallTest {

    private val corridorRight = GameSurfaceView.DESERT_CORRIDOR_HALF_WIDTH
    private val shipClamp = corridorRight - 25f // the player is clamped 25 inside the wall

    /** Farthest world X a centred camera can show, with the player pinned against the wall. */
    private fun worstCaseVisibleX(screenWidth: Float): Float = shipClamp + screenWidth / 2f

    @Test
    fun `a design-aspect portrait phone gets exactly the depth that shipped`() {
        // 960/2 + 15 = 495, under the 500 floor. A Pixel 9 Pro, and any phone at or narrower than
        // the design aspect, is bit-identical.
        assertEquals(500f, GameSurfaceView.desertWallDepth(960f), 0f)
    }

    @Test
    fun `wider portrait devices were already short, and gain coverage`() {
        // Portrait design width is only 960 on devices NARROWER than the design aspect. A 16:9
        // phone in portrait is 1204.9 wide, a portrait tablet 1338.8, a fold inner panel 2066.4 —
        // and at the shipped 500 all three could already see past the drawn rock. Widening them is
        // a fix, not a regression, so both halves are asserted: the gap was there, and it is gone.
        val alreadyShort = mapOf(1204.9f to 77.4f, 1338.8f to 144.4f, 2066.4f to 508.2f)
        for ((designWidth, expectedOldGap) in alreadyShort) {
            assertEquals(
                "the shipped 500 must already fall short at design width $designWidth",
                expectedOldGap,
                worstCaseVisibleX(designWidth) - (corridorRight + 500f),
                0.2f
            )
            assertTrue(
                "and the new depth must cover it at $designWidth",
                corridorRight + GameSurfaceView.desertWallDepth(designWidth) >=
                    worstCaseVisibleX(designWidth)
            )
        }
    }

    @Test
    fun `the wall always reaches past what the player can see`() {
        val widths = listOf(960f, 1204.8f, 1339f, 2142f, 2155f, 2276f, 3427f, 4808f)
        for (w in widths) {
            assertTrue(
                "wall stops short at screen width $w",
                corridorRight + GameSurfaceView.desertWallDepth(w) >= worstCaseVisibleX(w)
            )
        }
    }

    @Test
    fun `the shipped constant would not have covered a landscape panel`() {
        // Why this change exists: 546 design units of nothing beyond the rock at 16:9.
        assertTrue(corridorRight + 500f < worstCaseVisibleX(2142f))
    }
}
