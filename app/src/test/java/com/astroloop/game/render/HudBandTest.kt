package com.astroloop.game.render

import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.GameConfig
import com.astroloop.game.core.ScreenLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The combat HUD's horizontal rule. Plain JVM on purpose — HudBand has no Android types, and
 * this battery is the reason it was extracted from HUDRenderer: HudSafeAreaTest used to
 * re-implement the anchor math, which is how HangarMetricsTest went on passing while modelling
 * a device that had stopped existing.
 */
class HudBandTest {

    /** Below the gate: a phone or a TV. Above it: a tablet or an unfolded foldable. */
    private val phoneSw = 411
    private val tvSw = 540
    private val tabletSw = 800

    @Test
    fun `portrait keeps the shipped anchors exactly`() {
        // The regression gate for the whole change. Compared against the same expressions that
        // shipped, so zero delta is meaningful rather than optimistic.
        val layouts = listOf(
            DesignSpace.metricsFor(1080f, 2400f).layout,
            DesignSpace.metricsFor(1080f, 2424f, insetTopPx = 128f).layout,
            DesignSpace.metricsFor(1080f, 1920f).layout,
            DesignSpace.metricsFor(1600f, 2560f).layout,
            DesignSpace.metricsFor(1600f, 2560f, insetTopPx = 128f).layout,
            DesignSpace.metricsFor(2076f, 2152f, insetTopPx = 80f, insetRightPx = 60f).layout
        )
        for (sw in listOf(360, 411, 540, 599, 600, 800)) {
            val large = sw >= ScreenLayout.LARGE_SCREEN_MIN_SW_DP
            for (layout in layouts) {
                val expectedLeft = if (large) layout.content.left else layout.safe.left
                val expectedRight = if (large) layout.content.right else layout.safe.right

                assertEquals("left at sw$sw", expectedLeft, HudBand.left(layout, sw), 0f)
                assertEquals("right at sw$sw", expectedRight, HudBand.right(layout, sw), 0f)
            }
        }
    }

    @Test
    fun `rotation never changes the HUD width`() {
        // The invariant the design rests on: equal design units are equal millimetres in both
        // orientations, so this is "the HUD is physically the same size rotated".
        // Tolerance is 0.01 rather than 0 only because the portrait side above the gate goes
        // through a multiply-divide round trip (content width is safeH * designAspect), which
        // can land a few ULPs off 960. A real regression here is tens of units, not hundredths.
        val devices = listOf(
            Triple(1080f, 2400f, phoneSw),   // Pixel 9 Pro
            Triple(1080f, 1920f, phoneSw),   // 16:9 phone
            Triple(1080f, 1920f, tvSw),      // TV 1080p — below the gate, which is why it matters
            Triple(2160f, 3840f, tvSw),      // TV 4K
            Triple(1022f, 2400f, phoneSw),   // shotbox surface
            Triple(1600f, 2560f, tabletSw),  // tablet
            Triple(2076f, 2152f, tabletSw)   // fold inner
        )
        for ((short, long, sw) in devices) {
            val portrait = DesignSpace.metricsFor(short, long).layout
            val landscape = DesignSpace.metricsFor(long, short).layout

            assertEquals(
                "rotating ${short}x$long changed the HUD width",
                HudBand.width(portrait, sw),
                HudBand.width(landscape, sw),
                0.01f
            )
        }
    }

    @Test
    fun `the landscape band takes the device's own portrait width`() {
        val cases = listOf(
            Triple(2400f, 1080f, phoneSw) to 963.90f,     // Pixel 9 Pro
            Triple(1920f, 1080f, phoneSw) to 1204.875f,   // 16:9 phone
            Triple(1920f, 1080f, tvSw) to 1204.875f,      // TV 1080p
            Triple(3840f, 2160f, tvSw) to 1204.875f,      // TV 4K
            Triple(2400f, 1022f, phoneSw) to 960f,        // shotbox surface
            // Above the gate the portrait HUD is already narrowed to the design column, so the
            // band matches that rather than the panel's wider portrait design width (1338.75 on
            // the tablet, 2066.35 on the fold — the latter would be the stretch all over again).
            Triple(2560f, 1600f, tabletSw) to GameConfig.DESIGN_WIDTH,
            Triple(2152f, 2076f, tabletSw) to GameConfig.DESIGN_WIDTH
        )
        for ((case, expected) in cases) {
            val (w, h, sw) = case
            val layout = DesignSpace.metricsFor(w, h).layout

            assertTrue("${w}x$h must be landscape", HudBand.isLandscape(layout))
            assertEquals("band width at ${w}x$h", expected, HudBand.width(layout, sw), 0.01f)
        }
    }

    @Test
    fun `the landscape band is centred in safe and never spans the screen`() {
        val layout = DesignSpace.metricsFor(1920f, 1080f).layout

        val left = HudBand.left(layout, phoneSw)
        val right = HudBand.right(layout, phoneSw)
        assertEquals("centred", layout.safe.centerX, (left + right) / 2f, 0.01f)
        assertTrue("must not span the screen", right - left < layout.width * 0.6f)
        assertTrue("inside safe on the left", left >= layout.safe.left)
        assertTrue("inside safe on the right", right <= layout.safe.right)
    }

    @Test
    fun `a side cutout moves the band off the screen centre, not into the inset`() {
        val layout = DesignSpace.metricsFor(1920f, 1080f, insetLeftPx = 128f).layout

        val left = HudBand.left(layout, phoneSw)
        val right = HudBand.right(layout, phoneSw)
        assertEquals("centred in safe", layout.safe.centerX, (left + right) / 2f, 0.01f)
        assertTrue("drifts away from the notch", (left + right) / 2f > layout.width / 2f)
        assertTrue("never reaches into the inset", left >= layout.safe.left)
    }

    @Test
    fun `a pathological inset clamps the band to safe instead of overflowing it`() {
        // Fires on no real device: the widest nominal is 1204.875 while landscape safe.width is
        // at least ~1999 even with a 128px cutout. Kept as the horizontal half of "vertical
        // insets never change the width, horizontal ones can only narrow it", and as what makes
        // "never clipped" structural. A guard, not a rule — do not delete it as dead.
        val layout = DesignSpace.metricsFor(1920f, 1080f, insetLeftPx = 700f, insetRightPx = 700f).layout

        val left = HudBand.left(layout, phoneSw)
        val right = HudBand.right(layout, phoneSw)
        assertEquals("clamped to safe", layout.safe.width, right - left, 0.01f)
        assertTrue("inside safe on the left", left >= layout.safe.left)
        assertTrue("inside safe on the right", right <= layout.safe.right)
    }

    @Test
    fun `a vertical inset never changes the band width`() {
        val bare = DesignSpace.metricsFor(1920f, 1080f).layout
        val notched = DesignSpace.metricsFor(1920f, 1080f, insetTopPx = 128f).layout

        assertEquals(
            "a status bar must not narrow the band",
            HudBand.width(bare, phoneSw),
            HudBand.width(notched, phoneSw),
            0f
        )
    }
}
