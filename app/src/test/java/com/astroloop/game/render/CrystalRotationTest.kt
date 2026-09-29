package com.astroloop.game.render

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.astroloop.game.core.DesignSpace
import com.astroloop.game.core.GameSurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The crystal covers the whole screen after a rotation, not the rectangle it was born in.
 *
 * The owner, 2026-09-20: "if you double tap to pause and you get the crystal effect on the
 * screen, rotating your screen makes the effect no longer full screen."
 *
 * [CrystalRenderer.activatePause] bakes an ABSOLUTE crack pattern spanning 0..screenWidth by
 * 0..screenHeight at the moment it is called, and nothing regenerated it on rotation —
 * `GameSurfaceView.surfaceChanged` re-initialises the camera, the starfield, the HUD, the spawn
 * and movement systems and more, but never the crystal. Rotate a phone while the pause crystal
 * is up and the cracks keep the portrait rect: a tall narrow band of cracks on a wide screen,
 * with the rest of the display bare.
 *
 * The death crystal is the same code and the same defect — and it is the story's own beat (the
 * freeze on every death IS the Time Crystal activating), so it is covered here too rather than
 * left for a later report.
 *
 * The pattern is deliberately REGENERATED rather than stretched. Cell shape is why: the grid
 * transposes with the screen ([CrystalRenderer.crackGrid], and `CrystalCrackGridTest` pins it),
 * so stretching a portrait pattern across a landscape screen would put back the 3.1:1 cells that
 * transposition exists to prevent. The cracks therefore re-shuffle at the instant of rotation,
 * which is unavoidable — the aspect they describe has changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CrystalRotationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // A Pixel 9 Pro, both ways up.
    private val portraitPx = 1280 to 2844
    private val landscapePx = 2844 to 1280

    private fun designSize(physW: Int, physH: Int): Pair<Float, Float> {
        val m = DesignSpace.metricsFor(physW.toFloat(), physH.toFloat())
        return m.width to m.height
    }

    private fun view(): GameSurfaceView =
        GameSurfaceView(context, "ship_blue", "pilot_medic") { _, _ -> }

    private fun rotate(view: GameSurfaceView, size: Pair<Int, Int>) {
        view.surfaceChanged(view.holder, 0, size.first, size.second)
    }

    /** How far the baked pattern actually reaches, as (maxX, maxY). */
    private fun extent(view: GameSurfaceView): Pair<Float, Float> {
        val segments = view.crystalRenderer.crackSegments
        assertTrue("there must be a pattern to measure", segments.isNotEmpty())
        val maxX = segments.maxOf { maxOf(it.x1, it.x2) }
        val maxY = segments.maxOf { maxOf(it.y1, it.y2) }
        return maxX to maxY
    }

    /**
     * A pattern spans its screen if it reaches the far edge on both axes. The jitter pulls grid
     * points either way by up to 0.35 of a cell, so the outermost points land near the edge
     * rather than exactly on it — hence a proportional tolerance rather than an equality.
     */
    private fun assertSpans(width: Float, height: Float, extent: Pair<Float, Float>, what: String) {
        assertTrue(
            "$what: cracks reach x=${extent.first} of a $width-wide screen",
            extent.first > width * 0.85f
        )
        assertTrue(
            "$what: cracks reach y=${extent.second} of a $height-tall screen",
            extent.second > height * 0.85f
        )
    }

    @Test
    fun `the pause crystal covers the screen after rotating to landscape`() {
        val view = view()
        rotate(view, portraitPx)
        val (pw, ph) = designSize(portraitPx.first, portraitPx.second)
        view.crystalRenderer.activatePause(pw, ph)
        assertSpans(pw, ph, extent(view), "portrait, before rotating")

        rotate(view, landscapePx)

        val (lw, lh) = designSize(landscapePx.first, landscapePx.second)
        assertSpans(lw, lh, extent(view), "landscape, after rotating")
    }

    @Test
    fun `the pause crystal covers the screen after rotating back to portrait`() {
        val view = view()
        rotate(view, landscapePx)
        val (lw, lh) = designSize(landscapePx.first, landscapePx.second)
        view.crystalRenderer.activatePause(lw, lh)
        assertSpans(lw, lh, extent(view), "landscape, before rotating")

        rotate(view, portraitPx)

        val (pw, ph) = designSize(portraitPx.first, portraitPx.second)
        assertSpans(pw, ph, extent(view), "portrait, after rotating")
    }

    /** Same code, same defect, and it is the one the story turns on. */
    @Test
    fun `the death crystal covers the screen after rotating`() {
        val view = view()
        rotate(view, portraitPx)
        val (pw, ph) = designSize(portraitPx.first, portraitPx.second)
        view.crystalRenderer.activateDeath(pw, ph)

        rotate(view, landscapePx)

        val (lw, lh) = designSize(landscapePx.first, landscapePx.second)
        assertSpans(lw, lh, extent(view), "death crystal after rotating")
    }

    /**
     * A rotation must not restart the animation. The pause crystal holds at its own coverage cap
     * while the game is paused, and the death crystal is mid-grow on a timer the death sequence
     * depends on — regenerating via `activatePause`/`activateDeath` would reset coverage to 0 and
     * replay the freeze, which on the death path would desync the beat it is cut to.
     */
    @Test
    fun `rotating keeps the animation where it was`() {
        val view = view()
        rotate(view, portraitPx)
        val (pw, ph) = designSize(portraitPx.first, portraitPx.second)
        view.crystalRenderer.activatePause(pw, ph)
        repeat(20) { view.crystalRenderer.update(0.016f) }

        val coverageBefore = view.crystalRenderer.coverageForTest
        val textBefore = view.crystalRenderer.textAlphaForTest
        assertTrue("the fixture must have advanced the animation", coverageBefore > 0f)

        rotate(view, landscapePx)

        assertEquals(
            "coverage must survive the rotation",
            coverageBefore, view.crystalRenderer.coverageForTest, 0f
        )
        assertEquals(
            "and so must the text fade", textBefore, view.crystalRenderer.textAlphaForTest, 0f
        )
        assertTrue("the crystal is still up", view.crystalRenderer.isActive)
        assertTrue("and still a pause, not a death", view.crystalRenderer.isPause)
    }

    /**
     * A rotation while nothing is frozen must not conjure a pattern — the renderer is inert
     * between a death and the next pause, and `surfaceChanged` fires on every resume from
     * background, not only on a real rotation.
     */
    @Test
    fun `rotating with no crystal up leaves it inert`() {
        val view = view()
        rotate(view, portraitPx)
        assertTrue(view.crystalRenderer.crackSegments.isEmpty())

        rotate(view, landscapePx)

        assertTrue("no pattern may appear", view.crystalRenderer.crackSegments.isEmpty())
        assertTrue("and nothing may become active", !view.crystalRenderer.isActive)
    }
}
