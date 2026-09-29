package com.astroloop.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignSpaceTest {

    /** The formula that shipped, so "portrait is unchanged" is checked against the real thing. */
    private fun shippedPortraitScale(physW: Float, physH: Float): Float =
        minOf(physW / GameConfig.DESIGN_WIDTH, physH / GameConfig.DESIGN_HEIGHT)

    @Test
    fun `portrait keeps the shipped rect`() {
        assertFalse(DesignSpace.isLandscape(1080f, 2424f))
        assertEquals(GameConfig.DESIGN_WIDTH, DesignSpace.designWidth(1080f, 2424f), 0.01f)
        assertEquals(GameConfig.DESIGN_HEIGHT, DesignSpace.designHeight(1080f, 2424f), 0.01f)
    }

    @Test
    fun `landscape transposes the rect`() {
        assertTrue(DesignSpace.isLandscape(1920f, 1080f))
        assertEquals(GameConfig.DESIGN_HEIGHT, DesignSpace.designWidth(1920f, 1080f), 0.01f)
        assertEquals(GameConfig.DESIGN_WIDTH, DesignSpace.designHeight(1920f, 1080f), 0.01f)
    }

    @Test
    fun `every portrait profile keeps the exact shipped scale`() {
        // The regression gate for the whole release. Not approximately equal — the orientation
        // test never fires in portrait, so these must agree to the digit.
        val profiles = listOf(
            1080f to 2424f,   // Pixel 9 Pro, the owner's device
            1600f to 2560f,   // tablet portrait
            2076f to 2152f,   // fold inner, portrait
            1080f to 1920f,   // compact 16:9
            1080f to 2640f    // ultra-tall 22:9
        )
        for ((w, h) in profiles) {
            assertEquals(
                "portrait scale changed at ${w}x$h",
                shippedPortraitScale(w, h),
                DesignSpace.renderScale(w, h),
                0f
            )
        }
    }

    @Test
    fun `landscape stays legible instead of halving`() {
        // A 1080p panel: 0.896 against the 0.504 the portrait rect gives, i.e. 24 design units of
        // body text at 21.5px rather than 12.1px.
        assertEquals(0.8964f, DesignSpace.renderScale(1920f, 1080f), 0.001f)
        assertTrue(
            "landscape must not render at half size",
            DesignSpace.renderScale(1920f, 1080f) > shippedPortraitScale(1920f, 1080f) * 1.7f
        )
    }

    @Test
    fun `the switch is continuous at a square screen`() {
        // The two rects are transposes, so both give S / DESIGN_HEIGHT. Fold inner panels sit
        // within 4% of square, so a jump here would be visible on real hardware.
        val s = 2000f
        assertEquals(s / GameConfig.DESIGN_HEIGHT, DesignSpace.renderScale(s, s), 0f)
    }

    @Test
    fun `a square screen counts as portrait`() {
        assertFalse(DesignSpace.isLandscape(2000f, 2000f))
    }

    @Test
    fun `the aspect transposes with the rect`() {
        val portrait = DesignSpace.designAspect(1080f, 2424f)
        val landscape = DesignSpace.designAspect(2424f, 1080f)
        assertEquals(GameConfig.DESIGN_WIDTH / GameConfig.DESIGN_HEIGHT, portrait, 0.0001f)
        assertEquals(1f / portrait, landscape, 0.0001f)
    }

    @Test
    fun `a zero measurement behaves exactly as it does today`() {
        // surfaceChanged really does fire at 0x0. This must stay 0f so the callers' existing NaN
        // handling (HangarSurfaceView's re-anchor block) is reached unchanged.
        assertEquals(shippedPortraitScale(0f, 0f), DesignSpace.renderScale(0f, 0f), 0f)
    }
}
