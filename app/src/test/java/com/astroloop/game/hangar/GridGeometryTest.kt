package com.astroloop.game.hangar

import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.LayoutRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Golden values are the arithmetic that shipped, inlined here on purpose: this task must move
 * the geometry without changing it, and a test that calls the new code twice cannot prove that.
 */
class GridGeometryTest {

    private val portrait = DesignSpace.metricsFor(1280f, 2844f).layout
    private val withCutout = DesignSpace.metricsFor(1280f, 2844f, insetTopPx = 128f).layout

    private fun oldPilotBounds(l: com.astroloop.game.core.ScreenLayout): LayoutRect {
        val c = l.content
        return LayoutRect(c.left + 12f, c.top + 70f, c.right - 12f, c.top + c.height * 0.52f)
    }

    @Test
    fun `pilot grid bounds are the shipped bounds, with and without a cutout`() {
        for (l in listOf(portrait, withCutout)) {
            val expected = oldPilotBounds(l)
            val actual = GridGeometry.pilotGridBounds(l.content, l.width, l.width)
            assertEquals(expected.left, actual.left, 0.001f)
            assertEquals(expected.top, actual.top, 0.001f)
            assertEquals(expected.right, actual.right, 0.001f)
            assertEquals(expected.bottom, actual.bottom, 0.001f)
        }
    }

    @Test
    fun `pilot card size is the shipped size, with a cutout`() {
        val b = oldPilotBounds(withCutout)
        val expectedW = (b.width - 8f * 3f) / 4f
        val expectedH = (b.height - 8f * 2f) / 3f
        val (w, h) = GridGeometry.pilotCardSize(GridGeometry.pilotGridBounds(withCutout.content, withCutout.width, withCutout.width))
        assertEquals(expectedW, w, 0.001f)
        assertEquals(expectedH, h, 0.001f)
    }

    @Test
    fun `there are exactly twelve card rects and they march left to right, top to bottom`() {
        val bounds = GridGeometry.pilotGridBounds(withCutout.content, withCutout.width, withCutout.width)
        val rects = GridGeometry.pilotCardRects(bounds, 12)
        assertEquals(12, rects.size)
        assertEquals(bounds.left, rects[0].left, 0.001f)
        assertEquals(bounds.top, rects[0].top, 0.001f)
        // card 4 starts the second row
        assertEquals(bounds.left, rects[4].left, 0.001f)
        assertEquals(rects[0].bottom + 8f, rects[4].top, 0.001f)
    }

    @Test
    fun `pilotIndexAt matches the rects it is the inverse of`() {
        val bounds = GridGeometry.pilotGridBounds(withCutout.content, withCutout.width, withCutout.width)
        val rects = GridGeometry.pilotCardRects(bounds, 12)
        for ((i, r) in rects.withIndex()) {
            assertEquals(i, GridGeometry.pilotIndexAt(bounds, r.centerX, r.centerY, 12))
        }
    }

    @Test
    fun `a tap in the gap between cards hits nothing`() {
        val bounds = GridGeometry.pilotGridBounds(withCutout.content, withCutout.width, withCutout.width)
        val rects = GridGeometry.pilotCardRects(bounds, 12)
        val gapX = rects[0].right + 4f
        assertNull(GridGeometry.pilotIndexAt(bounds, gapX, rects[0].centerY, 12))
        val gapY = rects[0].bottom + 4f
        assertNull(GridGeometry.pilotIndexAt(bounds, rects[0].centerX, gapY, 12))
    }

    @Test
    fun `store tile size is the shipped size`() {
        val c = withCutout.content
        val walkwayY = withCutout.height * 0.60f
        val expected = minOf(
            (c.width - 16f * 2f - 10f * 2f) / 3f,
            ((walkwayY - 20f) - 70f - 10f * 2f) / 3f
        )
        assertEquals(expected, GridGeometry.storeTileSize(c, walkwayY), 0.001f)
    }

    @Test
    fun `the machine spans the grid's width and hangs below the walkway`() {
        val c = withCutout.content
        val walkwayY = withCutout.height * 0.60f
        val bounds = GridGeometry.storeGridBounds(c, c.left, walkwayY)
        val frame = GridGeometry.machineFrame(bounds, walkwayY, withCutout.height)
        assertEquals(bounds.left, frame.left, 0.001f)
        assertEquals(bounds.width, frame.width, 0.001f)
        assertEquals(walkwayY + 15f, frame.top, 0.001f)
        assertEquals(withCutout.height * 0.92f, frame.bottom, 0.001f)
    }
}
