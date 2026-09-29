package com.astroloop.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLayoutTest {

    private val designAspect = GameConfig.DESIGN_WIDTH / GameConfig.DESIGN_HEIGHT // 960/2142

    @Test
    fun `design-exact screen content fills the whole space`() {
        val l = ScreenLayout.compute(width = 960f, height = 2142f)
        assertEquals(0f, l.content.left, 0.01f)
        assertEquals(0f, l.content.top, 0.01f)
        assertEquals(960f, l.content.width, 0.01f)
        assertEquals(2142f, l.content.height, 0.01f)
    }

    @Test
    fun `wider-than-design screen centers a design-aspect content block horizontally`() {
        val width = 1500f
        val l = ScreenLayout.compute(width = width, height = 2142f)
        assertEquals(2142f, l.content.height, 0.01f)
        assertEquals(2142f * designAspect, l.content.width, 0.01f)
        assertEquals(width / 2f, l.content.centerX, 0.01f)
        assertTrue(l.content.left >= l.safe.left)
        assertTrue(l.content.right <= l.safe.right)
    }

    @Test
    fun `taller-than-design screen centers content block vertically`() {
        val height = 2600f
        val l = ScreenLayout.compute(width = 960f, height = height)
        assertEquals(960f, l.content.width, 0.01f)
        assertEquals(960f / designAspect, l.content.height, 0.01f)
        assertEquals(height / 2f, l.content.centerY, 0.01f)
    }

    @Test
    fun `content keeps the design aspect ratio on any size`() {
        for (w in listOf(720f, 960f, 1500f, 2076f, 3000f)) {
            for (h in listOf(1600f, 2142f, 2152f, 2600f)) {
                val l = ScreenLayout.compute(width = w, height = h)
                assertEquals(
                    "aspect wrong at ${w}x$h",
                    designAspect,
                    l.content.width / l.content.height,
                    0.001f
                )
            }
        }
    }

    @Test
    fun `safe rect excludes insets and content stays inside safe`() {
        val l = ScreenLayout.compute(
            width = 960f, height = 2142f,
            insetLeft = 0f, insetTop = 90f, insetRight = 0f, insetBottom = 40f
        )
        assertEquals(90f, l.safe.top, 0.01f)
        assertEquals(2142f - 40f, l.safe.bottom, 0.01f)
        assertTrue(l.content.top >= l.safe.top)
        assertTrue(l.content.bottom <= l.safe.bottom)
    }

    @Test
    fun `custom square designAspect on a square safe area yields square content`() {
        // Exercises the branch boundary (safeW/safeH == designAspect) and a non-default aspect.
        val l = ScreenLayout.compute(width = 1000f, height = 1000f, designAspect = 1f)
        assertEquals(1000f, l.content.width, 0.01f)
        assertEquals(1000f, l.content.height, 0.01f)
        assertEquals(500f, l.content.centerX, 0.01f)
        assertEquals(500f, l.content.centerY, 0.01f)
    }

    @Test
    fun `zero-size measurement does not crash and content falls back to safe`() {
        val l = ScreenLayout.compute(width = 0f, height = 0f)
        assertEquals(0f, l.content.width, 0.01f)
        assertEquals(0f, l.content.height, 0.01f)
    }

    @Test
    fun `full rect always spans the whole design space ignoring insets`() {
        val l = ScreenLayout.compute(width = 1200f, height = 2000f, insetTop = 50f)
        assertEquals(0f, l.full.left, 0.01f)
        assertEquals(0f, l.full.top, 0.01f)
        assertEquals(1200f, l.full.width, 0.01f)
        assertEquals(2000f, l.full.height, 0.01f)
    }

    // ── The transposed design space ──────────────────────────────────────────

    private val landscapeAspect = GameConfig.DESIGN_HEIGHT / GameConfig.DESIGN_WIDTH // 2142/960

    @Test
    fun `a landscape design aspect gives a wide content band`() {
        // 1080p panel in design units: 2142 x 1205. The band is the full width and 960 tall,
        // centred vertically — the transpose of the portrait column.
        val l = ScreenLayout.compute(
            width = 2142f, height = 1205f, designAspect = landscapeAspect
        )
        assertEquals(2142f, l.content.width, 0.5f)
        assertEquals(960f, l.content.height, 0.5f)
        assertEquals(1205f / 2f, l.content.centerY, 0.01f)
    }

    @Test
    fun `an ultra-wide landscape screen pillarboxes the band instead of stretching it`() {
        // 21:9 in design units: 2276 x 960. Width is now the slack axis.
        val l = ScreenLayout.compute(
            width = 2276f, height = 960f, designAspect = landscapeAspect
        )
        assertEquals(2142f, l.content.width, 1f)
        assertEquals(960f, l.content.height, 0.5f)
        assertEquals(2276f / 2f, l.content.centerX, 0.01f)
    }

    @Test
    fun `content keeps the landscape design aspect on any size`() {
        for (w in listOf(2142f, 2276f, 3427f, 4808f)) {
            for (h in listOf(960f, 1205f, 1339f, 2066f)) {
                val l = ScreenLayout.compute(width = w, height = h, designAspect = landscapeAspect)
                assertEquals(
                    "aspect wrong at ${w}x$h",
                    landscapeAspect,
                    l.content.width / l.content.height,
                    0.001f
                )
            }
        }
    }

    @Test
    fun `shortEdge is the device's own portrait design width in either orientation`() {
        // renderScale is rotation-invariant: portrait takes min(w/960, h/2142) and landscape
        // min(w/2142, h/960), and rotating swaps w and h — the same two divisions, the same min.
        // So both sides below are shortEdge / renderScale with identical operands, and agree
        // bit-for-bit rather than to a tolerance. Zero delta is the point of the test.
        val profiles = listOf(
            1080f to 2400f,   // Pixel 9 Pro
            1080f to 2424f,   // Pixel 9 Pro, taller variant
            1080f to 1920f,   // 16:9 phone / TV 1080p
            2160f to 3840f,   // TV 4K
            1600f to 2560f,   // tablet
            2076f to 2152f,   // fold inner, near-square
            1022f to 2400f    // shotbox surface (system bars carved out)
        )
        for ((short, long) in profiles) {
            val portraitWidth = DesignSpace.metricsFor(short, long).width

            assertEquals(
                "portrait ${short}x$long",
                portraitWidth,
                DesignSpace.metricsFor(short, long).layout.shortEdge,
                0f
            )
            assertEquals(
                "landscape ${long}x$short",
                portraitWidth,
                DesignSpace.metricsFor(long, short).layout.shortEdge,
                0f
            )
        }
    }
}
