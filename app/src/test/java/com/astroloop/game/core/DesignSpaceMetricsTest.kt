package com.astroloop.game.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole screen-dimension derivation, which both SurfaceViews used to carry a copy of.
 *
 * Testing it here rather than through a re-implementation in the test is the point: HangarMetricsTest
 * inlined the formula instead of calling it, and went on passing while modelling a device that had
 * stopped existing.
 */
class DesignSpaceMetricsTest {

    @Test
    fun `a portrait phone gets exactly the metrics that shipped`() {
        val m = DesignSpace.metricsFor(1080f, 2424f)

        assertEquals(1.125f, m.renderScale, 0.0001f)
        assertEquals(960f, m.width, 0.01f)
        assertEquals(2154.67f, m.height, 0.1f)
        assertEquals(960f, m.layout.content.width, 0.5f)
        assertEquals(2142f, m.layout.content.height, 0.5f)
    }

    @Test
    fun `a 1080p landscape panel gets a wide content band`() {
        val m = DesignSpace.metricsFor(1920f, 1080f)

        assertEquals(0.8964f, m.renderScale, 0.001f)
        assertEquals(2142f, m.width, 1f)
        assertEquals(1205f, m.height, 1f)
        // The transpose of the portrait column: the full width, 960 tall, centred vertically.
        assertEquals(2142f, m.layout.content.width, 1f)
        assertEquals(960f, m.layout.content.height, 1f)
        assertEquals(m.height / 2f, m.layout.content.centerY, 0.5f)
    }

    @Test
    fun `insets arrive in physical pixels and come out in design units`() {
        val m = DesignSpace.metricsFor(1080f, 2424f, insetTopPx = 128f)

        assertEquals(128f / 1.125f, m.layout.safe.top, 0.01f)
    }

    @Test
    fun `a side cutout in landscape narrows safe horizontally`() {
        // Rotating moves the cutout to a side edge. This is the case the yen counter has to
        // survive, and the reason edge-anchored UI pins to layout.safe.
        val m = DesignSpace.metricsFor(1920f, 1080f, insetLeftPx = 128f)

        assertEquals(128f / m.renderScale, m.layout.safe.left, 0.01f)
        assertTrue("content must stay inside safe", m.layout.content.left >= m.layout.safe.left)
    }

    @Test
    fun `a zero measurement still yields NaN dimensions`() {
        // Deliberate, not an oversight: HangarSurfaceView's re-anchor block keys off
        // state.pilotX.isNaN(), which originates here. Guarding it would silently disable that
        // recovery path on the first frame after a surface is created.
        val m = DesignSpace.metricsFor(0f, 0f)

        assertEquals(0f, m.renderScale, 0f)
        assertTrue(m.width.isNaN())
        assertTrue(m.height.isNaN())
    }
}
