package com.astroloop.game.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crack grid's cells, which must stay roughly square.
 *
 * 8x14 was chosen against a portrait screen. The crystal is the single most-seen visual in the
 * game — every death and every pause goes through it — so a stretched cell is not a small defect.
 */
class CrystalCrackGridTest {

    private fun cellAspect(screenWidth: Float, screenHeight: Float): Float {
        val (cols, rows) = CrystalRenderer.crackGrid(screenWidth, screenHeight)
        return (screenWidth / cols) / (screenHeight / rows)
    }

    @Test
    fun `portrait keeps the grid that shipped`() {
        assertEquals(
            CrystalRenderer.GRID_COLS to CrystalRenderer.GRID_ROWS,
            CrystalRenderer.crackGrid(960f, 2155f)
        )
    }

    @Test
    fun `landscape transposes it`() {
        assertEquals(
            CrystalRenderer.GRID_ROWS to CrystalRenderer.GRID_COLS,
            CrystalRenderer.crackGrid(2142f, 1205f)
        )
    }

    @Test
    fun `cells stay roughly square on every profile`() {
        val profiles = listOf(
            960f to 2155f,    // Pixel 9 Pro portrait
            960f to 2142f,    // design exact
            2142f to 1205f,   // 1080p landscape / TV
            2142f to 1339f,   // tablet landscape
            2155f to 960f     // Pixel 9 Pro landscape
        )
        for ((w, h) in profiles) {
            val aspect = cellAspect(w, h)
            assertTrue("cell aspect $aspect at ${w}x$h", aspect in 0.7f..1.4f)
        }
    }
}
