package com.astroloop.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The portrait-shaped layout is what gives a landscape panel true portrait card sizes.
 *
 * The strong test is not "it transposes" — it is that a rotated device's portrait-shaped layout
 * equals the SAME device's real portrait layout, content rect included. A cutout is physical: it
 * is the portrait top inset and the landscape side inset, the same magnitude, so rotating the
 * inset set with the axes must reproduce portrait exactly.
 */
class PortraitShapedLayoutTest {

    @Test
    fun `portrait is returned unchanged, not rebuilt`() {
        val m = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = 128f)
        assertSame(m.layout, DesignSpace.portraitShaped(m.layout))
    }

    @Test
    fun `a rotated device's portrait-shaped layout is its own portrait layout`() {
        val portrait = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = 128f).layout
        val landscape = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f).layout

        val shaped = DesignSpace.portraitShaped(landscape)

        assertEquals("width", portrait.width, shaped.width, 0.01f)
        assertEquals("height", portrait.height, shaped.height, 0.01f)
        assertEquals("content width", portrait.content.width, shaped.content.width, 0.01f)
        assertEquals("content height", portrait.content.height, shaped.content.height, 0.01f)
    }

    @Test
    fun `the cutout survives rotation in either direction`() {
        // Rotated the other way the cutout lands on the right edge. Sizes must not care: the
        // pilot card's height is derived from content.height and its width from content.width,
        // neither of which depends on which end of the axis the inset sits.
        val left = DesignSpace.portraitShaped(DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f).layout)
        val right = DesignSpace.portraitShaped(DesignSpace.metricsFor(2844f, 1280f, insetRightPx = 128f).layout)

        assertEquals(left.content.width, right.content.width, 0.01f)
        assertEquals(left.content.height, right.content.height, 0.01f)
    }

    @Test
    fun `no insets still transposes the rect`() {
        val shaped = DesignSpace.portraitShaped(DesignSpace.metricsFor(2400f, 1080f).layout)
        assertEquals(1080f / DesignSpace.renderScale(2400f, 1080f), shaped.width, 0.01f)
        assertEquals(2400f / DesignSpace.renderScale(2400f, 1080f), shaped.height, 0.01f)
    }

    @Test
    fun `a side inset plus a top-or-bottom inset lands at the real portrait position`() {
        // A reflection instead of a rotation gives identical SIZES to the correct mapping --
        // ScreenLayout.compute derives content width/height from the inset *sum*, which can't
        // distinguish left-right swapped from top-bottom swapped. None of the fixtures above
        // combine a side inset with a top-or-bottom inset, so none can tell a rotation from its
        // mirror image apart; this one carries both at once and checks POSITION, not size.
        //
        // Same correspondence the other fixtures use (portrait insetTop <-> landscape insetLeft),
        // extended one step clockwise: portrait insetRight <-> landscape insetTop.
        val portrait = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = 128f, insetRightPx = 40f).layout
        val landscape = DesignSpace.metricsFor(2844f, 1280f, insetLeftPx = 128f, insetTopPx = 40f).layout

        val shaped = DesignSpace.portraitShaped(landscape)

        assertEquals("content.left", portrait.content.left, shaped.content.left, 0.01f)
        assertEquals("content.top", portrait.content.top, shaped.content.top, 0.01f)
    }
}
