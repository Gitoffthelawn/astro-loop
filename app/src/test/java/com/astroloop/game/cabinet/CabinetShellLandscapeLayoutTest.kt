package com.astroloop.game.cabinet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * BELT RUN's non-play screens on a landscape tube.
 *
 * The portrait menu stacks PLAY, SCORES, EXIT (and ???) down ~6.4 button heights below 0.75 of
 * the height, which ran off the bottom of a rotated phone. Landscape lays them out as one row;
 * these pin that the row is a row, fits the screen, and overlaps nothing — and that portrait is
 * still the column it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CabinetShellLandscapeLayoutTest {

    private val board = List(12) { BoardRow("P${it.toString().padStart(2, '0')}", 100 + it, it % 3 == 0) }

    private fun menuRects(
        w: Float, h: Float, credits: Int = 1, replay: Boolean = false
    ): Map<String, RectF> {
        val m = CabinetMetrics(w, h)
        val shell = CabinetShell(m, Random(1), { true }, { }, { false })
        val renderer = CabinetShellRenderer(m, CabinetRenderer(m))
        val canvas = Canvas(Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888))
        renderer.draw(canvas, shell, board, credits, canAfford = true, coinCost = 100,
            replayAvailable = replay, bestReleaseSeconds = 42)
        return renderer.focusRegistry.targets().associate { it.id to it.rect }
    }

    private fun assertOnScreenAndDisjoint(rects: Map<String, RectF>, w: Float, h: Float) {
        for ((id, r) in rects) {
            assertTrue("$id off screen: $r", r.left >= 0f && r.top >= 0f && r.right <= w && r.bottom <= h)
        }
        val list = rects.entries.toList()
        for (i in list.indices) for (j in i + 1 until list.size) {
            assertTrue(
                "${list[i].key} overlaps ${list[j].key}",
                !RectF.intersects(list[i].value, list[j].value)
            )
        }
    }

    @Test
    fun `landscape menu buttons sit in one row, left to right`() {
        for (credits in listOf(0, 1)) for (replay in listOf(false, true)) {
            val rects = menuRects(2856f, 1280f, credits, replay)
            val expected = if (replay) listOf("shell:PLAY", "shell:SCORES", "shell:EXIT", "shell:REPLAY")
            else listOf("shell:PLAY", "shell:SCORES", "shell:EXIT")
            assertEquals(expected, rects.entries.sortedBy { it.value.left }.map { it.key })
            val centres = rects.values.map { it.centerY() }
            assertEquals("one centre line", centres.first(), centres.max(), 0.5f)
            assertEquals("one centre line", centres.first(), centres.min(), 0.5f)
            assertOnScreenAndDisjoint(rects, 2856f, 1280f)
        }
    }

    @Test
    fun `landscape menu fits a 16 by 9 tube with all four buttons priced`() {
        assertOnScreenAndDisjoint(menuRects(1920f, 1080f, credits = 0, replay = true), 1920f, 1080f)
    }

    @Test
    fun `portrait menu is still a column`() {
        val rects = menuRects(1080f, 2400f, replay = true)
        val lefts = rects.values.map { it.centerX() }
        assertEquals(540f, lefts.min(), 0.5f)
        assertEquals(540f, lefts.max(), 0.5f)
        assertEquals(
            listOf("shell:PLAY", "shell:SCORES", "shell:EXIT", "shell:REPLAY"),
            rects.entries.sortedBy { it.value.top }.map { it.key }
        )
        assertOnScreenAndDisjoint(rects, 1080f, 2400f)
    }

    @Test
    fun `a cabinet opened in portrait lays its menu out as a row once rotated`() {
        // The owner's bug: the metrics were fixed at open time, so rotating left the menu laid out
        // for the old screen. resize() is what the host calls on the render thread afterwards.
        val m = CabinetMetrics(1280f, 2856f)
        val shell = CabinetShell(m, Random(1), { true }, { }, { false })
        val renderer = CabinetShellRenderer(m, CabinetRenderer(m))
        shell.resize(2856f, 1280f)
        val canvas = Canvas(Bitmap.createBitmap(2856, 1280, Bitmap.Config.ARGB_8888))
        renderer.draw(canvas, shell, board, 1, canAfford = true, coinCost = 100)
        val rects = renderer.focusRegistry.targets().associate { it.id to it.rect }

        assertEquals(
            listOf("shell:PLAY", "shell:SCORES", "shell:EXIT"),
            rects.entries.sortedBy { it.value.left }.map { it.key }
        )
        assertOnScreenAndDisjoint(rects, 2856f, 1280f)
    }

    @Test
    fun `resize keeps everything in the field inside the new bounds`() {
        val m = CabinetMetrics(1280f, 2856f)
        val shell = CabinetShell(m, Random(1), { true }, { }, { false })
        repeat(120) { shell.update(1f / 60f, 0f, 0f, false) }   // let the attract demo populate
        shell.resize(2856f, 1280f)

        assertEquals(2856f, m.width, 0f)
        assertEquals(1280f, m.minEdge, 0f)
        for (sim in listOf(shell.sim, shell.attractSim)) {
            assertTrue(sim.ship.x in 0f..2856f && sim.ship.y in 0f..1280f)
            for (r in sim.rocks) assertTrue("rock at ${r.x},${r.y}", r.x in 0f..2856f && r.y in 0f..1280f)
        }
        assertTrue("the demo had something to move", shell.attractSim.rocks.isNotEmpty())
    }

    @Test
    fun `corner clearance follows the arc and never reaches below it`() {
        assertEquals(0f, CabinetRenderer.cornerClearance(0f, 0f), 0f)       // square corner
        assertEquals(150f, CabinetRenderer.cornerClearance(150f, 0f), 0.01f) // the very top edge
        assertEquals(0f, CabinetRenderer.cornerClearance(150f, 150f), 0.01f) // at the arc's foot
        assertEquals(0f, CabinetRenderer.cornerClearance(150f, 400f), 0.01f) // below it
        // Halfway down: 150 - sqrt(150^2 - 75^2) = 20.1
        assertEquals(20.1f, CabinetRenderer.cornerClearance(150f, 75f), 0.05f)
    }
}
