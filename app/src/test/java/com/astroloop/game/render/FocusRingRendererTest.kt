package com.astroloop.game.render

import android.graphics.RectF
import com.astroloop.game.input.FocusRegistry
import com.astroloop.game.input.FocusTarget
import com.astroloop.game.input.InputModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FocusRingRendererTest {

    private lateinit var renderer: FocusRingRenderer
    private lateinit var registry: FocusRegistry

    @Before
    fun setup() {
        InputModeState.reset()
        renderer = FocusRingRenderer()
        registry = FocusRegistry()
        registry.begin()
        registry.add(FocusTarget("a", RectF(0f, 0f, 40f, 40f)) {})
        registry.commit()
        registry.focusedId = "a"
    }

    @Test
    fun `never draws in touch mode`() {
        // The whole point: a player who only touches the screen never sees a focus ring.
        assertFalse(renderer.shouldDraw(registry))
    }

    @Test
    fun `draws in directional mode`() {
        InputModeState.markDirectional()
        renderer.update(0.1f)

        assertTrue(renderer.shouldDraw(registry))
    }

    @Test
    fun `does not draw with nothing focused`() {
        InputModeState.markDirectional()
        renderer.update(0.1f)
        registry.focusedId = null

        assertFalse(renderer.shouldDraw(registry))
    }

    @Test
    fun `does not draw when the focused id is stale`() {
        InputModeState.markDirectional()
        renderer.update(0.1f)
        registry.begin()
        registry.commit()

        assertFalse(renderer.shouldDraw(registry))
    }

    // --- V7 geometry and breath, pinned as pure functions ------------------------------------

    @Test
    fun `arms stay proportional to the shorter edge`() {
        // A card is tall, a store tile is square. Scaling off the shorter edge is what keeps the
        // brackets from meeting in the middle on a square target.
        assertEquals(63.36f, FocusRingRenderer.armLength(288f, 900f), 0.01f)
        assertEquals(66f, FocusRingRenderer.armLength(300f, 300f), 0.01f)
    }

    @Test
    fun `arms never meet on a square target`() {
        // Two arms plus the corners must not span the edge, or the brackets close into a border —
        // the one thing this shape exists to avoid.
        val side = 300f
        assertTrue(FocusRingRenderer.armLength(side, side) * 2f < side)
    }

    @Test
    fun `a disabled target rings dimmer than an enabled one`() {
        val enabled = FocusRingRenderer.ringAlpha(enabled = true, pulse = 0f)
        val disabled = FocusRingRenderer.ringAlpha(enabled = false, pulse = 0f)

        assertTrue("disabled must be visibly dimmer", disabled < enabled)
        assertEquals(191, enabled)   // 255 * 0.75 at the bottom of the breath
        assertEquals(82, disabled)   // 110 * 0.75
    }

    @Test
    fun `the breath stays inside its band`() {
        var p = 0f
        while (p < 10f) {
            val b = FocusRingRenderer.breathAt(p)
            assertTrue("breath $b out of band at $p", b in 0.4999f..1.0001f)
            p += 0.05f
        }
    }

    @Test
    fun `the pulse wraps instead of growing without bound`() {
        // Float precision dies on a large accumulator — the same trap the radio wave hit.
        // Wrapping on the breath period keeps the phase exact for a session of any length.
        val r = FocusRingRenderer()
        repeat(10_000) { r.update(0.016f) }

        assertTrue("pulse must stay bounded", r.pulseForTest < FocusRingRenderer.BREATH_PERIOD)
    }

    @Test
    fun `wrapping does not jump the breath`() {
        // Wrapping is only safe if it lands on the same phase: one period later must read the same.
        val atZero = FocusRingRenderer.breathAt(0f)
        val atPeriod = FocusRingRenderer.breathAt(FocusRingRenderer.BREATH_PERIOD)

        assertEquals(atZero, atPeriod, 0.0001f)
    }

    // --- the fade, so a finger arriving does not cut the ring off the screen ------------------

    @Test
    fun `starts invisible`() {
        assertEquals(0f, renderer.alpha, 0.0001f)
    }

    @Test
    fun `reaches full opacity and stops there`() {
        InputModeState.markDirectional()
        repeat(30) { renderer.update(0.1f) }

        assertEquals(1f, renderer.alpha, 0.0001f)
    }

    @Test
    fun `fades out rather than cutting when a finger arrives`() {
        // The hangar marks touch on the next ACTION_DOWN. Nothing in this game may vanish.
        InputModeState.markDirectional()
        repeat(30) { renderer.update(0.1f) }
        InputModeState.markTouch()
        renderer.update(0.05f)

        assertTrue("still visible on the frame after the touch", renderer.shouldDraw(registry))
        assertTrue(renderer.alpha < 1f)
    }

    @Test
    fun `and is gone once the fade finishes`() {
        InputModeState.markDirectional()
        repeat(30) { renderer.update(0.1f) }
        InputModeState.markTouch()
        repeat(30) { renderer.update(0.1f) }

        assertEquals(0f, renderer.alpha, 0.0001f)
        assertFalse(renderer.shouldDraw(registry))
    }

    @Test
    fun `a dim of zero draws nothing`() {
        // The launch fade hands this in: the ring must dissolve with the labels beside it rather
        // than hanging over a ship that has already left.
        InputModeState.markDirectional()
        repeat(30) { renderer.update(0.1f) }

        assertFalse(renderer.shouldDraw(registry, dim = 0f))
    }

    @Test
    fun `a partial dim still draws`() {
        InputModeState.markDirectional()
        repeat(30) { renderer.update(0.1f) }

        assertTrue(renderer.shouldDraw(registry, dim = 0.5f))
    }
}
