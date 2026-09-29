package com.astroloop.game.render

import com.astroloop.game.core.DesignSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HudBandTest proves the rule; this proves the renderer actually uses it. Robolectric because
 * HUDRenderer builds its Paints at construction and a bare JVM Paint is not mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HudBandWiringTest {

    private val phoneSw = 411

    @Test
    fun `in landscape the renderer takes a centred band, not the full width`() {
        val layout = DesignSpace.metricsFor(2400f, 1080f).layout
        val renderer = HUDRenderer()
        renderer.initialize(layout, phoneSw)

        assertEquals(HudBand.left(layout, phoneSw), renderer.hudLeft, 0f)
        assertEquals(HudBand.right(layout, phoneSw), renderer.hudRight, 0f)
        assertTrue(
            "the bar must not span the screen",
            renderer.hudRight - renderer.hudLeft < layout.width * 0.6f
        )
        assertEquals(
            "centred in safe",
            layout.safe.centerX,
            (renderer.hudLeft + renderer.hudRight) / 2f,
            0.01f
        )
    }

    @Test
    fun `in portrait the renderer keeps the anchors that shipped`() {
        val layout = DesignSpace.metricsFor(1080f, 2400f).layout
        val renderer = HUDRenderer()
        renderer.initialize(layout, phoneSw)

        assertEquals(layout.safe.left, renderer.hudLeft, 0f)
        assertEquals(layout.safe.right, renderer.hudRight, 0f)
    }

    @Test
    fun `an uninitialised renderer reads as portrait`() {
        // The field's default is the portrait design rect, so the first frame after construction
        // must not take the landscape branch.
        val renderer = HUDRenderer()

        assertTrue("must not be wider than it is tall", renderer.hudRight - renderer.hudLeft <= 960f)
    }
}
